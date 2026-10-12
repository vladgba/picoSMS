package x.vladgba.sms

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

/** "Quick pick": OTP detection switch + user regex pickers with a live preview over real messages. */
class PickersPage(a: SettingsActivity) : Page(a, R.layout.page_pickers) {
    private val adapter = PickerAdapter()
    private lateinit var none: View
    private lateinit var otpSwitch: Switch

    init {
        val list = findViewById<ListView>(R.id.list)
        val header = layoutInflater.inflate(R.layout.pickers_header, list, false)
        list.addHeaderView(header, null, false)
        list.adapter = adapter
        none = header.findViewById(R.id.noPickers)

        val otp = header.findViewById<Switch>(R.id.swOtp)
        otpSwitch = otp
        otp.isChecked = Picks.otpEnabled(this)
        otp.setOnCheckedChangeListener { _, on -> Picks.setOtpEnabled(this, on) }

        list.setOnItemClickListener { _, _, pos, _ ->
            val p = adapter.items.getOrNull(pos - list.headerViewsCount) ?: return@setOnItemClickListener
            AlertDialog.Builder(this).setTitle(p.name)
                .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, w ->
                    if (w == 0) addDialog(null, p)
                    else AlertDialog.Builder(this).setTitle(p.name).setMessage(R.string.delete_picker_q)
                        .setPositiveButton(R.string.delete) { _, _ -> Ui.load({ Db.get(this).removePicker(p.id) }) { reload() } }
                        .setNegativeButton(R.string.cancel, null).show()
                }.show()
        }
        // Opened from a message's "Create picker" menu: start with that message as the sample.
        a.intent.getStringExtra(SettingsActivity.EXTRA_SAMPLE)?.let {
            a.intent.removeExtra(SettingsActivity.EXTRA_SAMPLE)
            Ui.main.post { addDialog(it, null) }
        }
    }

    override val actionIcon = R.drawable.ic_add
    override val actionLabel = R.string.add_picker
    override fun onAction() {
        addDialog(null, null)
    }


    override fun onShow() {
        otpSwitch.isChecked = Picks.otpEnabled(this)
        reload()
    }

    private fun reload() {
        Ui.load({ Db.get(this).pickers() }) {
            adapter.items = it
            adapter.notifyDataSetChanged()
            none.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** New picker ([edit] null) or edit one; [sample] is a message to test against first. */
    private fun addDialog(sample: String?, edit: Picker?) {
        val v = layoutInflater.inflate(R.layout.dialog_picker, null)
        val name = v.findViewById<EditText>(R.id.name)
        val regex = v.findViewById<EditText>(R.id.regex)
        if (edit != null) {
            name.setText(edit.name)
            regex.setText(edit.regex)
        }
        val info = v.findViewById<TextView>(R.id.info)
        val box = v.findViewById<LinearLayout>(R.id.examples)

        // Preset chips fill in a ready-made regex.
        val presets = v.findViewById<LinearLayout>(R.id.presets)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this)
        scroll.addView(row)
        presets.addView(scroll)
        for ((label, rx) in PRESETS) {
            val chip = layoutInflater.inflate(R.layout.item_chip, row, false) as TextView
            chip.setCompoundDrawablesRelative(null, null, null, null)
            chip.text = label
            chip.setOnClickListener {
                if (name.text.isBlank() || PRESETS.any { it.first == name.text.toString() }) name.setText(label)
                regex.setText(rx)
                regex.setSelection(regex.text.length)
            }
            row.addView(chip)
        }

        val d = AlertDialog.Builder(this).setTitle(if (edit == null) R.string.add_picker else R.string.edit_picker).setView(v)
            .setPositiveButton(R.string.save_only, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val refresh = Runnable {
            val g = Apply.newGen()
            box.removeAllViews()
            val src = regex.text.toString()
            val ok = d.getButton(AlertDialog.BUTTON_POSITIVE)
            if (src.isBlank()) {
                info.setText(R.string.pk_empty)
                ok?.isEnabled = false
                return@Runnable
            }
            val rx = Picks.compile(src)
            if (rx == null) {
                info.setText(R.string.pv_invalid)
                ok?.isEnabled = false
                return@Runnable
            }
            ok?.isEnabled = true
            info.setText(R.string.searching)
            Ui.load({
                Picks.preview(this, rx, g, sample)?.let { p -> p to p.examples.map { e -> if (e.addr.isEmpty()) getString(R.string.this_message) else Contacts.label(this, e.addr) } }
            }) { res ->
                if (res == null || g != Apply.gen || !d.isShowing) return@load
                val (p, names) = res
                info.text = if (p.total == 0) getString(R.string.pk_none, p.scanned) else getString(R.string.pk_n, p.total, p.scanned)
                fill(box, p, names)
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Ui.main.removeCallbacks(refresh)
                Ui.main.postDelayed(refresh, 300)
            }
        }
        regex.addTextChangedListener(watcher)
        d.setOnShowListener {
            refresh.run()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val src = regex.text.toString()
                if (Picks.compile(src) == null) return@setOnClickListener
                val n = name.text.toString().trim().ifEmpty { src }
                Ui.load({ Db.get(this).savePicker(edit?.id ?: 0, n, src) }) { reload() }
                d.dismiss()
            }
        }
        d.setOnDismissListener {
            Ui.main.removeCallbacks(refresh)
            Apply.newGen()
        }
        d.show()
    }

    private fun fill(box: LinearLayout, p: Picks.PickPreview, names: List<String>) {
        box.removeAllViews()
        val accent = getColor(R.color.accent)
        for ((i, e) in p.examples.withIndex()) {
            val sb = SpannableStringBuilder()
            val head = names[i] + "  →  "
            sb.append(head)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val vs = sb.length
            sb.append(e.value)
            sb.setSpan(StyleSpan(Typeface.BOLD), vs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(accent), vs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append('\n')
            var body = e.body.replace('\n', ' ')
            var rs = e.span.start
            var re = e.span.end
            if (rs > 40) {
                val cut = rs - 30
                body = "…" + body.substring(cut)
                rs -= cut - 1
                re -= cut - 1
            }
            val st = sb.length
            sb.append(body)
            val a = st + rs
            val b = minOf(st + re, sb.length)
            if (a < b) sb.setSpan(ForegroundColorSpan(accent), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val tv = layoutInflater.inflate(R.layout.item_example, box, false) as TextView
            tv.text = sb
            box.addView(tv)
        }
    }

    private inner class PickerAdapter : BaseAdapter() {
        var items: List<Picker> = ArrayList()
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id

        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_rule, parent, false)
            val k = items[p]
            val dim = if (k.enabled) 1f else 0.45f
            v.findViewById<TextView>(R.id.tag).apply {
                setText(R.string.tag_regex)
                setTextColor(getColor(R.color.accent))
                alpha = dim
            }
            v.findViewById<TextView>(R.id.pattern).apply {
                text = k.name
                alpha = dim
            }
            v.findViewById<TextView>(R.id.field).apply {
                text = k.regex
                typeface = Typeface.MONOSPACE
                alpha = dim
            }
            v.findViewById<CheckBox>(R.id.on).apply {
                setOnCheckedChangeListener(null)
                isChecked = k.enabled
                setOnCheckedChangeListener { _, on ->
                    Ui.load({ Db.get(this@PickersPage).setPickerEnabled(k.id, on) }) { reload() }
                }
            }
            return v
        }
    }

    companion object {
        private val PRESETS = listOf(
            "Tracking #" to "\\b([A-Z]{2}\\d{9}[A-Z]{2}|1Z[0-9A-Z]{16})\\b",
            "Amount" to "(\\d+(?:[.,]\\d{1,2})?)\\s?(?:лв|bgn|eur|€|usd|\\$|rub|руб|uah|грн)",
            "Link" to "(https?://\\S+)",
            "Any 4–8 digits" to "(?<!\\d)(\\d{4,8})(?!\\d)",
            "IBAN" to "\\b([A-Z]{2}\\d{2}[A-Z0-9]{11,30})\\b"
        )
    }
}
