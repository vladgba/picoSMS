package x.vladgba.sms

import android.content.Context
import java.util.regex.Pattern

/**
 * A filter rule: an optional sender condition AND an optional message-text condition.
 * Each condition is a list of entries (any entry may match) checked with one [Mode].
 */
class Rule(
    val id: Long,
    val black: Boolean,
    val enabled: Boolean,
    val sMode: Int,
    val senders: String,
    val bMode: Int,
    val bodies: String,
    val cs: Boolean
) {
    companion object {
        /** Quick "block/allow this sender" rule. */
        fun sender(black: Boolean, addr: String) = Rule(0, black, true, Mode.EQUALS, addr, Mode.CONTAINS, "", false)
    }
}

/** How a condition compares its entries (also the order of the mode dropdown). */
object Mode {
    const val CONTAINS = 0
    const val EQUALS = 1
    const val STARTS = 2
    const val ENDS = 3
    const val REGEX = 4
    const val WILDCARD = 5

    /** Internal (labels): sender = smart equals, text = contains; '*' globs and /regex/ per entry. */
    const val AUTO = -1
}

/**
 * White/black list engine.
 *  1. Any matching enabled whitelist rule -> allow (whitelist always wins).
 *  2. Any matching enabled blacklist rule -> block.
 *  3. Whitelist mode on                   -> block, unless "allow contacts" and the sender is a contact.
 *  4. Otherwise                           -> allow.
 */
object Rules {
    private fun prefs(c: Context) = c.getSharedPreferences("pico", Context.MODE_PRIVATE)

    fun whitelistMode(c: Context) = prefs(c).getBoolean("wl", false)
    fun setWhitelistMode(c: Context, on: Boolean) = prefs(c).edit().putBoolean("wl", on).apply()
    fun allowContacts(c: Context) = prefs(c).getBoolean("contacts", true)
    fun setAllowContacts(c: Context, on: Boolean) = prefs(c).edit().putBoolean("contacts", on).apply()

    /** @return null when the message is allowed, otherwise a human-readable reason. */
    fun check(c: Context, addr: String, body: String): String? {
        val ms = ArrayList<Matcher>()
        for (r in Db.get(c).rules()) if (r.enabled) ms.add(Matcher(r))
        for (m in ms) if (!m.rule.black && m.matches(addr, body)) return null
        for (m in ms) if (m.rule.black && m.matches(addr, body)) return reason(c, m, addr, body)
        if (whitelistMode(c)) {
            if (allowContacts(c) && Contacts.name(c, addr) != null) return null
            return c.getString(R.string.r_not_whitelisted)
        }
        return null
    }

    /** "text matches "bonus"" / "sender matches "BANK"" for a rule that matched. */
    fun reason(c: Context, m: Matcher, addr: String, body: String): String {
        val b = m.body?.which(body)
        if (b != null) return c.getString(R.string.r_text, b)
        return c.getString(R.string.r_sender, m.sender?.which(addr) ?: "")
    }

    /** Converts a v3 rule (one field, '*' and /regex/ per entry) to the v4 model. */
    fun legacy(id: Long, black: Boolean, text: Boolean, pattern: String): Rule? {
        val entries = entries(pattern, !text, Mode.AUTO)
        if (entries.isEmpty()) return null
        val mode: Int
        val list = ArrayList<String>()
        var allRx = true
        var anyStar = false
        for (e in entries) {
            if (!isRegex(e)) allRx = false
            if ('*' in e) anyStar = true
        }
        when {
            allRx -> {
                mode = Mode.REGEX
                for (e in entries) list.add(e.substring(1, e.length - 1))
            }
            anyStar -> {
                mode = Mode.WILDCARD
                // Old text rules matched anywhere: keep that by wrapping in *...*.
                for (e in entries) list.add(if (!text) e else (if (e.startsWith("*")) e else "*$e") + (if (e.endsWith("*")) "" else "*"))
            }
            else -> {
                mode = if (text) Mode.CONTAINS else Mode.EQUALS
                list.addAll(entries)
            }
        }
        val joined = list.joinToString("\n")
        return if (text) Rule(id, black, true, Mode.EQUALS, "", mode, joined, false)
        else Rule(id, black, true, mode, joined, Mode.CONTAINS, "", false)
    }

