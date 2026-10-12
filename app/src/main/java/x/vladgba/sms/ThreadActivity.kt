package x.vladgba.sms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsMessage
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast

class ThreadActivity : Activity() {
    private var tid = -1L
    private var addr: String? = null

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var toRow: View
    private lateinit var to: EditText
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var list: ListView
    private val adapter = MsgAdapter()

    private val reloader = Runnable { reload() }
    private val observer = object : ContentObserver(Ui.main) {
        override fun onChange(selfChange: Boolean) {
            Ui.main.removeCallbacks(reloader)
            Ui.main.postDelayed(reloader, 150)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_thread)
        Ui.edge(this, findViewById(R.id.root))
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        toRow = findViewById(R.id.toRow)
        to = findViewById(R.id.to)
        input = findViewById(R.id.input)
        counter = findViewById(R.id.counter)
        list = findViewById(R.id.list)
        list.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSend).setOnClickListener { send() }
        findViewById<View>(R.id.btnCall).setOnClickListener {
            addr?.let { a -> runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a)))) } }
        }
        findViewById<View>(R.id.btnMore).setOnClickListener { threadMenu(it) }
        @Suppress("DEPRECATION")
        findViewById<View>(R.id.btnPick).setOnClickListener {
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), PICK)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (s.isEmpty()) {
                    counter.text = ""
                    return
                }
                val r = SmsMessage.calculateLength(s, false)
                counter.text = if (r[0] > 1) "${r[2]} · ${r[0]}" else r[2].toString()
            }
        })
        readIntent(intent)
    }

    private fun readIntent(i: Intent) {
        val data = i.data
        var body: String? = i.getStringExtra("sms_body") ?: i.getStringExtra(Intent.EXTRA_TEXT)
        val scheme = data?.scheme
        if (data != null && (scheme == "sms" || scheme == "smsto" || scheme == "mms" || scheme == "mmsto")) {
            val ssp = data.encodedSchemeSpecificPart ?: ""
            addr = Uri.decode(ssp.substringBefore('?')).takeIf { Sender.split(it).isNotEmpty() }
            val q = ssp.indexOf('?')
            if (body == null && q >= 0) {
                for (param in ssp.substring(q + 1).split('&')) {
                    if (Uri.decode(param.substringBefore('=')) == "body") {
                        body = Uri.decode(param.substringAfter('=', ""))
                        break
                    }
                }
            }
        } else {
            tid = i.getLongExtra(EXTRA_TID, -1)
            addr = i.getStringExtra(EXTRA_ADDR)?.takeIf { Sender.split(it).isNotEmpty() }
        }
        if (!body.isNullOrEmpty()) input.setText(body)

        val a = addr
        when {
            tid >= 0 && a == null -> Ui.load({ Sms.addressOf(this, tid) }) { addr = it; header() }
            tid < 0 && a != null -> Ui.load({ Sms.threadFor(this, Sender.split(a).first()) }) {
                if (Sender.split(a).size == 1) tid = it
                header(); reload()
            }
        }
        if (addr == null && tid < 0) {
            toRow.visibility = View.VISIBLE
            title.setText(R.string.new_message)
            to.requestFocus()
        }
        header()
    }

    private fun header() {
        val a = addr ?: return
        toRow.visibility = View.GONE
        title.text = a
        Ui.load({ Contacts.name(this, a) }) { n ->
            if (n != null) {
                title.text = n
                subtitle.text = a
                subtitle.visibility = View.VISIBLE
            } else {
                subtitle.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        reload()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
        Ui.main.removeCallbacks(reloader)
    }

    private fun reload() {
        val t = tid
        if (t < 0) return
        Ui.load({
            val msgs = Sms.messages(this, t)
            val otp = Picks.otpEnabled(this)
            val rx = Picks.compiled(this)
            for (m in msgs) m.picks = Picks.extract(m.body, otp && m.type == Sms.INBOX, rx)
            if (msgs.any { !it.read }) {
                Sms.markRead(this, t)
                Widget.update(this)
            }
            Notifier.cancel(this, t)
            msgs
        }) {
            val atEnd = list.lastVisiblePosition >= adapter.count - 2
            adapter.items = it
            adapter.notifyDataSetChanged()
            if (atEnd) list.setSelection(adapter.count - 1)
        }
    }

    private fun send() {
        val text = input.text.toString()
        if (text.isBlank()) return
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
            Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_SHORT).show()
            return
        }
        val target = addr ?: to.text.toString()
        val recipients = Sender.split(target)
        if (recipients.isEmpty()) {
            Toast.makeText(this, R.string.no_recipient, Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        Ui.load({
            val failed = recipients.filter { !Sender.send(this, it, text) }
            (if (tid < 0) Sms.threadFor(this, recipients[0]) else tid) to failed
        }) { (thread, failed) ->
            if (failed.isNotEmpty()) {
                if (input.text.isEmpty()) input.setText(text)
                if (recipients.size > 1) { addr = failed.joinToString(", "); tid = -1 }
                Toast.makeText(this, R.string.failed, Toast.LENGTH_LONG).show()
            }
            if (addr == null) {
                addr = if (recipients.size == 1) recipients[0] else target
                header()
            }
            if (recipients.size == 1) tid = thread
            reload()
            list.setSelection(adapter.count - 1)
        }
    }

    private fun threadMenu(anchor: View) {
        val a = addr ?: return
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, R.string.block_sender)
        pm.menu.add(0, 2, 0, R.string.copy_number)
        if (tid >= 0) pm.menu.add(0, 3, 0, R.string.delete_conv)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> Ui.load({ Db.get(this).saveRule(Rule.sender(true, a)) }) {
                    Toast.makeText(this, R.string.sender_blocked, Toast.LENGTH_SHORT).show()
                }
                2 -> copy(a)
                3 -> AlertDialog.Builder(this).setMessage(R.string.delete_conv_q)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        val t = tid
                        Ui.load({ Sms.deleteThread(this, t) }) { finish() }
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            true
        }
        pm.show()
    }

    private fun msgMenu(m: Msg) {
        val opts = ArrayList<Pair<Int, () -> Unit>>()
        for (p in m.picks) opts += 0 to { Picks.copy(this, p.value, p.otp) }
        opts += R.string.copy to { copy(m.body) }
        opts += R.string.create_picker to {
            startActivity(SettingsActivity.intent(this, SettingsActivity.TAB_PICK).putExtra(SettingsActivity.EXTRA_SAMPLE, m.body))
        }
        opts += R.string.forward to { startActivity(intent(this, -1, null).putExtra("sms_body", m.body)) }
        if (m.type == Sms.FAILED) opts += R.string.resend to {
            val a = addr
            if (a != null) Ui.bg { if (Sender.send(this, a, m.body)) Sms.deleteMsg(this, m.id) }
        }
        opts += R.string.delete to { Ui.bg { Sms.deleteMsg(this, m.id) } }
        AlertDialog.Builder(this)
            .setItems(opts.mapIndexed { i, o ->
                if (o.first == 0) getString(R.string.copy_value, m.picks[i].value) else getString(o.first)
            }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("sms", s))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("framework callback")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data
        if (req != PICK || res != RESULT_OK || uri == null) return
        Ui.load({
            try {
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (_: Exception) {
                null
            }
        }) { n ->
            if (n == null) return@load
            val cur = to.text.toString().trim().trimEnd(',', ';')
            to.setText(if (cur.isEmpty()) n else "$cur, $n")
            to.setSelection(to.text.length)
            input.requestFocus()
        }
    }

    private inner class MsgAdapter : BaseAdapter() {
        var items: List<Msg> = ArrayList()
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id

        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_msg, parent, false)
            val m = items[p]
            val out = m.type != Sms.INBOX
            val failed = m.type == Sms.FAILED
            (v as LinearLayout).gravity = if (out) Gravity.END else Gravity.START
            val bubble = v.findViewById<TextView>(R.id.bubble)
            bubble.text = m.body
            bubble.setBackgroundResource(
                when {
                    failed -> R.drawable.bubble_failed
                    out -> R.drawable.bubble_out
                    else -> R.drawable.bubble_in
                }
            )
            val fg = getColor(if (out) R.color.on_accent else R.color.text)
            bubble.setTextColor(fg)
            bubble.setLinkTextColor(fg)
            bubble.setOnLongClickListener { msgMenu(m); true }
            val chips = v.findViewById<LinearLayout>(R.id.picks)
            chips.removeAllViews()
            chips.visibility = if (m.picks.isEmpty()) View.GONE else View.VISIBLE
            for (pk in m.picks) {
                val chip = layoutInflater.inflate(R.layout.item_chip, chips, false) as TextView
                chip.text = pk.value
                chip.contentDescription = getString(R.string.copy_value, pk.value)
                chip.setOnClickListener { Picks.copy(this@ThreadActivity, pk.value, pk.otp) }
                chips.addView(chip)
            }
            val meta = v.findViewById<TextView>(R.id.meta)
            val showTime = p == items.size - 1 || items[p + 1].date - m.date > 10 * 60_000 ||
                (items[p + 1].type == Sms.INBOX) != !out
            val status = when (m.type) {
                Sms.OUTBOX, Sms.QUEUED -> getString(R.string.sending)
                Sms.FAILED -> getString(R.string.failed)
                Sms.DRAFT -> getString(R.string.draft)
                else -> null
            }
            if (showTime || status != null) {
                meta.visibility = View.VISIBLE
                meta.text = if (status != null) Ui.time(this@ThreadActivity, m.date) + " · " + status
                else Ui.time(this@ThreadActivity, m.date)
                meta.setTextColor(getColor(if (failed) R.color.danger else R.color.text2))
            } else {
                meta.visibility = View.GONE
            }
            return v
        }
    }

    companion object {
        const val EXTRA_TID = "tid"
        const val EXTRA_ADDR = "addr"
        private const val PICK = 5

        fun intent(c: Context, tid: Long, addr: String?): Intent =
            Intent(c, ThreadActivity::class.java).putExtra(EXTRA_TID, tid).putExtra(EXTRA_ADDR, addr)
    }
}
