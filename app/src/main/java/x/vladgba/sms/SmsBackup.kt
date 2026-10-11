package x.vladgba.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Backup and restore of all SMS (not MMS) to a file the user picks.
 * Formats: SMS Backup & Restore XML, or picoSMS JSON; optionally password-encrypted (then gzipped inside).
 */
object SmsBackup {
    class Result(val added: Int, val duplicates: Int, val mms: Int)

    private const val BATCH = 200

    /** Writes every SMS to [uri]. @return number of messages written. */
    fun export(c: Context, uri: Uri, json: Boolean, password: CharArray?, progress: (Int, Int) -> Unit): Int {
        val cr = c.contentResolver
        val cur = cr.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("address", "body", "date", "date_sent", "type", "read", "seen", "status", "locked", "sub_id", "protocol", "service_center", "subject"),
            null, null, "date ASC"
        ) ?: throw IllegalStateException("No access to messages")
        var n = 0
        cur.use {
            val total = it.count
            val buffer = if (password != null) ByteArrayOutputStream() else null
            val sink: OutputStream = if (buffer != null) GZIPOutputStream(buffer, 65536)
            else cr.openOutputStream(uri, "wt") ?: throw IllegalStateException("Cannot write file")
            val w = BufferedWriter(OutputStreamWriter(sink, Charsets.UTF_8), 65536)
            try {
                val out: MsgWriter = if (json) JsonOut(w) else XmlOut(w)
                out.begin(total)
                val m = BakMsg()
                while (it.moveToNext()) {
                    m.address = it.getString(0) ?: ""
                    m.body = it.getString(1) ?: ""
                    m.date = it.getLong(2)
                    m.dateSent = it.getLong(3)
                    m.type = it.getInt(4)
                    m.read = it.getInt(5)
                    m.seen = it.getInt(6)
                    m.status = if (it.isNull(7)) -1 else it.getInt(7)
                    m.locked = it.getInt(8)
                    m.subId = if (it.isNull(9)) -1 else it.getInt(9)
                    m.protocol = if (it.isNull(10)) 0 else it.getInt(10)
                    m.serviceCenter = it.getString(11)
                    m.subject = it.getString(12)
                    out.write(m, if (m.address.isEmpty()) null else Contacts.name(c, m.address))
                    n++
                    if (n % BATCH == 0) progress(n, total)
                }
                out.end()
            } finally {
                w.close()
            }
            if (buffer != null && password != null) {
                val enc = Crypt.encrypt(buffer.toByteArray(), password, Build.VERSION.SDK_INT >= 26)
                (cr.openOutputStream(uri, "wt") ?: throw IllegalStateException("Cannot write file")).use { o -> o.write(enc) }
            }
            progress(n, total)
        }
        return n
    }

    fun isEncrypted(c: Context, uri: Uri): Boolean {
        val head = ByteArray(4)
        val n = c.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: 0
        return Crypt.isEncrypted(head, n)
    }

    /** Opens a backup (decrypting / un-gzipping as needed) and picks the right format reader. */
    fun open(c: Context, uri: Uri, password: CharArray?): MsgReader {
        var input: InputStream = BufferedInputStream(
            c.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read file"), 65536
        )
        input.mark(8)
        val head = ByteArray(4)
        val got = input.read(head)
        input.reset()
        if (Crypt.isEncrypted(head, got)) {
            val pw = password ?: throw Crypt.WrongPassword()
            val data = input.use { it.readBytes() }
            input = GZIPInputStream(ByteArrayInputStream(Crypt.decrypt(data, pw)), 65536)
        } else if (got >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) {
            input = GZIPInputStream(input, 65536)
        }
        val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 65536)
        r.mark(4096)
        var ch = r.read()
        while (ch == 0xFEFF || (ch != -1 && ch <= ' '.code)) ch = r.read() // BOM / whitespace
        r.reset()
        return when (ch) {
            '<'.code -> XmlIn(r)
            '{'.code, '['.code -> JsonIn(r)
            else -> throw IllegalArgumentException("Unknown backup format")
        }
    }

    /** Inserts messages that are not already on the phone. Requires the default SMS role. */
    fun restore(c: Context, src: MsgReader, progress: (Int, Int) -> Unit): Result {
        val cr = c.contentResolver
        // Existing messages, keyed by time + type + text: restoring the same file twice adds nothing.
        val have = HashSet<String>()
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf("date", "type", "body"), null, null, null)?.use {
            while (it.moveToNext()) have.add(key(it.getLong(0), it.getInt(1), it.getString(2) ?: ""))
        }
        var added = 0
        var dup = 0
        var seen = 0
        val batch = ArrayList<ContentValues>(BATCH)
        fun flush() {
            if (batch.isEmpty()) return
            added += cr.bulkInsert(Telephony.Sms.CONTENT_URI, batch.toTypedArray())
            batch.clear()
        }
        while (true) {
            val m = src.next() ?: break
            seen++
            if (m.address.isBlank() && m.type == Sms.INBOX) continue
            // Never re-create "outbox"/"queued" rows: some phones would try to send them again.
            val type = when (m.type) {
                Sms.OUTBOX, Sms.QUEUED -> Sms.FAILED
                in 1..6 -> m.type
                else -> Sms.INBOX
            }
            if (!have.add(key(m.date, type, m.body))) {
                dup++
                continue
            }
            val v = ContentValues()
            v.put("address", m.address)
            v.put("body", m.body)
            v.put("date", m.date)
            v.put("date_sent", m.dateSent)
            v.put("type", type)
            v.put("read", m.read)
            v.put("seen", m.seen)
            v.put("status", m.status)
            v.put("locked", m.locked)
            v.put("protocol", m.protocol)
            if (m.subId >= 0) v.put("sub_id", m.subId)
            m.serviceCenter?.let { v.put("service_center", it) }
            m.subject?.let { v.put("subject", it) }
            batch.add(v)
            if (batch.size >= BATCH) {
                flush()
                progress(seen, src.declared)
            }
        }
        flush()
        progress(seen, src.declared)
        return Result(added, dup, src.mmsSkipped)
    }

    private fun key(date: Long, type: Int, body: String) = "$date|$type|${body.hashCode()}|${body.length}"
}
