package x.vladgba.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

object Notifier {
    private const val CH = "sms"
    private const val MMS_ID = 0x7FFF_FFF0
    const val KEY_REPLY = "reply"

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    @Suppress("DEPRECATION")
    private fun builder(c: Context): Notification.Builder {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = nm(c)
            if (nm.getNotificationChannel(CH) == null) {
                val ch = NotificationChannel(CH, c.getString(R.string.ch_messages), NotificationManager.IMPORTANCE_HIGH)
                ch.description = c.getString(R.string.ch_messages_desc)
                nm.createNotificationChannel(ch)
            }
            return Notification.Builder(c, CH)
        }
        return Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
    }

    /** Back stack: main list -> thread, so "back" from a notification lands in the app. */
    fun openThread(c: Context, req: Int, tid: Long, addr: String?): PendingIntent =
        TaskStackBuilder.create(c)
            .addNextIntent(Intent(c, MainActivity::class.java))
            .addNextIntent(ThreadActivity.intent(c, tid, addr))
            .getPendingIntent(req, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)!!

    fun message(c: Context, tid: Long, addr: String, body: String, pick: Pick? = null) {
        val id = tid.toInt()
        val b = builder(c)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(c.getColor(R.color.accent))
            .setContentTitle(Contacts.label(c, addr))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openThread(c, id, tid, addr))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_MESSAGE)

        val base = Intent(c, ActionReceiver::class.java)
            .putExtra(ThreadActivity.EXTRA_TID, tid)
            .putExtra(ThreadActivity.EXTRA_ADDR, addr)

        if (pick != null) {
            val copyPi = PendingIntent.getBroadcast(
                c, id, Intent(base).setAction(ActionReceiver.COPY)
                    .putExtra(ActionReceiver.EXTRA_VALUE, pick.value).putExtra(ActionReceiver.EXTRA_OTP, pick.otp),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(c, R.drawable.ic_copy), c.getString(R.string.copy_value, pick.value), copyPi
                ).build()
            )
        }

        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val replyPi = PendingIntent.getBroadcast(
            c, id, Intent(base).setAction(ActionReceiver.REPLY), PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(c.getString(R.string.reply)).build()
        b.addAction(
            Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_send), c.getString(R.string.reply), replyPi)
                .addRemoteInput(input).build()
        )
        val readPi = PendingIntent.getBroadcast(
            c, id, Intent(base).setAction(ActionReceiver.READ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        b.addAction(
            Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_notif), c.getString(R.string.mark_read), readPi).build()
        )
        try {
            nm(c).notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }

    fun mms(c: Context) {
        val open = PendingIntent.getActivity(
            c, MMS_ID, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = builder(c)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(c.getColor(R.color.accent))
            .setContentTitle(c.getString(R.string.mms_unsupported))
            .setContentText(c.getString(R.string.mms_unsupported_text))
            .setContentIntent(open)
            .setAutoCancel(true)
        try {
            nm(c).notify(MMS_ID, b.build())
        } catch (_: SecurityException) {
        }
    }

    fun cancel(c: Context, tid: Long) {
        if (tid >= 0) nm(c).cancel(tid.toInt())
    }
}
