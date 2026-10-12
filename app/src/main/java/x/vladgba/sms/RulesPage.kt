package x.vladgba.sms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.provider.ContactsContract
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class RulesPage(a: SettingsActivity) : Page(a, R.layout.page_rules) {
    private val adapter = RuleAdapter()
    private lateinit var noRules: View
    private lateinit var blockedRow: TextView
    private lateinit var whitelistSwitch: Switch
    private lateinit var contactsSwitch: Switch

    /** Sender field of the open rule dialog, filled by the contact picker. */
    private var pickTarget: EditText? = null

    init {
        val list = findViewById<ListView>(R.id.list)
        val header = layoutInflater.inflate(R.layout.rules_header, list, false)
        list.addHeaderView(header, null, false)
        list.adapter = adapter
        noRules = header.findViewById(R.id.noRules)
        blockedRow = header.findViewById(R.id.blockedRow)

        val wl = header.findViewById<Switch>(R.id.swWhitelist)
        val ct = header.findViewById<Switch>(R.id.swContacts)
        whitelistSwitch = wl
        contactsSwitch = ct
        wl.isChecked = Rules.whitelistMode(this)
        ct.isChecked = Rules.allowContacts(this)
        wl.setOnCheckedChangeListener { _, on -> Rules.setWhitelistMode(this, on) }
        ct.setOnCheckedChangeListener { _, on -> Rules.setAllowContacts(this, on) }
        blockedRow.setOnClickListener { a.show(SettingsActivity.TAB_BLOCKED) }

        list.setOnItemClickListener { _, _, pos, _ ->
            adapter.items.getOrNull(pos - list.headerViewsCount)?.let { ruleMenu(it) }
        }
    }

    override val actionIcon = R.drawable.ic_add
    override val actionLabel = R.string.add_rule
    override fun onAction() {
        ruleDialog(null)
    }


    override fun onShow() {
        whitelistSwitch.isChecked = Rules.whitelistMode(this)
        contactsSwitch.isChecked = Rules.allowContacts(this)
        reload()
    }

    private fun reload() {
        Ui.load({ Db.get(this).let { it.rules() to it.blockedCount() } }) { (rules, blocked) ->
            adapter.items = rules
            adapter.notifyDataSetChanged()
            noRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
            blockedRow.text = getString(R.string.blocked_n, blocked)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---- Rule dialog: sender mode, senders, case, contacts, text (+), text mode, text rows ----

    /** New rule when [edit] is null, otherwise edits that rule in place. */
    private fun ruleDialog(edit: Rule?) {
        val v = layoutInflater.inflate(R.layout.dialog_rule, null)
        val black = v.findViewById<RadioButton>(R.id.rBlack)
        val sMode = v.findViewById<Spinner>(R.id.sMode)
        val senders = v.findViewById<EditText>(R.id.senders)
        val cs = v.findViewById<CheckBox>(R.id.cs)
        val bMode = v.findViewById<Spinner>(R.id.bMode)
        val bodies = v.findViewById<LinearLayout>(R.id.bodies)
        val info = v.findViewById<TextView>(R.id.info)
        val box = v.findViewById<LinearLayout>(R.id.examples)

        val modes = ArrayAdapter.createFromResource(this, R.array.modes, android.R.layout.simple_spinner_item)
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sMode.adapter = modes
        bMode.adapter = modes
        sMode.setSelection(edit?.sMode ?: Mode.EQUALS)
        bMode.setSelection(edit?.bMode ?: Mode.CONTAINS)

        val d = AlertDialog.Builder(this).setTitle(if (edit == null) R.string.add_rule else R.string.edit_rule).setView(v)
            .setPositiveButton(R.string.apply_rule, null)
            .setNeutralButton(R.string.save_only, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun bodyTexts(): String {
            val b = StringBuilder()
            for (i in 0 until bodies.childCount) {
                val t = bodies.getChildAt(i).findViewById<EditText>(R.id.text).text.toString()
                if (b.isNotEmpty()) b.append('\n')
                b.append(t.replace('\n', ' '))
            }
            return b.toString()
        }

        fun current(): Rule? {
            val sm = sMode.selectedItemPosition
            val bm = bMode.selectedItemPosition
            val s = Rules.normalize(senders.text.toString(), true, sm)
            val b = Rules.normalize(bodyTexts(), false, bm)
            if (s.isEmpty() && b.isEmpty()) return null
            return Rule(edit?.id ?: 0, black.isChecked, edit?.enabled ?: true, sm, s, bm, b, cs.isChecked)
        }

        fun buttons(enabled: Boolean, count: Int) {
            val ok = d.getButton(AlertDialog.BUTTON_POSITIVE)
            ok?.isEnabled = enabled
            ok?.text = if (count > 0) getString(R.string.apply_rule_n, count) else getString(R.string.apply_rule)
            d.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = enabled
        }

        val refresh = Runnable {
            val r = current()
            val g = Apply.newGen()
            box.removeAllViews()
            bMode.visibility = if (bodies.childCount > 0) View.VISIBLE else View.INVISIBLE
            if (r == null) {
                info.setText(R.string.rule_empty)
                buttons(false, 0)
                return@Runnable
            }
            val bad = Rules.Matcher(r).firstInvalid
            if (bad != null) {
                info.text = getString(R.string.pv_invalid_entry, bad)
                buttons(false, 0)
                return@Runnable
            }
            info.setText(R.string.searching)
            buttons(true, 0)
            Ui.load({ Apply.preview(this, r, g)?.let { it to labelsFor(it.examples) } }) { res ->
                if (res == null || g != Apply.gen || !d.isShowing) return@load
                val (p, names) = res
                info.text = when {
                    r.black && p.total == 0 -> getString(R.string.pv_black_none)
                    r.black -> getString(R.string.pv_black_n, p.total)
                    p.total == 0 -> getString(R.string.pv_white_none)
                    else -> getString(R.string.pv_white_n, p.total)
                }
                fillExamples(box, r, p, names)
                buttons(true, p.total)
            }
        }
        val schedule = {
            Ui.main.removeCallbacks(refresh)
            Ui.main.postDelayed(refresh, 300)
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                schedule()
            }
        }
        val onMode = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                schedule()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        fun addBody(text: String) {
            val row = layoutInflater.inflate(R.layout.item_body, bodies, false)
            val et = row.findViewById<EditText>(R.id.text)
            et.setText(text)
            et.addTextChangedListener(watcher)
            row.findViewById<View>(R.id.remove).setOnClickListener {
                bodies.removeView(row)
                schedule()
            }
            bodies.addView(row)
        }

        if (edit != null) {
            v.findViewById<RadioButton>(if (edit.black) R.id.rBlack else R.id.rWhite).isChecked = true
            senders.setText(edit.senders)
            cs.isChecked = edit.cs
            for (e in Rules.entries(edit.bodies, false, edit.bMode)) addBody(e)
        }
        senders.addTextChangedListener(watcher)
        sMode.onItemSelectedListener = onMode
        bMode.onItemSelectedListener = onMode
        cs.setOnCheckedChangeListener { _, _ -> schedule() }
        v.findViewById<RadioGroup>(R.id.gList).setOnCheckedChangeListener { _, _ -> schedule() }
        v.findViewById<View>(R.id.btnAddBody).setOnClickListener {
            addBody("")
            bodies.getChildAt(bodies.childCount - 1).findViewById<View>(R.id.text).requestFocus()
            schedule()
        }
        @Suppress("DEPRECATION")
        v.findViewById<View>(R.id.btnContacts).setOnClickListener {
            pickTarget = senders
            runCatching {
                a.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), PICK)
            }
        }

        d.setOnShowListener {
            refresh.run()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = current() ?: return@setOnClickListener
                if (Rules.Matcher(r).invalid) return@setOnClickListener
                save(r, apply = true)
                d.dismiss()
            }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val r = current() ?: return@setOnClickListener
                if (Rules.Matcher(r).invalid) return@setOnClickListener
                save(r, apply = false)
                d.dismiss()
            }
        }
        d.setOnDismissListener {
            Ui.main.removeCallbacks(refresh)
            Apply.newGen()
            pickTarget = null
        }
        d.show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data
        val target = pickTarget
        if (req != PICK || res != Activity.RESULT_OK || uri == null || target == null) return
        Ui.load({
            try {
                contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            } catch (_: Exception) {
                null
            }
        }) { n ->
            if (n == null) return@load
            val cur = target.text.toString().trimEnd()
            target.setText(if (cur.isEmpty()) n else cur + "\n" + n)
            target.setSelection(target.text.length)
        }
    }

    private fun labelsFor(hits: List<Hit>): List<String> {
        val out = ArrayList<String>(hits.size)
        for (h in hits) out.add(Contacts.label(this, h.addr))
        return out
    }

    private fun save(r: Rule, apply: Boolean) {
        Ui.load({
            Db.get(this).saveRule(r)
            if (apply) Apply.apply(this, r) else 0
        }) { n ->
            toast(
                when {
                    n < 0 -> getString(R.string.apply_needs_default)
                    !apply || n == 0 -> getString(R.string.rule_saved)
                    r.black -> getString(R.string.applied_black, n)
                    else -> getString(R.string.applied_white, n)
                }
            )
            reload()
        }
    }

    /** Example cards: sender · time, then the message with the matching text highlighted. */
    private fun fillExamples(box: LinearLayout, r: Rule, p: Preview, names: List<String>) {
        box.removeAllViews()
        val m = Rules.Matcher(r)
        val accent = getColor(R.color.accent)
        val fg = getColor(R.color.text)
        for ((i, h) in p.examples.withIndex()) {
            val sb = SpannableStringBuilder()
            val name = names[i]
            sb.append(name)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(if (m.sender != null) accent else fg), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ·  ").append(Ui.time(this, h.date)).append('\n')

            var body = h.body.replace('\n', ' ')
            val hit = m.span(body)
            var hs = hit?.start ?: 0
            var he = hit?.end ?: 0
            // Keep the highlighted part visible in long messages.
            if (hit != null && hs > 40) {
                val cut = hs - 30
                body = "…" + body.substring(cut)
                hs -= cut - 1
                he -= cut - 1
            }
            val start = sb.length
            sb.append(body)
            if (hit != null && he > hs) {
                val a = start + hs
                val b = minOf(start + he, sb.length)
                sb.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(accent), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val tv = layoutInflater.inflate(R.layout.item_example, box, false) as TextView
            tv.text = sb
            box.addView(tv)
        }
        if (p.total > p.examples.size) {
            val more = layoutInflater.inflate(R.layout.item_example, box, false) as TextView
            more.background = null
            more.text = getString(R.string.pv_more, p.total - p.examples.size)
            box.addView(more)
        }
    }

    // ---- Existing rule: edit, apply now or delete ----

    private fun ruleMenu(r: Rule) {
        val items = arrayOf(getString(R.string.edit), getString(R.string.apply_existing), getString(R.string.delete))
        AlertDialog.Builder(this).setTitle(summary(r)).setItems(items) { _, w ->
            when (w) {
                0 -> ruleDialog(r)
                1 -> applyExisting(r)
                else -> AlertDialog.Builder(this).setTitle(summary(r)).setMessage(R.string.delete_rule_q)
                    .setPositiveButton(R.string.delete) { _, _ -> Ui.load({ Db.get(this).removeRule(r.id) }) { reload() } }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }.show()
    }

    private fun applyExisting(r: Rule) {
        val g = Apply.newGen()
        Ui.load({ Apply.preview(this, r, g, limit = 3)?.let { it to labelsFor(it.examples) } }) { res ->
            val (p, names) = res ?: return@load
            if (p.total == 0) {
                toast(getString(R.string.apply_nothing))
                return@load
            }
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            box.setPadding(pad, pad / 3, pad, 0)
            fillExamples(box, r, p, names)
            AlertDialog.Builder(this)
                .setTitle(getString(if (r.black) R.string.apply_q_black else R.string.apply_q_white, p.total))
                .setView(box)
                .setPositiveButton(if (r.black) R.string.move else R.string.restore) { _, _ ->
                    Ui.load({ Apply.apply(this, r) }) { n ->
                        toast(
                            when {
                                n < 0 -> getString(R.string.apply_needs_default)
                                r.black -> getString(R.string.moved_black, n)
                                else -> getString(R.string.moved_white, n)
                            }
                        )
                        reload()
                    }
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    /** "BANK · INFO  +  casino · bonus +2" */
    private fun summary(r: Rule): String {
        val b = StringBuilder()
        list(b, Rules.entries(r.senders, true, r.sMode))
        val t = Rules.entries(r.bodies, false, r.bMode)
        if (t.isNotEmpty()) {
            if (b.isNotEmpty()) b.append("  +  ")
            list(b, t)
        }
        return b.toString()
    }

    private fun list(b: StringBuilder, e: List<String>) {
        for (i in 0 until minOf(3, e.size)) {
            if (i > 0) b.append(" · ")
            b.append(e[i])
        }
        if (e.size > 3) b.append(" +").append(e.size - 3)
    }

    /** "Sender equals, and text contains · case sensitive" */
    private fun describe(r: Rule): String {
        val modes = resources.getStringArray(R.array.modes)
        val s = if (r.senders.isBlank()) null else getString(R.string.d_sender, modes[r.sMode].lowercase())
        val t = if (r.bodies.isBlank()) null else getString(R.string.d_text, modes[r.bMode].lowercase())
        var out = when {
            s != null && t != null -> getString(R.string.d_and, s, t.lowercase())
            else -> s ?: t ?: ""
        }
        if (r.cs) out += " · " + getString(R.string.d_cs)
        return out
    }

    private inner class RuleAdapter : BaseAdapter() {
        var items: List<Rule> = ArrayList()
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id

        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_rule, parent, false)
            val r = items[p]
            val dim = if (r.enabled) 1f else 0.45f
            v.findViewById<TextView>(R.id.tag).apply {
                setText(if (r.black) R.string.tag_block else R.string.tag_allow)
                setTextColor(getColor(if (r.black) R.color.danger else R.color.allow))
                alpha = dim
            }
            v.findViewById<TextView>(R.id.pattern).apply {
                text = summary(r)
                alpha = dim
            }
            v.findViewById<TextView>(R.id.field).apply {
                text = describe(r)
                alpha = dim
            }
            v.findViewById<CheckBox>(R.id.on).apply {
                setOnCheckedChangeListener(null)
                isChecked = r.enabled
                setOnCheckedChangeListener { _, on ->
                    Ui.load({ Db.get(this@RulesPage).setRuleEnabled(r.id, on) }) { reload() }
                }
            }
            return v
        }
    }

    companion object {
        private const val PICK = 7
    }
}
