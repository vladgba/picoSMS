package x.vladgba.sms

import android.content.Context

/**
 * Labels group conversations like folders.
 * A conversation belongs to a label when its sender matches the label's sender entries and/or
 * any of its messages contains one of the label's words. With both lists filled, [Label.both]
 * decides between AND (must match both) and OR (either is enough).
 * Entries use the same syntax as filter rules: one per line, * wildcard, /regex/.
 */
object Labels {
    const val MAX = 62

    private fun prefs(c: Context) = c.getSharedPreferences("pico", Context.MODE_PRIVATE)
    fun selected(c: Context) = prefs(c).getLong("label", 0)
    fun select(c: Context, id: Long) = prefs(c).edit().putLong("label", id).apply()

    /** A label compiled for scanning. */
    class Compiled(val label: Label) {
        val sender: Rules.Cond? = if (label.senders.isBlank()) null else Rules.Cond(true, Mode.AUTO, label.senders, false)
        val words: Rules.Cond? = if (label.words.isBlank()) null else Rules.Cond(false, Mode.AUTO, label.words, false)
        val invalid = sender?.invalid == true || words?.invalid == true
        val firstInvalid: String? = sender?.firstInvalid ?: words?.firstInvalid

        fun decide(senderHit: Boolean, wordHit: Boolean): Boolean = when {
            sender != null && words != null -> if (label.both) senderHit && wordHit else senderHit || wordHit
            sender != null -> senderHit
            words != null -> wordHit
            else -> false
        }
    }

    fun compile(list: List<Label>): List<Compiled> {
        val out = ArrayList<Compiled>(minOf(list.size, MAX))
        for (i in 0 until minOf(list.size, MAX)) out.add(Compiled(list[i]))
        return out
    }

    /**
     * Fills [Conv.labels] (bit i = label i). [Sms.conversations] calls [row] for every message and
     * [finish] once per conversation.
     */
    class Scan(private val labels: List<Compiled>) {
        private val words = HashMap<Long, Long>()

        fun row(tid: Long, body: String) {
            if (labels.isEmpty()) return
            var got = words[tid] ?: 0L
            for (i in labels.indices) {
                val bit = 1L shl i
                if (got and bit != 0L) continue
                val w = labels[i].words ?: continue
                if (w.which(body) != null) got = got or bit
            }
            words[tid] = got
        }

        fun finish(cv: Conv) {
            var mask = 0L
            val w = words[cv.tid] ?: 0L
            for (i in labels.indices) {
                val l = labels[i]
                val s = l.sender?.which(cv.addr) != null
                if (l.decide(s, w and (1L shl i) != 0L)) mask = mask or (1L shl i)
            }
            cv.labels = mask
        }
    }
}
