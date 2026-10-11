package x.vladgba.sms

import java.io.Reader
import java.io.Writer
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * SMS backup file formats. Pure Java/Kotlin (no Android classes), streaming in both directions,
 * so huge backups never have to fit in memory and everything here is unit-testable on the JVM.
 */

/** One SMS as stored in a backup file (columns of content://sms). */
class BakMsg {
    var address = ""
    var body = ""
    var date = 0L
    var dateSent = 0L
    var type = 1
    var read = 1
    var seen = 1
    var status = -1
    var locked = 0
    var subId = -1
    var protocol = 0
    var serviceCenter: String? = null
    var subject: String? = null
}

interface MsgWriter {
    fun begin(count: Int)
    fun write(m: BakMsg, contact: String?)
    fun end()
}

interface MsgReader {
    /** Message count declared in the file header, or -1 if unknown. */
    val declared: Int

    /** MMS entries skipped so far (XML only). */
    val mmsSkipped: Int

    fun next(): BakMsg?
}

// ---------------------------------------------------------------- XML (SMS Backup & Restore)

/** Writes the "SMS Backup & Restore" XML format: <smses count="N"><sms .../></smses>. */
class XmlOut(private val w: Writer) : MsgWriter {
    private val fmt = SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US)

    override fun begin(count: Int) {
        w.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        w.write("<!--Exported by picoSMS. Compatible with SMS Backup & Restore.-->\n")
        w.write("<smses count=\"$count\" backup_set=\"picoSMS\" backup_date=\"${System.currentTimeMillis()}\" type=\"full\">\n")
    }

    override fun write(m: BakMsg, contact: String?) {
        w.write("  <sms protocol=\"${m.protocol}\" address=\"")
        esc(m.address)
        w.write("\" date=\"${m.date}\" type=\"${m.type}\" subject=\"")
        esc(m.subject ?: "null")
        w.write("\" body=\"")
        esc(m.body)
        w.write("\" toa=\"null\" sc_toa=\"null\" service_center=\"")
        esc(m.serviceCenter ?: "null")
        w.write("\" read=\"${m.read}\" status=\"${m.status}\" locked=\"${m.locked}\" date_sent=\"${m.dateSent}\"")
        if (m.subId >= 0) w.write(" sub_id=\"${m.subId}\"")
        w.write(" readable_date=\"")
        esc(fmt.format(Date(m.date)))
        w.write("\" contact_name=\"")
        esc(contact ?: "(Unknown)")
        w.write("\" />\n")
    }

    override fun end() {
        w.write("</smses>\n")
        w.flush()
    }

    private fun esc(s: String) {
        for (ch in s) when {
            ch == '&' -> w.write("&amp;")
            ch == '<' -> w.write("&lt;")
            ch == '>' -> w.write("&gt;")
            ch == '"' -> w.write("&quot;")
            ch == '\'' -> w.write("&apos;")
            ch == '\n' -> w.write("&#10;")
            ch == '\r' -> w.write("&#13;")
            ch == '\t' -> w.write("&#9;")
            ch < ' ' -> {} // not representable in XML 1.0
            else -> w.write(ch.code)
        }
    }
}

/**
 * Lenient streaming reader for SMS Backup & Restore XML. Reads <sms> elements, counts and skips
 * <mms> ones, and tolerates what that app writes (e.g. emoji as separate surrogate references
 * &#55357;&#56832;, '>' inside attribute values).
 */
class XmlIn(private val r: Reader) : MsgReader {
    override var declared = -1
        private set
    override var mmsSkipped = 0
        private set

    private val buf = CharArray(16384)
    private var len = 0
    private var pos = 0

    private fun read(): Int {
        if (pos >= len) {
            len = r.read(buf, 0, buf.size)
            pos = 0
            if (len <= 0) return -1
        }
        return buf[pos++].code
    }

