package x.vladgba.sms

import android.content.ContextWrapper
import android.content.Intent
import android.view.LayoutInflater
import android.view.View

/**
 * One tab of [SettingsActivity]. Wraps the activity as its Context, so dialogs, toasts and
 * resources work exactly as they did when each tab was its own screen.
 */
abstract class Page(val a: SettingsActivity, layout: Int) : ContextWrapper(a) {
    val layoutInflater: LayoutInflater = LayoutInflater.from(a)
    val view: View = layoutInflater.inflate(layout, null)

    fun <T : View> findViewById(id: Int): T = view.findViewById(id)

    /** Icon and description of the header action for this tab (0 = none). */
    open val actionIcon = 0
    open val actionLabel = 0
    open fun onAction() {}

    /** Called every time the tab becomes visible (and when the screen resumes on it). */
    open fun onShow() {}

    open fun onActivityResult(req: Int, res: Int, data: Intent?) {}
}
