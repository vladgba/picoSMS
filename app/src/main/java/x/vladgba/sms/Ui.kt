package x.vladgba.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.view.WindowInsets
import android.widget.TextView
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

/** Tiny helpers shared by all screens: background work, edge-to-edge insets, formatting. */
object Ui {
    val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()

    /** Runs [work] off the main thread and delivers its result to [done] on the main thread. */
    fun <T> load(work: () -> T, done: (T) -> Unit) {
        exec.execute {
            val r = work()
            main.post { done(r) }
        }
    }

    fun bg(work: () -> Unit) = exec.execute(work)

    /** Draw behind system bars and pad [root] by bar + keyboard insets. */
    @Suppress("DEPRECATION")
    fun edge(a: Activity, root: View) {
        val w = a.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            val d = w.decorView
            d.systemUiVisibility = d.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                v.setPadding(
                    ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                    ins.systemWindowInsetRight, ins.systemWindowInsetBottom
                )
            }
            ins
        }
    }

    /** Asks the system to make picoSMS the default SMS app. */
    @Suppress("DEPRECATION")
    fun requestDefault(a: Activity) {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = a.getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS)) {
                a.startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS), 1)
                return
            }
        }
        try {
            a.startActivity(
                android.content.Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    .putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, a.packageName)
            )
        } catch (_: Exception) {
        }
    }

    fun time(c: Context, t: Long): String {
        if (DateUtils.isToday(t)) return DateFormat.getTimeFormat(c).format(Date(t))
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = t }
        var f = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) f = f or DateUtils.FORMAT_NO_YEAR
        return DateUtils.formatDateTime(c, t, f)
    }

    fun fullTime(c: Context, t: Long): String =
        DateUtils.formatDateTime(
            c, t,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )

    private val PALETTE = intArrayOf(
        0xFF0F9D8A.toInt(), 0xFF3F7DD8.toInt(), 0xFF8E5BD6.toInt(), 0xFFD9653B.toInt(),
        0xFFC2416E.toInt(), 0xFF3C9A4E.toInt(), 0xFFB08A1E.toInt(), 0xFF5A6B7D.toInt()
    )

    fun avatar(tv: TextView, name: String, key: String) {
        val bg = (tv.background as? GradientDrawable) ?: GradientDrawable().also {
            it.shape = GradientDrawable.OVAL
            tv.background = it
        }
        bg.setColor(PALETTE[(key.hashCode() and 0x7fffffff) % PALETTE.size])
        val ch = name.firstOrNull { it.isLetterOrDigit() }
        tv.text = if (ch != null && ch.isLetter()) ch.uppercaseChar().toString() else "#"
    }
}

/** goAsync() + worker thread, so receivers never touch the provider on the main thread. */
fun BroadcastReceiver.async(block: () -> Unit) {
    val p = goAsync()
    Thread {
        try {
            block()
        } catch (_: Exception) {
        } finally {
            p.finish()
        }
    }.start()
}