    override fun next(): BakMsg? {
        while (true) {
            var c = read()
            while (c != -1 && c != '<'.code) c = read()
            if (c == -1) return null
            c = read()
            when (c) {
                -1 -> return null
                '!'.code -> skipSpecial()
                '?'.code, '/'.code -> skipTag()
                else -> {
                    val name = StringBuilder()
                    name.append(c.toChar())
                    while (true) {
                        c = read()
                        if (c == -1 || c == '>'.code || c == '/'.code || c <= ' '.code) break
                        name.append(c.toChar())
                    }
                    if (c == '>'.code) {
                        if (name.toString() == "mms") mmsSkipped++
                        continue
                    }
                    when (name.toString()) {
                        "sms" -> return toMsg(attrs(c))
                        "smses" -> declared = attrs(c)["count"]?.toIntOrNull() ?: -1
                        "mms" -> { mmsSkipped++; skipTag() }
                        else -> skipTag()
                    }
                }
            }
        }
    }

    /** Skips the rest of a tag, honouring quotes (attribute values may contain '>'). */
    private fun skipTag() {
        var q = 0
        while (true) {
            val c = read()
            if (c == -1) return
            if (q != 0) {
                if (c == q) q = 0
            } else if (c == '"'.code || c == '\''.code) q = c
            else if (c == '>'.code) return
        }
    }

    /** <!-- comment -->, <!DOCTYPE ...>, <![CDATA[...]]> */
    private fun skipSpecial() {
        var a = read()
        if (a == '-'.code) {
            var p1 = 0
            var p2 = 0
            while (true) {
                a = read()
                if (a == -1 || (a == '>'.code && p1 == '-'.code && p2 == '-'.code)) return
                p2 = p1
                p1 = a
            }
        }
        if (a != '>'.code) skipTag()
    }

    private fun attrs(first: Int): HashMap<String, String> {
        val out = HashMap<String, String>()
        var c = first
        while (true) {
            while (c != -1 && c <= ' '.code) c = read()
            if (c == -1 || c == '>'.code) return out
            if (c == '/'.code) {
                c = read()
                continue
            }
            val name = StringBuilder()
            while (c != -1 && c != '='.code && c > ' '.code && c != '>'.code && c != '/'.code) {
                name.append(c.toChar())
                c = read()
            }
            while (c != -1 && c <= ' '.code) c = read()
            if (c != '='.code) continue // attribute without value
            c = read()
            while (c != -1 && c <= ' '.code) c = read()
            if (c != '"'.code && c != '\''.code) continue
            val q = c
            val v = StringBuilder()
            while (true) {
                c = read()
                if (c == -1 || c == q) break
                if (c == '&'.code) entity(v) else v.append(c.toChar())
            }
            out[name.toString()] = v.toString()
            c = read()
        }
    }

    private fun entity(v: StringBuilder) {
        val e = StringBuilder()
        while (e.length < 12) {
            val c = read()
            if (c == -1) break
            if (c == ';'.code) {
                val s = e.toString()
                when {
                    s == "amp" -> v.append('&')
                    s == "lt" -> v.append('<')
                    s == "gt" -> v.append('>')
                    s == "quot" -> v.append('"')
                    s == "apos" -> v.append('\'')
                    s.startsWith("#x") || s.startsWith("#X") -> code(v, s.substring(2).toIntOrNull(16), s)
                    s.startsWith("#") -> code(v, s.substring(1).toIntOrNull(), s)
                    else -> v.append('&').append(s).append(';')
                }
                return
            }
            e.append(c.toChar())
        }
        v.append('&').append(e) // not an entity after all
    }

    private fun code(v: StringBuilder, cp: Int?, raw: String) {
        when {
            cp == null || cp < 0 || cp > 0x10FFFF -> v.append('&').append(raw).append(';')
            cp <= 0xFFFF -> v.append(cp.toChar()) // includes lone surrogates, which pair up naturally
            else -> v.appendCodePoint(cp)
        }
    }

