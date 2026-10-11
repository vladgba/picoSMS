package x.vladgba.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager

object Sender {
    const val ACTION_SENT = "x.vladgba.sms.SENT"

    @Suppress("DEPRECATION")
    private fun manager(c: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** Sends [text] to every recipient in [to] (separated by , or ;). Must run off the main thread. */
    fun sendAll(c: Context, to: String, text: String) {
        for (a in split(to)) send(c, a, text)
    }

    fun split(to: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in 0..to.length) {
            if (i == to.length || to[i] == ',' || to[i] == ';') {
                val s = to.substring(start, i).trim()
                if (s.isNotEmpty()) out.add(s)
                start = i + 1
            }
        }
        return out
    }

    fun send(c: Context, addr: String, text: String) {
        // As default SMS app we must write outgoing messages to the provider ourselves.
        val uri = if (Sms.isDefault(c)) Sms.insertOutgoing(c, addr, text) else null
        try {
            val sm = manager(c)
            val parts = sm.divideMessage(text)
            val data = uri ?: Uri.parse("pico://sent/" + System.nanoTime())
            val pis = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val it = Intent(ACTION_SENT, data, c, SentReceiver::class.java)
                    .putExtra("p", i).putExtra("n", parts.size)
                pis.add(PendingIntent.getBroadcast(c, i, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT))
            }
            if (parts.size > 1) sm.sendMultipartTextMessage(addr, null, parts, pis, null)
            else sm.sendTextMessage(addr, null, text, pis[0], null)
        } catch (_: Exception) {
            if (uri != null) Sms.setType(c, uri, Sms.FAILED)
        }
    }
}
