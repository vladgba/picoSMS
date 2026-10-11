package x.vladgba.sms

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

class Conv(val tid: Long, val addr: String, val snippet: String, val date: Long, val type: Int) {
    var unread = 0
    var name: String = addr
    /** First quick-picked value of the latest message (OTP or a user picker). */
    var pick: Pick? = null
    /** Bit i set = belongs to label i. */
    var labels = 0L
}

private val NO_PICKS = ArrayList<Pick>(0)

class Msg(val id: Long, val body: String, val date: Long, val type: Int, val read: Boolean) {
    var picks: List<Pick> = NO_PICKS
}

/** Thin wrapper around the system SMS provider (content://sms). */
object Sms {
    const val INBOX = 1
    const val SENT = 2
    const val DRAFT = 3
    const val OUTBOX = 4
    const val FAILED = 5
    const val QUEUED = 6

    private val URI: Uri = Telephony.Sms.CONTENT_URI

    fun isDefault(c: Context) = Telephony.Sms.getDefaultSmsPackage(c) == c.packageName

    fun conversations(c: Context, labels: List<Labels.Compiled> = ArrayList(0)): List<Conv> {
        val scan = Labels.Scan(labels)
        val map = LinkedHashMap<Long, Conv>()
        try {
            c.contentResolver.query(
                URI, arrayOf("thread_id", "address", "body", "date", "type", "read"),
                null, null, "date DESC"
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val tid = cur.getLong(0)
                    var cv = map[tid]
                    if (cv == null) {
                        val a = cur.getString(1)
                        if (a.isNullOrEmpty()) continue
                        cv = Conv(tid, a, cur.getString(2) ?: "", cur.getLong(3), cur.getInt(4))
                        map[tid] = cv
                    }
                    if (cur.getInt(5) == 0 && cur.getInt(4) == INBOX) cv.unread++
                    scan.row(tid, cur.getString(2) ?: "")
                }
            }
        } catch (_: Exception) {
        }
        val out = ArrayList(map.values)
        if (labels.isNotEmpty()) for (cv in out) scan.finish(cv)
        return out
    }

    fun messages(c: Context, tid: Long): List<Msg> {
        val out = ArrayList<Msg>()
        val proj = arrayOf("_id", "body", "date", "type", "read")
        val sel = "thread_id=?"
        val args = arrayOf(tid.toString())
        try {
            val cur = try {
                c.contentResolver.query(URI, proj, sel, args, "date DESC LIMIT 1000")
            } catch (_: Exception) {
                c.contentResolver.query(URI, proj, sel, args, "date DESC")
            }
            cur?.use {
                while (it.moveToNext() && out.size < 1000) {
                    out.add(Msg(it.getLong(0), it.getString(1) ?: "", it.getLong(2), it.getInt(3), it.getInt(4) != 0))
                }
            }
        } catch (_: Exception) {
        }
        out.reverse()
        return out
    }

    /** Latest address of a thread (used when a thread is opened without one). */
    fun addressOf(c: Context, tid: Long): String? {
        try {
            c.contentResolver.query(URI, arrayOf("address"), "thread_id=?", arrayOf(tid.toString()), "date DESC")
                ?.use { if (it.moveToFirst()) return it.getString(0) }
        } catch (_: Exception) {
        }
        return null
    }

    fun threadOf(c: Context, uri: Uri): Long {
        try {
            c.contentResolver.query(uri, arrayOf("thread_id"), null, null, null)
                ?.use { if (it.moveToFirst()) return it.getLong(0) }
        } catch (_: Exception) {
        }
        return -1
    }

    fun threadFor(c: Context, addr: String): Long = try {
        Telephony.Threads.getOrCreateThreadId(c, addr)
    } catch (_: Exception) {
        -1
    }

    fun unread(c: Context): Int {
        try {
            c.contentResolver.query(URI, arrayOf("_id"), "read=0 AND type=$INBOX", null, null)
                ?.use { return it.count }
        } catch (_: Exception) {
        }
        return 0
    }

    fun insertIncoming(c: Context, addr: String, body: String, sent: Long, sub: Int, read: Boolean = false): Uri? {
        val v = ContentValues()
        v.put("address", addr)
        v.put("body", body)
        v.put("date", System.currentTimeMillis())
        v.put("date_sent", sent)
        v.put("read", if (read) 1 else 0)
        v.put("seen", if (read) 1 else 0)
        v.put("type", INBOX)
        if (sub >= 0) v.put("sub_id", sub)
        return try {
            c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, v)
        } catch (_: Exception) {
            null
        }
    }

    fun insertOutgoing(c: Context, addr: String, body: String): Uri? {
        val v = ContentValues()
        v.put("address", addr)
        v.put("body", body)
        v.put("date", System.currentTimeMillis())
        v.put("read", 1)
        v.put("seen", 1)
        v.put("type", OUTBOX)
        return try {
            c.contentResolver.insert(URI, v)
        } catch (_: Exception) {
            null
        }
    }

    fun setType(c: Context, uri: Uri, type: Int, onlyIfNot: Int = -1) {
        val v = ContentValues()
        v.put("type", type)
        try {
            if (onlyIfNot >= 0) c.contentResolver.update(uri, v, "type<>$onlyIfNot", null)
            else c.contentResolver.update(uri, v, null, null)
        } catch (_: Exception) {
        }
    }

    fun markRead(c: Context, tid: Long) {
        val v = ContentValues()
        v.put("read", 1)
        v.put("seen", 1)
        try {
            c.contentResolver.update(URI, v, "thread_id=? AND read=0", arrayOf(tid.toString()))
        } catch (_: Exception) {
        }
    }

    fun deleteThread(c: Context, tid: Long) {
        try {
            c.contentResolver.delete(URI, "thread_id=?", arrayOf(tid.toString()))
        } catch (_: Exception) {
        }
    }

    fun deleteMsg(c: Context, id: Long): Int = try {
        c.contentResolver.delete(ContentUris.withAppendedId(URI, id), null, null)
    } catch (_: Exception) {
        0
    }
}