    /** True for entries written as /regex/ (only meaningful in [Mode.AUTO]). */
    fun isRegex(pattern: String): Boolean {
        val t = pattern.trim()
        return t.length > 2 && t[0] == '/' && t[t.length - 1] == '/'
    }

    /** Case-insensitive, Unicode-aware flags. */
    const val CI = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /**
     * Splits a condition's text into entries: one per line; sender lists may also use commas,
     * except for regular expressions (a comma can be part of {2,3}). Blank and duplicate entries are dropped.
     */
    fun entries(text: String, sender: Boolean, mode: Int): List<String> {
        val out = ArrayList<String>()
        for (raw in cut(text, '\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!sender || mode == Mode.REGEX || (mode == Mode.AUTO && isRegex(line))) {
                if (line !in out) out.add(line)
            } else {
                for (part in cut(line, ',')) {
                    val t = part.trim()
                    if (t.isNotEmpty() && t !in out) out.add(t)
                }
            }
        }
        return out
    }

    /** Canonical stored form: entries joined by newlines. */
    fun normalize(text: String, sender: Boolean, mode: Int) = entries(text, sender, mode).joinToString("\n")

    /** Splits [s] on [sep] without Kotlin's sequence machinery (keeps the APK small). */
    private fun cut(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (true) {
            val i = s.indexOf(sep, start)
            if (i < 0) {
                out.add(s.substring(start))
                return out
            }
            out.add(s.substring(start, i))
            start = i + 1
        }
    }

    /** A rule compiled once, so it can be run against thousands of messages cheaply. */
    class Matcher(val rule: Rule) {
        val sender: Cond? = if (rule.senders.isBlank()) null else Cond(true, rule.sMode, rule.senders, rule.cs)
        val body: Cond? = if (rule.bodies.isBlank()) null else Cond(false, rule.bMode, rule.bodies, rule.cs)
        val empty = sender == null && body == null
        val invalid = sender?.invalid == true || body?.invalid == true
        val firstInvalid: String? = sender?.firstInvalid ?: body?.firstInvalid

        fun matches(addr: String, body: String): Boolean {
            if (empty) return false
            if (sender != null && sender.which(addr) == null) return false
            if (this.body != null && this.body.which(body) == null) return false
            return true
        }

        /** Where the text condition matches inside [body], for highlighting. */
        fun span(body: String): Span? = this.body?.span(body)
    }

    /** One condition: entries + mode + case sensitivity, against a sender or a message body. */
    class Cond(val isSender: Boolean, val mode: Int, text: String, val cs: Boolean) {
        private val parts: List<One>

        init {
            val list = ArrayList<One>()
            for (e in entries(text, isSender, mode)) list.add(One(isSender, mode, e, cs))
            parts = list
        }

        val invalid: Boolean
        val firstInvalid: String?

        init {
            var bad: String? = null
            for (p in parts) if (p.invalid) {
                bad = p.entry
                break
            }
            invalid = bad != null
            firstInvalid = bad
        }

        val size get() = parts.size

        /** The entry that matched [value], or null. */
        fun which(value: String): String? {
            for (p in parts) if (p.matches(value)) return p.entry
            return null
        }

        /** Earliest match of any entry inside [value]. */
        fun span(value: String): Span? {
            var best: Span? = null
            for (p in parts) {
                val s = p.span(value) ?: continue
                if (best == null || s.start < best.start) best = s
            }
            return best
        }
    }

    /** One compiled entry. */
    private class One(val isSender: Boolean, val mode: Int, val entry: String, val cs: Boolean) {
        private val ignore = !cs
        private val rx: Pattern?
        private val rx2: Pattern?
        val invalid: Boolean
        private val p: String
        private val dp: String
        private val phone: Boolean

