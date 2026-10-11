package x.vladgba.sms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.provider.Telephony
import android.widget.Toast
import java.util.regex.Pattern

/** A value picked out of a message: a one-time code or a user regex match. */
class Pick(val label: String, val value: String, val otp: Boolean)

/** A compiled user picker. */
class PickRx(val name: String, val rx: Pattern)

/**
 * "Quick pick": pulls useful values out of message text so they can be copied with one tap.
 *  - Built-in OTP detection (keyword + 4–8 digit code, nearest to the keyword wins).
 *  - User regex pickers; the first capture group is picked when present, otherwise the whole match.
 */
object Picks {
    private fun prefs(c: Context) = c.getSharedPreferences("pico", Context.MODE_PRIVATE)
    fun otpEnabled(c: Context) = prefs(c).getBoolean("otp", true)
    fun setOtpEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("otp", on).apply()

    private const val MAX = 4

    private val KEYWORD = Pattern.compile(
        "code|otp|one[- ]?time|passcode|password|pin\\b|verif|confirm|auth|token|security|login|sign[- ]?in|" +
            "код|парол|пароль|потвърд|подтвер|kod|código|codigo|codice|kode|wachtwoord|mot de passe|" +
            "验证码|驗證碼|認証|인증",
        Rules.CI
    )

    // 4–8 digits, or 3+3 split by a space/dash ("123 456", "123-456"); not part of a longer number,
    // time (12:30), date (10.10.2026), decimal amount (24.90) or phone number.
    private val CODE = Pattern.compile("(?<![\\d.,:/+])(?<!\\d-)(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\d]|[.,:/]\\d)")

    private val CURRENCY = arrayOf("$", "€", "£", "лв", "lv", "bgn", "usd", "eur", "rub", "руб", "uah", "грн", "pln", "zł", "%")

    fun otp(body: String): String? {
        val km = KEYWORD.matcher(body)
        val keys = ArrayList<Int>()
        while (km.find()) keys.add(km.start())
        if (keys.isEmpty()) return null
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        val m = CODE.matcher(body)
        while (m.find()) {
            val s = m.start()
            val e = m.end()
            val before = body.substring(maxOf(0, s - 2), s).trimEnd().lowercase()
            val after = body.substring(e, minOf(body.length, e + 4)).trimStart().lowercase()
            var money = false
            for (cur in CURRENCY) if (after.startsWith(cur) || before.endsWith(cur)) money = true
            if (money) continue
            var d = Int.MAX_VALUE
            for (k in keys) d = minOf(d, if (k > s) k - s else s - k)
            if (d < bestDist) {
                bestDist = d
                best = digitsOf(m.group())
            }
        }
        return best
    }

    private fun digitsOf(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) if (ch in '0'..'9') b.append(ch)
        return b.toString()
    }

    fun compiled(c: Context): List<PickRx> {
        val out = ArrayList<PickRx>()
        for (p in Db.get(c).pickers()) if (p.enabled) compile(p.regex)?.let { out.add(PickRx(p.name, it)) }
        return out
    }

    fun compile(regex: String): Pattern? = try {
        if (regex.isBlank()) null else Pattern.compile(regex, Rules.CI)
    } catch (_: Exception) {
        null
    }

    /** First capture group if the regex has one (and it matched), else the whole match. */
    private fun useGroup(m: java.util.regex.Matcher) = m.groupCount() > 0 && m.start(1) >= 0

    fun valueOf(m: java.util.regex.Matcher): String = if (useGroup(m)) m.group(1)!! else m.group()

    fun extract(body: String, otp: Boolean, pickers: List<PickRx>): List<Pick> {
        val out = ArrayList<Pick>()
        val seen = HashSet<String>()
        if (otp) otp(body)?.let { out += Pick("OTP", it, true); seen += it }
        for (p in pickers) {
            val m = p.rx.matcher(body)
            while (m.find()) {
                if (m.end() == m.start()) continue
                val v = valueOf(m).trim()
                if (v.isEmpty() || !seen.add(v)) continue
                out += Pick(p.name, v, false)
                if (out.size >= MAX) return out
            }
        }
        return out
    }

    fun forMessage(c: Context, body: String): Pick? =
        extract(body, otpEnabled(c), compiled(c)).firstOrNull()

    fun copy(c: Context, value: String, sensitive: Boolean) {
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("picoSMS", value)
        if (sensitive) {
            // Hide the code in the Android 13+ clipboard preview.
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(c, c.getString(R.string.copied_value, value), Toast.LENGTH_SHORT).show()
    }

    class Example(val addr: String, val body: String, val date: Long, val span: Span, val value: String)
    class PickPreview(val scanned: Int, val total: Int, val examples: List<Example>)

    /** Runs [rx] over the latest inbox messages (and an optional sample text first). */
    fun preview(c: Context, rx: Pattern, g: Int, sample: String?, limit: Int = 5, scanMax: Int = 2000): PickPreview? {
        val ex = ArrayList<Example>()
        var total = 0
        var scanned = 0
        fun test(addr: String, body: String, date: Long): Boolean {
            scanned++
            val m = rx.matcher(body)
            if (!m.find()) return Apply.gen == g
            total++
            if (ex.size < limit) {
                val r = if (useGroup(m)) Span(m.start(1), m.end(1)) else Span(m.start(), m.end())
                ex += Example(addr, body, date, r, valueOf(m))
            }
            return Apply.gen == g
        }
        if (sample != null && !test("", sample, System.currentTimeMillis())) return null
        try {
            c.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date"), "type=${Sms.INBOX}", null, "date DESC"
            )?.use {
                while (it.moveToNext() && scanned < scanMax) {
                    if (!test(it.getString(0) ?: "", it.getString(1) ?: "", it.getLong(2))) return null
                }
            }
        } catch (_: Exception) {
        }
        return PickPreview(scanned, total, ex)
    }
}
