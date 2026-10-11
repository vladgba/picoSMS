package x.vladgba.sms

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast

/** About tab: version, default-app status, source code and privacy policy. */
class AboutPage(a: SettingsActivity) : Page(a, R.layout.page_about) {
    private val rowDefault = findViewById<TextView>(R.id.rowDefault)

    init {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        findViewById<TextView>(R.id.version).text = getString(R.string.version_n, ver)
        rowDefault.setOnClickListener { Ui.requestDefault(a) }
        findViewById<View>(R.id.rowSource).setOnClickListener { open(SOURCE) }
        findViewById<View>(R.id.rowPrivacy).setOnClickListener { open(PRIVACY) }
    }

    override fun onShow() {
        rowDefault.visibility = if (Sms.isDefault(this)) View.GONE else View.VISIBLE
    }

    /** Opens a link in the user's browser (picoSMS itself has no internet access). */
    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val SOURCE = "https://github.com/vladgba/picoSMS"
        private const val PRIVACY = "https://github.com/vladgba/picoSMS/blob/main/PRIVACY.md"
    }
}
