package x.vladgba.sms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var banner: View
    private lateinit var labelBar: LinearLayout
    private var all: List<Conv> = ArrayList()
    private var labels: List<Label> = ArrayList()
    private val adapter = ConvAdapter()

    private val reloader = Runnable { reload() }

    private val observer = object : ContentObserver(Ui.main) {
        override fun onChange(selfChange: Boolean) {
            Ui.main.removeCallbacks(reloader)
            Ui.main.postDelayed(reloader, 200)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        Ui.edge(this, findViewById(R.id.root))
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        banner = findViewById(R.id.banner)
        labelBar = findViewById(R.id.labelBar)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, pos, _ ->
            val cv = adapter.items[pos]
            startActivity(ThreadActivity.intent(this, cv.tid, cv.addr))
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            convMenu(adapter.items[pos]); true
        }
        findViewById<View>(R.id.fab).setOnClickListener {
            startActivity(ThreadActivity.intent(this, -1, null))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnDefault).setOnClickListener { Ui.requestDefault(this) }
    }

    override fun onResume() {
        super.onResume()
        val def = Sms.isDefault(this)
        banner.visibility = if (def) View.GONE else View.VISIBLE
        if (def) askPermissions()
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        reload()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
        Ui.main.removeCallbacks(reloader)
    }

    private fun reload() {
        Ui.load({
            val labels = Db.get(this).labels()
            val l = Sms.conversations(this, Labels.compile(labels))
            val otp = Picks.otpEnabled(this)
            val pickers = Picks.compiled(this)
            for (cv in l) {
                cv.name = Contacts.label(this, cv.addr)
                // Same as in the chat: OTP for incoming messages, user pickers for any message.
                val picks = Picks.extract(cv.snippet, otp && cv.type == Sms.INBOX, pickers)
                cv.pick = if (picks.isEmpty()) null else picks[0]
            }
            Widget.update(this)
            l to labels
        }) { (l, lb) ->
            all = l
            labels = lb
            showFiltered()
        }
    }

    // ---- Labels (folders) ----

    /** Index of the selected label in [labels], or -1 for "All". */
    private fun selectedIndex(): Int {
        val id = Labels.selected(this)
        if (id == 0L) return -1
        for (i in labels.indices) if (labels[i].id == id && i < Labels.MAX) return i
        return -1
    }

    private fun inLabel(cv: Conv, i: Int) = cv.labels and (1L shl i) != 0L

    private fun showFiltered() {
        val sel = selectedIndex()
        adapter.items = if (sel < 0) {
            // Inbox: everything except conversations in a label marked "hide from Inbox".
            var hidden = 0L
            for (i in labels.indices) if (i < Labels.MAX && labels[i].hide) hidden = hidden or (1L shl i)
            if (hidden == 0L) all else all.filter { it.labels and hidden == 0L }
        } else all.filter { inLabel(it, sel) }
        adapter.notifyDataSetChanged()
        (empty as TextView).setText(if (sel < 0) R.string.no_messages else R.string.label_empty)
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        renderBar(sel)
    }

    private fun chip(text: String, on: Boolean): TextView {
        val t = layoutInflater.inflate(R.layout.item_label, labelBar, false) as TextView
        t.text = text
        t.setBackgroundResource(if (on) R.drawable.label_on else R.drawable.label_off)
        t.setTextColor(getColor(if (on) R.color.on_accent else R.color.text))
        labelBar.addView(t)
        return t
    }

    private fun renderBar(sel: Int) {
        labelBar.removeAllViews()
        chip(getString(R.string.label_all), sel < 0).setOnClickListener { Labels.select(this, 0); showFiltered() }
        for (i in labels.indices) {
            if (i >= Labels.MAX) break
            val lb = labels[i]
            var unread = 0
            for (cv in all) if (inLabel(cv, i)) unread += cv.unread
            val text = if (unread > 0) getString(R.string.label_chip, lb.name, unread) else lb.name
            val c = chip(text, i == sel)
            c.setOnClickListener { Labels.select(this, lb.id); showFiltered() }
            c.setOnLongClickListener { labelMenu(lb); true }
        }
        chip(getString(R.string.label_add), false).setOnClickListener { labelDialog(null, null) }
    }

    private fun labelMenu(lb: Label) {
        val items = arrayOf(getString(R.string.edit), getString(R.string.delete))
        AlertDialog.Builder(this).setTitle(lb.name).setItems(items) { _, w ->
            if (w == 0) labelDialog(lb, null)
            else AlertDialog.Builder(this).setMessage(getString(R.string.label_delete_q, lb.name))
                .setPositiveButton(R.string.delete) { _, _ ->
                    Ui.load({ Db.get(this).removeLabel(lb.id) }) {
                        if (Labels.selected(this) == lb.id) Labels.select(this, 0)
                        reload()
                    }
                }
                .setNegativeButton(R.string.cancel, null).show()
        }.show()
    }

    /** Create ([edit] null) or edit a label; [addSender] pre-fills a sender for "Add sender to label". */
    private fun labelDialog(edit: Label?, addSender: String?) {
        if (edit == null && labels.size >= Labels.MAX) {
            Toast.makeText(this, getString(R.string.label_limit, Labels.MAX), Toast.LENGTH_SHORT).show()
            return
        }
        val v = layoutInflater.inflate(R.layout.dialog_label, null)
        val name = v.findViewById<EditText>(R.id.name)
        val senders = v.findViewById<EditText>(R.id.senders)
        val words = v.findViewById<EditText>(R.id.words)
        val both = v.findViewById<Switch>(R.id.both)
        val hide = v.findViewById<Switch>(R.id.hide)
        val info = v.findViewById<TextView>(R.id.info)
        val box = v.findViewById<LinearLayout>(R.id.examples)
        if (edit != null) {
            name.setText(edit.name)
            senders.setText(edit.senders)
            words.setText(edit.words)
            both.isChecked = edit.both
            hide.isChecked = edit.hide
        }
        if (addSender != null) senders.setText(addSender)

        val d = AlertDialog.Builder(this).setTitle(if (edit == null) R.string.label_new else R.string.label_edit)
            .setView(v)
            .setPositiveButton(R.string.save_only, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun current(): Label {
            val s = Rules.normalize(senders.text.toString(), true, Mode.AUTO)
            val w = Rules.normalize(words.text.toString(), false, Mode.AUTO)
            var n = name.text.toString().trim()
            if (n.isEmpty()) n = Rules.entries(if (s.isNotEmpty()) s else w, s.isNotEmpty(), Mode.AUTO).firstOrNull() ?: ""
            return Label(edit?.id ?: 0, n, s, w, both.isChecked, hide.isChecked)
        }

        val refresh = Runnable {
            val lb = current()
            val g = Apply.newGen()
            box.removeAllViews()
            both.isEnabled = lb.senders.isNotEmpty() && lb.words.isNotEmpty()
            val ok = d.getButton(AlertDialog.BUTTON_POSITIVE)
            val comp = Labels.Compiled(lb)
            if (lb.senders.isEmpty() && lb.words.isEmpty()) {
                info.setText(R.string.label_help)
                ok?.isEnabled = false
                return@Runnable
            }
            if (comp.invalid) {
                info.text = getString(R.string.pv_invalid_entry, comp.firstInvalid)
                ok?.isEnabled = false
                return@Runnable
            }
            ok?.isEnabled = true
            info.setText(R.string.searching)
            Ui.load({
                val one = ArrayList<Labels.Compiled>(1)
                one.add(comp)
                val hits = ArrayList<Conv>()
                for (cv in Sms.conversations(this, one)) if (cv.labels and 1L != 0L) hits.add(cv)
                val names = ArrayList<String>()
                if (g == Apply.gen) for (k in 0 until minOf(5, hits.size)) names.add(Contacts.label(this, hits[k].addr))
                val res: Pair<List<Conv>, List<String>>? = if (g != Apply.gen) null else hits to names
                res
            }) { res ->
                if (res == null || g != Apply.gen || !d.isShowing) return@load
                val (hits, names) = res
                info.text = if (hits.isEmpty()) getString(R.string.label_none) else getString(R.string.label_n, hits.size)
                for (k in names.indices) {
                    val tv = layoutInflater.inflate(R.layout.item_example, box, false) as TextView
                    val sb = android.text.SpannableStringBuilder(names[k])
                    sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, sb.length, 0)
                    sb.append('\n').append(hits[k].snippet.replace('\n', ' '))
                    tv.text = sb
                    box.addView(tv)
                }
            }
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                Ui.main.removeCallbacks(refresh)
                Ui.main.postDelayed(refresh, 400)
            }
        }
        senders.addTextChangedListener(watcher)
        words.addTextChangedListener(watcher)
        both.setOnCheckedChangeListener { _, _ -> Ui.main.removeCallbacks(refresh); Ui.main.postDelayed(refresh, 100) }
        d.setOnShowListener {
            refresh.run()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val lb = current()
                if (lb.senders.isEmpty() && lb.words.isEmpty() || Labels.Compiled(lb).invalid) return@setOnClickListener
                Ui.load({ Db.get(this).saveLabel(lb.id, lb.name, lb.senders, lb.words, lb.both, lb.hide) }) { id ->
                    Labels.select(this, id)
                    reload()
                }
                d.dismiss()
            }
        }
        d.setOnDismissListener {
            Ui.main.removeCallbacks(refresh)
            Apply.newGen()
        }
        d.show()
    }

    /** Appends a sender to an existing label, or starts a new label with it. */
    private fun addToLabel(cv: Conv) {
        val names = ArrayList<String>()
        for (lb in labels) names.add(lb.name)
        names.add(getString(R.string.label_new))
        AlertDialog.Builder(this).setTitle(R.string.add_to_label)
            .setItems(names.toTypedArray()) { _, w ->
                if (w == labels.size) {
                    labelDialog(null, cv.addr)
                    return@setItems
                }
                val lb = labels[w]
                Ui.load({
                    val s = Rules.normalize(lb.senders + "\n" + cv.addr, true, Mode.AUTO)
                    Db.get(this).saveLabel(lb.id, lb.name, s, lb.words, lb.both, lb.hide)
                }) {
                    Toast.makeText(this, getString(R.string.added_to_label, lb.name), Toast.LENGTH_SHORT).show()
                    reload()
                }
            }.show()
    }

    private fun convMenu(cv: Conv) {
        val items = arrayOf(
            getString(R.string.mark_read), getString(R.string.add_to_label),
            getString(R.string.block_sender), getString(R.string.delete_conv)
        )
        AlertDialog.Builder(this).setTitle(cv.name).setItems(items) { _, w ->
            when (w) {
                0 -> Ui.bg { Sms.markRead(this, cv.tid); Notifier.cancel(this, cv.tid) }
                1 -> addToLabel(cv)
                2 -> Ui.load({ Db.get(this).saveRule(Rule.sender(true, cv.addr)) }) {
                    Toast.makeText(this, R.string.sender_blocked, Toast.LENGTH_SHORT).show()
                }
                3 -> AlertDialog.Builder(this).setMessage(R.string.delete_conv_q)
                    .setPositiveButton(R.string.delete) { _, _ -> Ui.bg { Sms.deleteThread(this, cv.tid) } }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }.show()
    }

    private fun askPermissions() {
        if (asked) return
        val want = ArrayList<String>()
        want += Manifest.permission.READ_SMS
        want += Manifest.permission.SEND_SMS
        want += Manifest.permission.RECEIVE_SMS
        want += Manifest.permission.READ_CONTACTS
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            asked = true
            requestPermissions(missing.toTypedArray(), 2)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        Contacts.clear()
        reload()
    }

    private inner class ConvAdapter : BaseAdapter() {
        var items: List<Conv> = ArrayList()
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].tid

        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_conv, parent, false)
            val cv = items[p]
            val unread = cv.unread > 0
            Ui.avatar(v.findViewById(R.id.avatar), cv.name, cv.addr)
            v.findViewById<TextView>(R.id.name).apply {
                text = cv.name
                setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
            }
            v.findViewById<TextView>(R.id.time).apply {
                text = Ui.time(context, cv.date)
                setTextColor(getColor(if (unread) R.color.accent else R.color.text2))
            }
            v.findViewById<TextView>(R.id.snippet).apply {
                text = if (cv.type != Sms.INBOX) getString(R.string.you_prefix, cv.snippet) else cv.snippet
                setTextColor(getColor(if (unread) R.color.text else R.color.text2))
            }
            v.findViewById<TextView>(R.id.otp).apply {
                val pk = cv.pick
                visibility = if (pk != null) View.VISIBLE else View.GONE
                if (pk != null) {
                    text = pk.value
                    contentDescription = getString(R.string.copy_value, pk.value)
                    setOnClickListener { Picks.copy(this@MainActivity, pk.value, pk.otp) }
                }
            }
            v.findViewById<TextView>(R.id.badge).apply {
                visibility = if (unread) View.VISIBLE else View.GONE
                text = String.format(java.util.Locale.getDefault(), "%d", cv.unread)
            }
            return v
        }
    }

    companion object {
        private var asked = false
    }
}
