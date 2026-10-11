package x.vladgba.sms

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast

/** Backup tab: back up / restore all SMS, export / import settings. */
class BackupPage(a: SettingsActivity) : Page(a, R.layout.page_backup) {

    init {
        findViewById<TextView>(R.id.rowBackup).setOnClickListener { backupDialog() }
        findViewById<TextView>(R.id.rowRestore).setOnClickListener {
            if (Sms.isDefault(this)) openBackup()
            else Toast.makeText(this, R.string.rs_need_default, Toast.LENGTH_LONG).show()
        }
        findViewById<TextView>(R.id.rowExport).setOnClickListener { pickFile(true) }
        findViewById<TextView>(R.id.rowImport).setOnClickListener { pickFile(false) }
    }

    // ---- Export / import of settings (rules, labels, pickers) via the system file picker ----

    @Suppress("DEPRECATION")
    private fun pickFile(export: Boolean) {
        val i = if (export) {
            val day = android.text.format.DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "picoSMS-settings-$day.json")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        try {
            a.startActivityForResult(i, if (export) EXPORT else IMPORT)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null) return
        if (req == EXPORT) Ui.load({
            try {
                val json = Backup.export(this)
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                true
            } catch (_: Exception) {
                false
            }
        }) { ok -> Toast.makeText(this, if (ok) R.string.exported else R.string.export_failed, Toast.LENGTH_SHORT).show() }
        else if (req == BACKUP) {
            val pw = pendingPw
            pendingPw = null
            runBackup(uri, pendingJson, pw)
        } else if (req == RESTORE) Ui.load({
            try {
                SmsBackup.isEncrypted(this, uri)
            } catch (_: Exception) {
                false
            }
        }) { enc -> if (enc) askPassword(uri, false) else runRestore(uri, null) }
        else if (req == IMPORT) Ui.load({
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > 2_000_000) null else Backup.parse(String(bytes, Charsets.UTF_8))
                }
            } catch (_: Exception) {
                null
            }
        }) { p ->
            if (p == null) {
                Toast.makeText(this, R.string.import_bad, Toast.LENGTH_LONG).show()
                return@load
            }
            AlertDialog.Builder(this).setTitle(R.string.import_cfg)
                .setMessage(getString(R.string.import_q, p.rules.size, p.labels.size, p.pickers.size))
                .setPositiveButton(R.string.import_replace) { _, _ -> doImport(p, true) }
                .setNeutralButton(R.string.import_merge) { _, _ -> doImport(p, false) }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    // ---- SMS backup / restore ----

    private var pendingJson = false
    private var pendingPw: CharArray? = null

    @Suppress("DEPRECATION")
    private fun backupDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_backup, null)
        val json = v.findViewById<RadioButton>(R.id.fJson)
        val pw = v.findViewById<EditText>(R.id.pw)
        val pw2 = v.findViewById<EditText>(R.id.pw2)
        val note = v.findViewById<TextView>(R.id.note)
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                note.setText(if (pw.text.isEmpty()) R.string.bk_note_plain else R.string.bk_note_enc)
            }
        }
        pw.addTextChangedListener(watcher)
        val d = AlertDialog.Builder(this).setTitle(R.string.bk_backup).setView(v)
            .setPositiveButton(R.string.bk_choose, null)
            .setNegativeButton(R.string.cancel, null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pw.text.toString() != pw2.text.toString()) {
                    pw2.error = getString(R.string.bk_pw_mismatch)
                    return@setOnClickListener
                }
                pendingJson = json.isChecked
                pendingPw = if (pw.text.isEmpty()) null else CharArray(pw.text.length) { i -> pw.text[i] }
                pw.text.clear()
                pw2.text.clear()
                d.dismiss()
                val day = android.text.format.DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())
                val ext = if (pendingJson) "json" else "xml"
                val enc = pendingPw != null
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if (enc) "application/octet-stream" else if (pendingJson) "application/json" else "text/xml")
                    .putExtra(Intent.EXTRA_TITLE, "picoSMS-sms-$day.$ext" + if (enc) ".enc" else "")
                try {
                    a.startActivityForResult(i, BACKUP)
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
                }
            }
        }
        d.show()
    }

    @Suppress("DEPRECATION")
    private fun openBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try {
            a.startActivityForResult(i, RESTORE)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
        }
    }

    /** Non-cancelable dialog whose message a worker thread can update. */
    private fun progress(title: Int): Pair<AlertDialog, (String) -> Unit> {
        val d = AlertDialog.Builder(this).setTitle(title).setMessage("…").setCancelable(false).create()
        d.show()
        return d to { msg: String -> a.runOnUiThread { if (d.isShowing) d.setMessage(msg) } }
    }

    private fun finishTask(d: AlertDialog, block: () -> Unit) = a.runOnUiThread {
        try {
            d.dismiss()
        } catch (_: Exception) {
        }
        if (!a.isFinishing && !a.isDestroyed) block()
    }

    private fun runBackup(uri: Uri, json: Boolean, pw: CharArray?) {
        val (d, set) = progress(R.string.bk_backup)
        val app = applicationContext
        Thread {
            val res = try {
                val n = SmsBackup.export(app, uri, json, pw) { a, b -> set(getString(R.string.bk_working, a, b)) }
                getString(R.string.bk_done, n)
            } catch (e: Exception) {
                getString(R.string.bk_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                pw?.fill('\u0000')
            }
            finishTask(d) { Toast.makeText(app, res, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun askPassword(uri: Uri, retry: Boolean) {
        val et = EditText(this)
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.setHint(R.string.bk_pw_hint)
        if (retry) et.error = getString(R.string.rs_wrong)
        val box = LinearLayout(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 3, pad, 0)
        box.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this).setTitle(R.string.rs_password).setView(box)
            .setPositiveButton(R.string.bk_restore) { _, _ ->
                val pw = CharArray(et.text.length) { i -> et.text[i] }
                et.text.clear()
                runRestore(uri, pw)
            }
            .setNegativeButton(R.string.cancel, null).show()
        et.requestFocus()
    }

    private fun runRestore(uri: Uri, pw: CharArray?) {
        val (d, set) = progress(R.string.bk_restore)
        if (pw != null) set(getString(R.string.decrypting))
        val app = applicationContext
        Thread {
            var wrong = false
            val res = try {
                val src = SmsBackup.open(app, uri, pw)
                val r = SmsBackup.restore(app, src) { a, b ->
                    set(if (b > 0) getString(R.string.rs_working_of, a, b) else getString(R.string.rs_working, a))
                }
                var msg = getString(R.string.rs_done, r.added, r.duplicates)
                if (r.mms > 0) msg += getString(R.string.rs_done_mms, r.mms)
                msg
            } catch (_: Crypt.WrongPassword) {
                wrong = true
                ""
            } catch (_: java.security.NoSuchAlgorithmException) {
                getString(R.string.rs_new_android)
            } catch (e: Exception) {
                getString(R.string.rs_failed, e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                getString(R.string.rs_failed, "out of memory")
            } finally {
                pw?.fill('\u0000')
            }
            Widget.update(app)
            finishTask(d) {
                if (wrong) askPassword(uri, true)
                else {
                    AlertDialog.Builder(this).setTitle(R.string.bk_restore).setMessage(res)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }.start()
    }

    private fun doImport(p: Backup.Parsed, replace: Boolean) {
        Ui.load({
            try {
                Backup.apply(this, p, replace)
            } catch (_: Exception) {
                null
            }
        }) { n ->
            if (n == null) Toast.makeText(this, R.string.import_bad, Toast.LENGTH_LONG).show()
            else {
                Toast.makeText(this, getString(R.string.imported, n[0], n[1], n[2]), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val EXPORT = 10
        private const val IMPORT = 11
        private const val BACKUP = 12
        private const val RESTORE = 13
    }
}