        init {
            var a: Pattern? = null
            var b: Pattern? = null
            var bad = false
            val flags = if (cs) 0 else CI
            p = if (isSender && mode != Mode.REGEX && !(mode == Mode.AUTO && isRegex(entry))) norm(entry) else entry
            when {
                mode == Mode.REGEX -> try {
                    a = Pattern.compile(entry, flags)
                } catch (_: Exception) {
                    bad = true
                }
                mode == Mode.AUTO && isRegex(entry) -> try {
                    a = Pattern.compile(entry.substring(1, entry.length - 1), CI)
                } catch (_: Exception) {
                    bad = true
                }
                // A wildcard entry without * or ? is simply "equals" (keeps phone-number matching).
                (mode == Mode.WILDCARD && (p.indexOf('*') >= 0 || p.indexOf('?') >= 0)) ||
                    (mode == Mode.AUTO && p.indexOf('*') >= 0) -> {
                    a = glob(p, flags)
                    if (isSender) b = glob(p.trimStart('+'), flags)
                }
            }
            rx = a
            rx2 = b
            invalid = bad
            dp = digits(p)
            phone = isSender && dp.length >= 7 && dp.length == p.trimStart('+').length
        }

        fun matches(raw: String): Boolean {
            if (invalid || p.isEmpty()) return false
            if (mode == Mode.REGEX || (mode == Mode.AUTO && isRegex(entry))) return rx!!.matcher(raw).find()
            val v = if (isSender) norm(raw) else raw
            if (rx != null) {
                // AUTO text rules match anywhere; everything else is a whole-value match.
                if (mode == Mode.AUTO && !isSender) return rx.matcher(v).find()
                return rx.matcher(v).matches() || (rx2 != null && rx2.matcher(v.trimStart('+')).matches())
            }
            return when (mode) {
                Mode.CONTAINS -> v.contains(p, ignore)
                Mode.STARTS -> v.startsWith(p, ignore)
                Mode.ENDS -> v.endsWith(p, ignore)
                Mode.EQUALS, Mode.WILDCARD -> v.equals(p, ignore) || samePhone(v)
                else -> if (isSender) v.equals(p, true) || samePhone(v) else v.contains(p, true) // AUTO
            }
        }

        /** +359881234567 == 0881234567: compare the national part of two phone numbers. */
        private fun samePhone(v: String): Boolean {
            if (!phone) return false
            val da = digits(v)
            if (da.length < 7 || da.length != v.trimStart('+').length) return false
            val n = minOf(dp.length, da.length, 9)
            return dp.regionMatches(dp.length - n, da, da.length - n, n)
        }

        fun span(raw: String): Span? {
            if (isSender || !matches(raw)) return null
            if (rx != null) {
                val m = rx.matcher(raw)
                return if (m.find()) Span(m.start(), m.end()) else null
            }
            return when (mode) {
                Mode.STARTS -> Span(0, p.length)
                Mode.ENDS -> Span(raw.length - p.length, raw.length)
                Mode.EQUALS, Mode.WILDCARD -> Span(0, raw.length)
                else -> {
                    val i = raw.indexOf(p, 0, mode == Mode.AUTO || ignore)
                    if (i < 0) null else Span(i, i + p.length)
                }
            }
        }
    }

    /** Sender normalization: keep letters, digits and + * ? (drops spaces, dashes, brackets). */
    private fun norm(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) if (Character.isLetterOrDigit(ch) || ch == '+' || ch == '*' || ch == '?') b.append(ch)
        return b.toString()
    }

    private fun digits(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) if (ch in '0'..'9') b.append(ch)
        return b.toString()
    }

    /** Wildcard -> regex: '*' any text, '?' one character, everything else literal. */
    private fun glob(p: String, flags: Int): Pattern {
        val b = StringBuilder()
        val lit = StringBuilder()
        fun flush() {
            if (lit.isNotEmpty()) {
                b.append(Pattern.quote(lit.toString()))
                lit.setLength(0)
            }
        }
        for (ch in p) when (ch) {
            '*' -> { flush(); b.append(".*") }
            '?' -> { flush(); b.append('.') }
            else -> lit.append(ch)
        }
        flush()
        return Pattern.compile(b.toString(), flags or Pattern.DOTALL)
    }
}

/** A [start, end) character range. */
class Span(val start: Int, val end: Int)
