package x.vladgba.sms

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews

/** Home-screen widget: the four latest conversations, each with a compact "copy OTP" chip when it has a code. */
class Widget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        async { update(c) }
    }

    companion object {
        private val ROWS = intArrayOf(R.id.r0, R.id.r1, R.id.r2, R.id.r3)
        private val NAMES = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3)
        private val TIMES = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3)
        private val TEXTS = intArrayOf(R.id.s0, R.id.s1, R.id.s2, R.id.s3)
        private val OTPS = intArrayOf(R.id.o0, R.id.o1, R.id.o2, R.id.o3)

        /** Rebuilds every placed widget. Call off the main thread. */
        fun update(c: Context) {
            val m = AppWidgetManager.getInstance(c) ?: return
            val ids = m.getAppWidgetIds(ComponentName(c, Widget::class.java))
            if (ids.isEmpty()) return
            val convs = Sms.conversations(c)
            val otpOn = Picks.otpEnabled(c)
            val v = RemoteViews(c.packageName, R.layout.widget)

            // Empty space / empty state opens the app.
            v.setOnClickPendingIntent(
                android.R.id.background,
                PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            v.setViewVisibility(R.id.w_empty, if (convs.isEmpty()) View.VISIBLE else View.GONE)

            for (k in ROWS.indices) {
                if (k >= convs.size) {
                    v.setViewVisibility(ROWS[k], View.GONE)
                    continue
                }
                val cv = convs[k]
                val label = Contacts.label(c, cv.addr)
                val name: CharSequence = if (cv.unread == 0) label else SpannableString(label).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                v.setViewVisibility(ROWS[k], View.VISIBLE)
                v.setTextViewText(NAMES[k], name)
                v.setTextViewText(TIMES[k], Ui.time(c, cv.date))
                v.setTextViewText(TEXTS[k], cv.snippet.replace('\n', ' '))
                v.setTextColor(TIMES[k], c.getColor(if (cv.unread > 0) R.color.accent else R.color.text2))
                v.setOnClickPendingIntent(ROWS[k], Notifier.openThread(c, -10 - k, cv.tid, cv.addr))

                val code = if (otpOn && cv.type == Sms.INBOX) Picks.otp(cv.snippet) else null
                if (code == null) {
                    v.setViewVisibility(OTPS[k], View.GONE)
                } else {
                    v.setViewVisibility(OTPS[k], View.VISIBLE)
                    v.setTextViewText(OTPS[k], code)
                    v.setContentDescription(OTPS[k], c.getString(R.string.copy_value, code))
                    val copy = Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.COPY)
                        .putExtra(ActionReceiver.EXTRA_VALUE, code).putExtra(ActionReceiver.EXTRA_OTP, true)
                    v.setOnClickPendingIntent(
                        OTPS[k],
                        PendingIntent.getBroadcast(c, 100 + k, copy, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    )
                }
            }
            m.updateAppWidget(ids, v)
        }
    }
}
