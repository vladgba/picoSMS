package x.vladgba.sms

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export/import of picoSMS configuration as readable JSON: settings, filter rules, labels and
 * Quick pick regexes. Messages and the blocked quarantine are never included.
 */
object Backup {
    private const val FORMAT = 1
    private val MODES = arrayOf("contains", "equals", "starts", "ends", "regex", "wildcard")

    class Parsed(
        val whitelistMode: Boolean?,
        val allowContacts: Boolean?,
        val pickOtp: Boolean?,
        val rules: List<Rule>,
        val labels: List<Label>,
        val pickers: List<Picker>
    )

    fun export(c: Context): String {
        val db = Db.get(c)
        return toJson(
            Rules.whitelistMode(c), Rules.allowContacts(c), Picks.otpEnabled(c),
            db.rules(), db.labels(), db.pickers()
        )
    }

    fun toJson(
        whitelistMode: Boolean, allowContacts: Boolean, pickOtp: Boolean,
        ruleList: List<Rule>, labelList: List<Label>, pickerList: List<Picker>
    ): String {
        val root = JSONObject()
        root.put("app", "picoSMS")
        root.put("format", FORMAT)

        val set = JSONObject()
        set.put("whitelistMode", whitelistMode)
        set.put("allowContacts", allowContacts)
        set.put("pickOtp", pickOtp)
        root.put("settings", set)

        val rules = JSONArray()
        for (r in ruleList) {
            val o = JSONObject()
            o.put("list", if (r.black) "black" else "white")
            o.put("enabled", r.enabled)
            o.put("senderMode", MODES[r.sMode])
            o.put("senders", lines(r.senders))
            o.put("textMode", MODES[r.bMode])
            o.put("texts", lines(r.bodies))
            o.put("caseSensitive", r.cs)
            rules.put(o)
        }
        root.put("rules", rules)

        val labels = JSONArray()
        for (l in labelList) {
            val o = JSONObject()
            o.put("name", l.name)
            o.put("senders", lines(l.senders))
            o.put("words", lines(l.words))
            o.put("matchBoth", l.both)
            o.put("hideFromInbox", l.hide)
            labels.put(o)
        }
        root.put("labels", labels)

        val pickers = JSONArray()
        for (p in pickerList) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("regex", p.regex)
            o.put("enabled", p.enabled)
            pickers.put(o)
        }
        root.put("pickers", pickers)
        return root.toString(2)
    }

    /** @throws Exception when [json] is not a picoSMS settings file. */
    fun parse(json: String): Parsed {
        val root = JSONObject(json)
        require(root.optString("app") == "picoSMS") { "not a picoSMS file" }
        val set = root.optJSONObject("settings")

        val rules = ArrayList<Rule>()
        root.optJSONArray("rules")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val r = Rule(
                    0, o.optString("list") != "white", o.optBoolean("enabled", true),
                    mode(o.optString("senderMode"), Mode.EQUALS), join(o.optJSONArray("senders")),
                    mode(o.optString("textMode"), Mode.CONTAINS), join(o.optJSONArray("texts")),
                    o.optBoolean("caseSensitive", false)
                )
                if (r.senders.isNotEmpty() || r.bodies.isNotEmpty()) rules.add(r)
            }
        }

        val labels = ArrayList<Label>()
        root.optJSONArray("labels")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val l = Label(0, o.optString("name").trim(), join(o.optJSONArray("senders")), join(o.optJSONArray("words")), o.optBoolean("matchBoth", false), o.optBoolean("hideFromInbox", false))
                if (l.name.isNotEmpty() && (l.senders.isNotEmpty() || l.words.isNotEmpty())) labels.add(l)
            }
        }

        val pickers = ArrayList<Picker>()
        root.optJSONArray("pickers")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val p = Picker(0, o.optString("name").trim(), o.optString("regex"), o.optBoolean("enabled", true))
                if (p.regex.isNotBlank()) pickers.add(if (p.name.isEmpty()) Picker(0, p.regex, p.regex, p.enabled) else p)
            }
        }

        return Parsed(
            if (set != null && set.has("whitelistMode")) set.optBoolean("whitelistMode") else null,
            if (set != null && set.has("allowContacts")) set.optBoolean("allowContacts") else null,
            if (set != null && set.has("pickOtp")) set.optBoolean("pickOtp") else null,
            rules, labels, pickers
        )
    }

    /**
     * Saves [p]. With [replace] the current rules, labels and pickers are deleted first;
     * otherwise entries identical to existing ones are skipped. All-or-nothing.
     * @return counts of rules, labels and pickers actually added.
     */
    fun apply(c: Context, p: Parsed, replace: Boolean): IntArray {
        val db = Db.get(c)
        val n = IntArray(3)
        db.tx {
            if (replace) {
                db.clear("rules")
                db.clear("labels")
                db.clear("pickers")
            }
            val haveRules = HashSet<String>()
            for (r in db.rules()) haveRules.add(key(r))
            for (r in p.rules) if (haveRules.add(key(r))) {
                db.saveRule(r)
                n[0]++
            }
            val haveLabels = HashSet<String>()
            val existing = db.labels()
            for (l in existing) haveLabels.add(key(l))
            var room = Labels.MAX - existing.size
            for (l in p.labels) if (room > 0 && haveLabels.add(key(l))) {
                db.saveLabel(0, l.name, l.senders, l.words, l.both, l.hide)
                n[1]++
                room--
            }
            val havePickers = HashSet<String>()
            for (k in db.pickers()) havePickers.add(k.name + "\u0000" + k.regex)
            for (k in p.pickers) if (havePickers.add(k.name + "\u0000" + k.regex)) {
                db.insertPicker(k.name, k.regex, k.enabled)
                n[2]++
            }
        }
        p.whitelistMode?.let { Rules.setWhitelistMode(c, it) }
        p.allowContacts?.let { Rules.setAllowContacts(c, it) }
        p.pickOtp?.let { Picks.setOtpEnabled(c, it) }
        return n
    }

    private fun key(r: Rule) = "${r.black}|${r.sMode}|${r.senders}|${r.bMode}|${r.bodies}|${r.cs}"
    private fun key(l: Label) = "${l.name}|${l.senders}|${l.words}|${l.both}|${l.hide}"

    private fun mode(name: String, def: Int): Int {
        for (i in MODES.indices) if (MODES[i] == name) return i
        return def
    }

    /** Stored newline lists become JSON arrays, which read better and survive editing. */
    private fun lines(s: String): JSONArray {
        val a = JSONArray()
        var start = 0
        while (start <= s.length) {
            val i = s.indexOf('\n', start).let { if (it < 0) s.length else it }
            val t = s.substring(start, i).trim()
            if (t.isNotEmpty()) a.put(t)
            start = i + 1
        }
        return a
    }

    private fun join(a: JSONArray?): String {
        if (a == null) return ""
        val b = StringBuilder()
        for (i in 0 until a.length()) {
            val t = a.optString(i).replace('\n', ' ').trim()
            if (t.isEmpty()) continue
            if (b.isNotEmpty()) b.append('\n')
            b.append(t)
        }
        return b.toString()
    }
}
