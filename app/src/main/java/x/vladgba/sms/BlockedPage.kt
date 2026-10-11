package x.vladgba.sms

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView

/** Quarantine for messages stopped by the filter rules. Nothing here touches the SMS provider until restored. */
class BlockedPage(a: SettingsActivity) : Page(a, R.layout.page_blocked) {
    private val adapter = BlockedAdapter()
    private lateinit var empty: View

    init {
        empty = findViewById(R.id.empty)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> open(adapter.items[pos]) }
    }

    override val actionIcon = R.drawable.ic_delete
    override val actionLabel = R.string.clear_all
    override fun onAction() {
        if (adapter.items.isEmpty()) return
        AlertDialog.Builder(this).setMessage(R.string.clear_all_q)
            .setPositiveButton(R.string.delete) { _, _ -> Ui.load({ Db.get(this).clearBlocked() }) { reload() } }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun onShow() = reload()


    private fun reload() {
        Ui.load({
            val l = Db.get(this).blocked()
            l.map { it to Contacts.label(this, it.addr) }
        }) {
            adapter.items = it.map { p -> p.first }
            adapter.names = it.map { p -> p.second }
            adapter.notifyDataSetChanged()
            empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun open(b: Blocked) {
        AlertDialog.Builder(this)
            .setTitle(b.addr)
            .setMessage(b.body + "\n\n" + Ui.fullTime(this, b.date) + "\n" + getString(R.string.reason, b.reason))
            .setPositiveButton(R.string.restore) { _, _ -> Ui.load({ restore(b) }) { reload() } }
            .setNeutralButton(R.string.allow_sender) { _, _ ->
                Ui.load({
                    Db.get(this).saveRule(Rule.sender(false, b.addr))
                    restore(b)
                }) { reload() }
            }
            .setNegativeButton(R.string.delete) { _, _ -> Ui.load({ Db.get(this).removeBlocked(b.id) }) { reload() } }
            .show()
    }

    private fun restore(b: Blocked) {
        if (Apply.restore(this, b)) Widget.update(this)
    }

    private inner class BlockedAdapter : BaseAdapter() {
        var items: List<Blocked> = ArrayList()
        var names: List<String> = ArrayList()
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].id

        override fun getView(p: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_blocked, parent, false)
            val b = items[p]
            v.findViewById<TextView>(R.id.name).text = names[p]
            v.findViewById<TextView>(R.id.time).text = Ui.time(this@BlockedPage, b.date)
            v.findViewById<TextView>(R.id.body).text = b.body
            v.findViewById<TextView>(R.id.reason).text = b.reason
            return v
        }
    }
}
