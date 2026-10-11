package x.vladgba.sms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/** Settings with tabs: Filters, Blocked, Quick pick, Backup, About. */
class SettingsActivity : Activity() {
    private lateinit var bar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var action: ImageButton
    private val pages = arrayOfNulls<Page>(TABS.size)
    private var current = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_settings)
        Ui.edge(this, findViewById(R.id.root))
        bar = findViewById(R.id.tabs)
        content = findViewById(R.id.content)
        action = findViewById(R.id.btnAction)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        action.setOnClickListener { pages[current]?.onAction() }
        show(state?.getInt("tab") ?: intent.getIntExtra(EXTRA_TAB, 0))
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", current)
    }

    override fun onResume() {
        super.onResume()
        pages[current]?.onShow()
    }

    private fun page(i: Int): Page = pages[i] ?: when (i) {
        TAB_FILTERS -> RulesPage(this)
        TAB_BLOCKED -> BlockedPage(this)
        TAB_PICK -> PickersPage(this)
        TAB_BACKUP -> BackupPage(this)
        else -> AboutPage(this)
    }.also { pages[i] = it }

    /** Switches to tab [i]. */
    fun show(i: Int) {
        current = if (i in TABS.indices) i else 0
        bar.removeAllViews()
        for (k in TABS.indices) {
            val t = layoutInflater.inflate(R.layout.item_label, bar, false) as TextView
            t.setText(TABS[k])
            val on = k == current
            t.setBackgroundResource(if (on) R.drawable.label_on else R.drawable.label_off)
            t.setTextColor(getColor(if (on) R.color.on_accent else R.color.text))
            t.setOnClickListener { if (k != current) show(k) }
            bar.addView(t)
        }
        val p = page(current)
        content.removeAllViews()
        (p.view.parent as? ViewGroup)?.removeView(p.view)
        content.addView(p.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (p.actionIcon != 0) {
            action.visibility = View.VISIBLE
            action.setImageResource(p.actionIcon)
            action.contentDescription = getString(p.actionLabel)
        } else {
            action.visibility = View.INVISIBLE
        }
        p.onShow()
    }

    @Deprecated("framework callback")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        for (p in pages) p?.onActivityResult(req, res, data)
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_SAMPLE = "sample"
        const val TAB_FILTERS = 0
        const val TAB_BLOCKED = 1
        const val TAB_PICK = 2
        const val TAB_BACKUP = 3
        const val TAB_ABOUT = 4
        private val TABS = intArrayOf(R.string.tab_filters, R.string.tab_blocked, R.string.quick_pick, R.string.tab_backup, R.string.about)

        fun intent(c: Context, tab: Int): Intent = Intent(c, SettingsActivity::class.java).putExtra(EXTRA_TAB, tab)
    }
}
