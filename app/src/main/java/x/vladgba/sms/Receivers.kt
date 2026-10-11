package x.vladgba.sms

import android.app.Activity
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.TelephonyManager

/** SMS_DELIVER: only the default SMS app receives this. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        if (parts.isEmpty()) return
        val first = parts[0] ?: return
        val addr = first.displayOriginatingAddress ?: first.originatingAddress ?: return
        val body = buildString { for (p in parts) if (p != null) append(p.displayMessageBody ?: "") }
        val sent = first.timestampMillis
        val sub = i.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", i.getIntExtra("subscription", -1))
        async {
            val reason = Rules.check(c, addr, body)
            if (reason != null) {
                Db.get(c).addBlocked(addr, body, System.currentTimeMillis(), sub, reason)
            } else {
                val uri = Sms.insertIncoming(c, addr, body, sent, sub)
                val tid = if (uri != null) Sms.threadOf(c, uri) else -1
                Notifier.message(c, tid, addr, body, Picks.forMessage(c, body))
                Widget.update(c)
            }
        }
    }
}

/** Required for the SMS role. picoSMS does not handle MMS; it only tells the user one arrived. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) Notifier.mms(c)
    }
}

/** Result of SmsManager send: mark the provider row as sent or failed. */
class SentReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val uri = i.data ?: return
        if (uri.scheme != "content") return
        val ok = resultCode == Activity.RESULT_OK
        val last = i.getIntExtra("p", 0) == i.getIntExtra("n", 1) - 1
        async {
            if (!ok) Sms.setType(c, uri, Sms.FAILED)
            else if (last) Sms.setType(c, uri, Sms.SENT, onlyIfNot = Sms.FAILED)
        }
    }
}

/** Notification actions: inline reply and mark-as-read. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val tid = i.getLongExtra(ThreadActivity.EXTRA_TID, -1)
        val addr = i.getStringExtra(ThreadActivity.EXTRA_ADDR)
        if (i.action == COPY) {
            i.getStringExtra(EXTRA_VALUE)?.let { Picks.copy(c, it, i.getBooleanExtra(EXTRA_OTP, false)) }
            return
        }
        val reply = if (i.action == REPLY) RemoteInput.getResultsFromIntent(i)
            ?.getCharSequence(Notifier.KEY_REPLY)?.toString() else null
        async {
            if (!reply.isNullOrBlank() && addr != null) Sender.send(c, addr, reply)
            if (tid >= 0) Sms.markRead(c, tid)
            Notifier.cancel(c, tid)
            Widget.update(c)
        }
    }

    companion object {
        const val REPLY = "x.vladgba.sms.REPLY"
        const val READ = "x.vladgba.sms.READ"
        const val COPY = "x.vladgba.sms.COPY"
        const val EXTRA_VALUE = "value"
        const val EXTRA_OTP = "otp"
    }
}

/** "Respond via message" from the incoming-call screen. */
class HeadlessSmsSendService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val text = i?.getStringExtra(Intent.EXTRA_TEXT)
        val data: Uri? = i?.data
        if (i?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE && !text.isNullOrBlank() && data != null) {
            val to = Uri.decode(data.schemeSpecificPart ?: "").substringBefore('?')
            Thread {
                try {
                    Sender.sendAll(this, to, text)
                } catch (_: Exception) {
                }
                stopSelf(startId)
            }.start()
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