    private fun toMsg(a: Map<String, String>): BakMsg {
        val m = BakMsg()
        m.address = a["address"]?.let { if (it == "null") "" else it } ?: ""
        m.body = a["body"]?.let { if (it == "null") "" else it } ?: ""
        m.date = a["date"]?.toLongOrNull() ?: 0L
        m.dateSent = a["date_sent"]?.toLongOrNull() ?: 0L
        m.type = a["type"]?.toIntOrNull() ?: 1
        m.read = a["read"]?.toIntOrNull() ?: 1
        m.seen = m.read
        m.status = a["status"]?.toIntOrNull() ?: -1
        m.locked = a["locked"]?.toIntOrNull() ?: 0
        m.subId = a["sub_id"]?.toIntOrNull() ?: -1
        m.protocol = a["protocol"]?.toIntOrNull() ?: 0
        m.serviceCenter = a["service_center"]?.takeIf { it != "null" && it.isNotEmpty() }
        m.subject = a["subject"]?.takeIf { it != "null" && it.isNotEmpty() }
        return m
    }
}

// ---------------------------------------------------------------- JSON (picoSMS)

/** {"app":"picoSMS","type":"sms","format":1,"count":N,"messages":[{...},...]} */
class JsonOut(private val w: Writer) : MsgWriter {
    private var first = true

    override fun begin(count: Int) {
        w.write("{\n  \"app\": \"picoSMS\",\n  \"type\": \"sms\",\n  \"format\": 1,\n")
        w.write("  \"exported\": ${System.currentTimeMillis()},\n  \"count\": $count,\n  \"messages\": [")
    }

    override fun write(m: BakMsg, contact: String?) {
        w.write(if (first) "\n    {" else ",\n    {")
        first = false
        str("address", m.address)
        w.write(", ")
        str("body", m.body)
        w.write(", \"date\": ${m.date}, \"dateSent\": ${m.dateSent}, \"type\": ${m.type}")
        w.write(", \"read\": ${m.read != 0}, \"seen\": ${m.seen != 0}, \"status\": ${m.status}, \"locked\": ${m.locked != 0}")
        if (m.subId >= 0) w.write(", \"subId\": ${m.subId}")
        if (m.protocol != 0) w.write(", \"protocol\": ${m.protocol}")
        m.serviceCenter?.let { w.write(", "); str("serviceCenter", it) }
        m.subject?.let { w.write(", "); str("subject", it) }
        if (contact != null) {
            w.write(", ")
            str("contact", contact)
        }
        w.write("}")
    }

    override fun end() {
        w.write("\n  ]\n}\n")
        w.flush()
    }

    private fun str(k: String, v: String) {
        w.write("\"")
        w.write(k)
        w.write("\": \"")
        for (ch in v) when {
            ch == '"' -> w.write("\\\"")
            ch == '\\' -> w.write("\\\\")
            ch == '\n' -> w.write("\\n")
            ch == '\r' -> w.write("\\r")
            ch == '\t' -> w.write("\\t")
            ch < ' ' -> w.write("\\u%04x".format(ch.code))
            else -> w.write(ch.code)
        }
        w.write("\"")
    }
}

/**
 * Streaming reader for the picoSMS JSON format (also accepts a bare top-level array of messages).
 * A small tokenizer; unknown keys and nested values are skipped.
 */
class JsonIn(private val r: Reader) : MsgReader {
    override var declared = -1
        private set
    override val mmsSkipped = 0

    private val buf = CharArray(16384)
    private var len = 0
    private var pos = 0
    private var peeked = -2
    private var state = 0 // 0 = before messages, 1 = inside messages array, 2 = done

    private fun raw(): Int {
        if (pos >= len) {
            len = r.read(buf, 0, buf.size)
            pos = 0
            if (len <= 0) return -1
        }
        return buf[pos++].code
    }

