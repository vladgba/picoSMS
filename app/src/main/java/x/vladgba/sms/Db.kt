package x.vladgba.sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A folder-like label: matches conversations by sender and/or message words. */
/** [hide] = keep these conversations out of the Inbox (main list); they show only under the label. */
class Label(val id: Long, val name: String, val senders: String, val words: String, val both: Boolean, val hide: Boolean = false)
class Picker(val id: Long, val name: String, val regex: String, val enabled: Boolean)
class Blocked(val id: Long, val addr: String, val body: String, val date: Long, val sub: Int, val reason: String)

/** App-private storage: filter rules and the blocked-message quarantine. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "pico.db", null, 7) {

    override fun onCreate(db: SQLiteDatabase) {
        createRules(db, "rules")
        db.execSQL("CREATE TABLE blocked(_id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, body TEXT, date INTEGER, sub INTEGER, reason TEXT)")
        createPickers(db)
        createLabels(db)
        createSentParts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {
        if (from < 2) createPickers(db)
        if (from < 3) createLabels(db)
        if (from < 4) migrateRules(db)
        // v2–v4 pickers tables lack "enabled" (a table created above for v1 already has it).
        if (from in 2..4) db.execSQL("ALTER TABLE pickers ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
        // v3–v5 labels tables lack "hide" (a table created above for v1–v2 already has it).
        if (from in 3..5) db.execSQL("ALTER TABLE labels ADD COLUMN hide INTEGER NOT NULL DEFAULT 0")
        if (from < 7) createSentParts(db)
    }

    private fun createSentParts(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE sent_parts(uri TEXT NOT NULL, part INTEGER NOT NULL, ok INTEGER NOT NULL, PRIMARY KEY(uri, part))"
    )

    fun startSend(uri: String, count: Int) = tx {
        for (part in 0 until count) {
            val v = ContentValues()
            v.put("uri", uri)
            v.put("part", part)
            v.put("ok", -1)
            writableDatabase.insertOrThrow("sent_parts", null, v)
        }
    }

    fun clearSend(uri: String) = writableDatabase.delete("sent_parts", "uri=?", arrayOf(uri))

    /** Persist every callback, including across process restarts; success requires every part. */
    fun sentPart(uri: String, part: Int, ok: Boolean): Int? {
        var result: Int? = null
        tx {
            val v = ContentValues()
            v.put("ok", if (ok) 1 else 0)
            if (writableDatabase.update("sent_parts", v, "uri=? AND part=? AND ok=-1", arrayOf(uri, part.toString())) == 0) return@tx
            readableDatabase.rawQuery("SELECT ok FROM sent_parts WHERE uri=?", arrayOf(uri)).use { cur ->
                var pending = false
                var failed = false
                while (cur.moveToNext()) {
                    if (cur.getInt(0) < 0) pending = true
                    if (cur.getInt(0) == 0) failed = true
                }
                result = if (failed) Sms.FAILED else if (!pending) Sms.SENT else null
                if (!pending) clearSend(uri)
            }
        }
        return result
    }

    private fun createRules(db: SQLiteDatabase, table: String) = db.execSQL(
        "CREATE TABLE $table(_id INTEGER PRIMARY KEY AUTOINCREMENT, black INTEGER NOT NULL, enabled INTEGER NOT NULL, " +
            "smode INTEGER NOT NULL, senders TEXT NOT NULL, bmode INTEGER NOT NULL, bodies TEXT NOT NULL, cs INTEGER NOT NULL)"
    )

    /** v3 rules (one field + pattern, /regex/ and * per entry) -> v4 sender/text conditions with a mode. */
    private fun migrateRules(db: SQLiteDatabase) {
        createRules(db, "rules4")
        db.rawQuery("SELECT _id, black, field, pattern FROM rules", null).use {
            while (it.moveToNext()) {
                val r = Rules.legacy(it.getLong(0), it.getInt(1) != 0, it.getInt(2) != 0, it.getString(3)) ?: continue
                val v = ContentValues()
                v.put("_id", r.id)
                v.put("black", if (r.black) 1 else 0)
                v.put("enabled", 1)
                v.put("smode", r.sMode)
                v.put("senders", r.senders)
                v.put("bmode", r.bMode)
                v.put("bodies", r.bodies)
                v.put("cs", 0)
                db.insert("rules4", null, v)
            }
        }
        db.execSQL("DROP TABLE rules")
        db.execSQL("ALTER TABLE rules4 RENAME TO rules")
    }

    private fun createLabels(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE IF NOT EXISTS labels(_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, senders TEXT NOT NULL, words TEXT NOT NULL, both_ INTEGER NOT NULL, hide INTEGER NOT NULL DEFAULT 0)")

    fun labels(): List<Label> {
        val out = ArrayList<Label>()
        readableDatabase.rawQuery("SELECT _id, name, senders, words, both_, hide FROM labels ORDER BY _id", null).use {
            while (it.moveToNext()) out.add(Label(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4) != 0, it.getInt(5) != 0))
        }
        return out
    }

    /** Inserts (id <= 0) or updates a label. @return its id. */
    fun saveLabel(id: Long, name: String, senders: String, words: String, both: Boolean, hide: Boolean): Long {
        val v = ContentValues()
        v.put("name", name)
        v.put("senders", senders)
        v.put("words", words)
        v.put("both_", if (both) 1 else 0)
        v.put("hide", if (hide) 1 else 0)
        if (id > 0) {
            writableDatabase.update("labels", v, "_id=$id", null)
            return id
        }
        return writableDatabase.insert("labels", null, v)
    }

    fun removeLabel(id: Long) = writableDatabase.delete("labels", "_id=$id", null)

    private fun createPickers(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE IF NOT EXISTS pickers(_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, regex TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1)")

    fun pickers(): List<Picker> {
        val out = ArrayList<Picker>()
        readableDatabase.rawQuery("SELECT _id, name, regex, enabled FROM pickers ORDER BY _id", null).use {
            while (it.moveToNext()) out.add(Picker(it.getLong(0), it.getString(1), it.getString(2), it.getInt(3) != 0))
        }
        return out
    }

    /** Inserts (id <= 0) or updates a picker. */
    fun savePicker(id: Long, name: String, regex: String) {
        val v = ContentValues()
        v.put("name", name)
        v.put("regex", regex)
        if (id > 0) writableDatabase.update("pickers", v, "_id=$id", null)
        else writableDatabase.insert("pickers", null, v)
    }

    fun setPickerEnabled(id: Long, on: Boolean) {
        val v = ContentValues()
        v.put("enabled", if (on) 1 else 0)
        writableDatabase.update("pickers", v, "_id=$id", null)
    }

    fun insertPicker(name: String, regex: String, enabled: Boolean) {
        val v = ContentValues()
        v.put("name", name)
        v.put("regex", regex)
        v.put("enabled", if (enabled) 1 else 0)
        writableDatabase.insert("pickers", null, v)
    }

    /** Deletes every row of a config table (rules, labels or pickers). */
    fun clear(table: String) = writableDatabase.delete(table, null, null)

    /** Runs [block] in one transaction: all of it is saved, or none of it. */
    fun tx(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun removePicker(id: Long) = writableDatabase.delete("pickers", "_id=$id", null)

    fun rules(): List<Rule> {
        val out = ArrayList<Rule>()
        readableDatabase.rawQuery(
            "SELECT _id, black, enabled, smode, senders, bmode, bodies, cs FROM rules ORDER BY black, _id DESC", null
        ).use {
            while (it.moveToNext()) out.add(
                Rule(
                    it.getLong(0), it.getInt(1) != 0, it.getInt(2) != 0, it.getInt(3), it.getString(4),
                    it.getInt(5), it.getString(6), it.getInt(7) != 0
                )
            )
        }
        return out
    }

    /** Inserts (id <= 0) or updates a rule. */
    fun saveRule(r: Rule) {
        val v = ContentValues()
        v.put("black", if (r.black) 1 else 0)
        v.put("enabled", if (r.enabled) 1 else 0)
        v.put("smode", r.sMode)
        v.put("senders", r.senders)
        v.put("bmode", r.bMode)
        v.put("bodies", r.bodies)
        v.put("cs", if (r.cs) 1 else 0)
        if (r.id > 0) writableDatabase.update("rules", v, "_id=${r.id}", null)
        else writableDatabase.insert("rules", null, v)
    }

    fun setRuleEnabled(id: Long, on: Boolean) {
        val v = ContentValues()
        v.put("enabled", if (on) 1 else 0)
        writableDatabase.update("rules", v, "_id=$id", null)
    }

    fun removeRule(id: Long) = writableDatabase.delete("rules", "_id=$id", null)

    fun blocked(): List<Blocked> {
        val out = ArrayList<Blocked>()
        readableDatabase.rawQuery("SELECT _id, address, body, date, sub, reason FROM blocked ORDER BY date DESC", null).use {
            while (it.moveToNext()) out.add(
                Blocked(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getLong(3), it.getInt(4), it.getString(5) ?: "")
            )
        }
        return out
    }

    fun blockedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM blocked", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun addBlocked(addr: String, body: String, date: Long, sub: Int, reason: String): Long {
        val v = ContentValues()
        v.put("address", addr)
        v.put("body", body)
        v.put("date", date)
        v.put("sub", sub)
        v.put("reason", reason)
        return writableDatabase.insertOrThrow("blocked", null, v)
    }

    fun removeBlocked(id: Long) = writableDatabase.delete("blocked", "_id=$id", null)

    fun clearBlocked() = writableDatabase.delete("blocked", null, null)

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(c.applicationContext).also { inst = it }
        }
    }
}
