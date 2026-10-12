package x.vladgba.sms

import android.content.Context
import android.provider.Telephony

/** A message a rule would affect: an inbox SMS (blacklist) or a quarantined one (whitelist). */
class Hit(val id: Long, val addr: String, val body: String, val date: Long, val sub: Int)

class Preview(val total: Int, val examples: List<Hit>)

/** Runs a rule against messages that already exist: preview the matches, then apply. */
object Apply {
    @Volatile
    var gen = 0
        private set

    /** Starts a new preview generation; older previews still running stop early. */
    fun newGen() = ++gen

    /** @return null if a newer preview superseded this one. */
    fun preview(c: Context, r: Rule, g: Int, limit: Int = 5): Preview? {
        val hits = ArrayList<Hit>()
        var n = 0
        val done = scan(c, r) { h ->
            n++
            if (hits.size < limit) hits += h
            g == gen
        }
        return if (done) Preview(n, hits) else null
    }

    /**
     * Blacklist: moves matching inbox SMS into the quarantine (needs default SMS app, else -1).
     * Whitelist: restores matching quarantined SMS into the inbox.
     * @return number of messages moved.
     */
    fun apply(c: Context, r: Rule): Int {
        val hits = ArrayList<Hit>()
        scan(c, r) { hits += it; true }
        var n = 0
        if (r.black) {
            if (hits.isNotEmpty() && !Sms.isDefault(c)) return -1
            val db = Db.get(c)
            val m = Rules.Matcher(r)
            for (h in hits) {
                val reason = Rules.reason(c, m, h.addr, h.body)
                // Keep an intact copy before touching the system provider.
                val blockedId = db.addBlocked(h.addr, h.body, h.date, h.sub, reason)
                if (Sms.deleteMsg(c, h.id) > 0) n++
                else db.removeBlocked(blockedId)
            }
        } else {
            for (h in hits) if (restore(c, Blocked(h.id, h.addr, h.body, h.date, h.sub, ""))) n++
        }
        if (n > 0) Widget.update(c)
        return n
    }

    fun restore(c: Context, b: Blocked): Boolean {
        if (Sms.insertIncoming(c, b.addr, b.body, b.date, b.sub, read = true, date = b.date) == null) return false
        Db.get(c).removeBlocked(b.id)
        return true
    }

    /** Calls [f] for every match, newest first; stops when [f] returns false. @return false if stopped. */
    private fun scan(c: Context, r: Rule, f: (Hit) -> Boolean): Boolean {
        val m = Rules.Matcher(r)
        if (!r.black) {
            for (b in Db.get(c).blocked()) if (m.matches(b.addr, b.body)) {
                if (!f(Hit(b.id, b.addr, b.body, b.date, b.sub))) return false
            }
            return true
        }
        val cr = c.contentResolver
        val uri = Telephony.Sms.CONTENT_URI
        val sel = "type=${Sms.INBOX}"
        val cur = try {
            cr.query(uri, arrayOf("_id", "address", "body", "date", "sub_id"), sel, null, "date DESC")
        } catch (_: Exception) {
            try {
                cr.query(uri, arrayOf("_id", "address", "body", "date"), sel, null, "date DESC")
            } catch (_: Exception) {
                null
            }
        } ?: return true
        cur.use {
            val hasSub = it.columnCount > 4
            while (it.moveToNext()) {
                val addr = it.getString(1) ?: continue
                val body = it.getString(2) ?: ""
                if (!m.matches(addr, body)) continue
                val sub = if (hasSub && !it.isNull(4)) it.getInt(4) else -1
                if (!f(Hit(it.getLong(0), addr, body, it.getLong(3), sub))) return false
            }
        }
        return true
    }
}