    private fun read(): Int {
        if (peeked != -2) {
            val p = peeked
            peeked = -2
            return p
        }
        return raw()
    }

    /** Next non-whitespace character, without consuming it. */
    private fun peek(): Int {
        if (peeked == -2) peeked = raw()
        while (peeked != -1 && peeked <= ' '.code) peeked = raw()
        return peeked
    }

    private fun expect(ch: Char) {
        val c = peek()
        if (c != ch.code) throw IllegalArgumentException("Expected '$ch' but found '${if (c < 0) "EOF" else c.toChar()}'")
        read()
    }

    override fun next(): BakMsg? {
        if (state == 0) {
            when (peek()) {
                '['.code -> { read(); state = 1 }
                '{'.code -> {
                    read()
                    while (state == 0) {
                        if (peek() == '}'.code) { read(); state = 2; break }
                        if (peek() == ','.code) { read(); continue }
                        val key = string()
                        expect(':')
                        when (key) {
                            "messages" -> { expect('['); state = 1 }
                            "count" -> declared = (value() as? Long)?.toInt() ?: -1
                            else -> value()
                        }
                    }
                }
                else -> throw IllegalArgumentException("Not a JSON backup")
            }
        }
        if (state != 1) return null
        if (peek() == ','.code) read()
        if (peek() == ']'.code) {
            read()
            state = 2
            return null
        }
        expect('{')
        val m = BakMsg()
        while (true) {
            if (peek() == '}'.code) { read(); break }
            if (peek() == ','.code) { read(); continue }
            val k = string()
            expect(':')
            val v = value()
            when (k) {
                "address" -> m.address = (v as? String) ?: ""
                "body" -> m.body = (v as? String) ?: ""
                "date" -> m.date = (v as? Long) ?: 0L
                "dateSent" -> m.dateSent = (v as? Long) ?: 0L
                "type" -> m.type = ((v as? Long) ?: 1L).toInt()
                "read" -> m.read = flag(v, 1)
                "seen" -> m.seen = flag(v, 1)
                "status" -> m.status = ((v as? Long) ?: -1L).toInt()
                "locked" -> m.locked = flag(v, 0)
                "subId" -> m.subId = ((v as? Long) ?: -1L).toInt()
                "protocol" -> m.protocol = ((v as? Long) ?: 0L).toInt()
                "serviceCenter" -> m.serviceCenter = v as? String
                "subject" -> m.subject = v as? String
            }
        }
        return m
    }

    private fun flag(v: Any?, def: Int): Int = when (v) {
        is Boolean -> if (v) 1 else 0
        is Long -> if (v != 0L) 1 else 0
        else -> def
    }

    /** String, Long, Double, Boolean or null; arrays and objects are skipped (returned as null). */
    private fun value(): Any? {
        val c = peek()
        return when {
            c == '"'.code -> string()
            c == '{'.code || c == '['.code -> { skipNested(); null }
            c == 't'.code -> { word(); true }
            c == 'f'.code -> { word(); false }
            c == 'n'.code -> { word(); null }
            else -> {
                val w = word()
                w.toLongOrNull() ?: w.toDoubleOrNull()
            }
        }
    }

    private fun word(): String {
        val b = StringBuilder()
        peek()
        while (true) {
            val c = read()
            if (c == -1) break
            if (c == ','.code || c == '}'.code || c == ']'.code || c <= ' '.code || c == ':'.code) {
                peeked = c
                break
            }
            b.append(c.toChar())
        }
        return b.toString()
    }

