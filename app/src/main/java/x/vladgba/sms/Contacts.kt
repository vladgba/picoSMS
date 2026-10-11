package x.vladgba.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Phone number -> contact name, cached for the life of the process. */
object Contacts {
    private val cache = HashMap<String, String>()

    fun can(c: Context) =
        c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun name(c: Context, addr: String): String? {
        synchronized(cache) { cache[addr]?.let { return it.ifEmpty { null } } }
        var n: String? = null
        if (addr.isNotBlank() && can(c)) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(addr))
                c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) n = it.getString(0) }
            } catch (_: Exception) {
            }
        }
        synchronized(cache) { cache[addr] = n ?: "" }
        return n
    }

    fun label(c: Context, addr: String) = name(c, addr) ?: addr

    fun clear() = synchronized(cache) { cache.clear() }
}