    private fun string(): String {
        expect('"')
        val b = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1) throw IllegalArgumentException("Unterminated string")
            if (c == '"'.code) return b.toString()
            if (c != '\\'.code) {
                b.append(c.toChar())
                continue
            }
            when (val e = read()) {
                'n'.code -> b.append('\n')
                'r'.code -> b.append('\r')
                't'.code -> b.append('\t')
                'b'.code -> b.append('\b')
                'f'.code -> b.append('\u000C')
                'u'.code -> {
                    var cp = 0
                    for (i in 0 until 4) cp = cp * 16 + Character.digit(read(), 16)
                    b.append(cp.toChar())
                }
                else -> b.append(e.toChar())
            }
        }
    }

    private fun skipNested() {
        var depth = 0
        var inStr = false
        while (true) {
            val c = read()
            if (c == -1) return
            if (inStr) {
                if (c == '\\'.code) read() else if (c == '"'.code) inStr = false
                continue
            }
            when (c) {
                '"'.code -> inStr = true
                '{'.code, '['.code -> depth++
                '}'.code, ']'.code -> { depth--; if (depth == 0) return }
            }
        }
    }
}

// ---------------------------------------------------------------- Encryption

/**
 * Password encryption for backups: PBKDF2 key derivation + AES-256-GCM (authenticated, so a wrong
 * password or a tampered file is detected). File layout:
 *   "PSE1" | kdf(1: 1 = PBKDF2-HMAC-SHA1, 2 = PBKDF2-HMAC-SHA256) | iterations(4, big-endian) | salt(16) | iv(12) | ciphertext+tag
 * The header is authenticated as associated data.
 */
object Crypt {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private const val HEADER = 4 + 1 + 4 + 16 + 12

    class WrongPassword : Exception()

    fun isEncrypted(head: ByteArray, n: Int): Boolean {
        if (n < 4) return false
        for (i in 0 until 4) if (head[i] != MAGIC[i]) return false
        return true
    }

    /** [sha256] needs Android 8+ to decrypt; SHA1 is the fallback for Android 7. */
    fun encrypt(plain: ByteArray, pw: CharArray, sha256: Boolean): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val kdf = if (sha256) 2 else 1
        val iter = if (sha256) 310_000 else 720_000
        val header = ByteArray(HEADER)
        System.arraycopy(MAGIC, 0, header, 0, 4)
        header[4] = kdf.toByte()
        header[5] = (iter ushr 24).toByte()
        header[6] = (iter ushr 16).toByte()
        header[7] = (iter ushr 8).toByte()
        header[8] = iter.toByte()
        System.arraycopy(salt, 0, header, 9, 16)
        System.arraycopy(iv, 0, header, 25, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pw, salt, iter, kdf), GCMParameterSpec(128, iv))
        c.updateAAD(header)
        val ct = c.doFinal(plain)
        val out = ByteArray(HEADER + ct.size)
        System.arraycopy(header, 0, out, 0, HEADER)
        System.arraycopy(ct, 0, out, HEADER, ct.size)
        return out
    }

    /** @throws WrongPassword when the password is wrong or the file was modified. */
    fun decrypt(data: ByteArray, pw: CharArray): ByteArray {
        if (data.size < HEADER + 16 || !isEncrypted(data, data.size)) throw IllegalArgumentException("Not encrypted")
        val kdf = data[4].toInt()
        val iter = ((data[5].toInt() and 0xFF) shl 24) or ((data[6].toInt() and 0xFF) shl 16) or
            ((data[7].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
        if (iter < 1000 || iter > 10_000_000) throw IllegalArgumentException("Bad header")
        val salt = data.copyOfRange(9, 25)
        val iv = data.copyOfRange(25, 37)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pw, salt, iter, kdf), GCMParameterSpec(128, iv))
        c.updateAAD(data, 0, HEADER)
        try {
            return c.doFinal(data, HEADER, data.size - HEADER)
        } catch (_: javax.crypto.AEADBadTagException) {
            throw WrongPassword()
        }
    }

    private fun key(pw: CharArray, salt: ByteArray, iter: Int, kdf: Int): SecretKeySpec {
        val alg = if (kdf == 2) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val spec = PBEKeySpec(pw, salt, iter, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(alg).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
