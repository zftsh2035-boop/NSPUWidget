package ru.nspu.schedule

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Personal data is separate from every schedule cache. Callers use IO. */
internal object Notes {
    @Volatile private var helper: Database? = null
    private fun database(c: Context): SQLiteDatabase {
        val owner = helper ?: synchronized(this) {
            helper ?: Database(c.applicationContext).also { helper = it }
        }
        return owner.writableDatabase
    }

    fun forDay(c: Context, groupId: Int, date: LocalDate): List<PersonalNote> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return database(c).query("notes", null,
            "group_id=? AND ((scope='lesson' AND lesson_date=?) OR " +
                "(scope='day' AND visible_from<? AND visible_until>?))",
            arrayOf(groupId.toString(), date.toString(), end.toString(), start.toString()),
            null, null, "lesson_time, updated_at DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.note()) }
        }.filter { !it.target.isDay || it.options.visibleOn(date) }
    }

    fun all(c: Context): List<PersonalNote> = database(c).query("notes", null, null, null,
        null, null, "lesson_date DESC, lesson_time, updated_at DESC")
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.note()) } }

    fun nextBoundary(c: Context, groupId: Int): Long? {
        val now = System.currentTimeMillis().toString()
        return database(c).rawQuery("""SELECT MIN(at) FROM (
            SELECT visible_from AS at FROM notes WHERE group_id=? AND visible_from>?
            UNION ALL SELECT visible_until AS at FROM notes WHERE group_id=? AND visible_until>?)""",
            arrayOf(groupId.toString(), now, groupId.toString(), now)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    }

    fun find(c: Context, id: String): PersonalNote? = database(c).query("notes", null,
        "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.note() else null }

    fun save(c: Context, request: NoteEditorRequest, value: String,
        options: NoteOptions = request.note?.options ?: NoteOptions()) {
        val text = value.trim()
        require(text.length <= PersonalNote.MAX_LENGTH && options.valid())
        if (text.isBlank()) { request.note?.let { delete(c, it) }; return }
        val target = request.target
        require(target.groupId > 0 && target.subjectKey.isNotBlank())
        require(!target.isDay || (options.visibleFrom != null && options.visibleUntil != null))
        val db = database(c)
        db.beginTransaction()
        try {
            val existing = request.note?.let { find(c, it.id) }
                ?: if (!target.isDay) db.query("notes", null,
                    "scope='lesson' AND group_id=? AND lesson_date=?",
                    arrayOf(target.groupId.toString(), target.date.toString()), null, null, null)
                    .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.note()) } }
                    .singleOrNull { it.target.slot == target.slot } else null
            val values = ContentValues().apply {
                put("id", existing?.id ?: UUID.randomUUID().toString())
                put("group_id", target.groupId); put("lesson_date", target.date.toString())
                put("subject", target.subject); put("subject_key", target.subjectKey)
                put("lesson_time", target.time); put("kind", target.kind); put("subgroup", target.subgroup)
                put("unique_on_day", if (target.uniqueOnDay) 1 else 0)
                put("text", text); put("updated_at", System.currentTimeMillis()); put("scope", target.scope)
                put("visible_from", options.visibleFrom); put("visible_until", options.visibleUntil)
                put("reminders", JSONArray(options.reminders.sorted()).toString())
            }
            if (existing == null) db.insertOrThrow("notes", null, values)
            else check(db.update("notes", values, "id=?", arrayOf(existing.id)) == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        Store.changed(c, target.groupId)
        runCatching { NoteReminders.reschedule(c) }
            .onFailure { android.util.Log.e("NSPUWidget", "Cannot schedule note reminders", it) }
    }

    fun delete(c: Context, note: PersonalNote) {
        val db = database(c)
        db.beginTransaction()
        try {
            db.delete("note_deliveries", "note_id=?", arrayOf(note.id))
            db.delete("notes", "id=?", arrayOf(note.id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        NoteReminders.dismiss(c, note.id)
        Store.changed(c, note.target.groupId)
        runCatching { NoteReminders.reschedule(c) }
            .onFailure { android.util.Log.e("NSPUWidget", "Cannot schedule note reminders", it) }
    }

    fun delivered(c: Context): Set<Pair<String, Long>> = database(c).query("note_deliveries",
        null, null, null, null, null, null).use { cursor -> buildSet {
        while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1))
    } }

    fun markDelivered(c: Context, id: String, at: Long): Boolean = database(c).insertWithOnConflict(
        "note_deliveries", null, ContentValues().apply { put("note_id", id); put("at", at) },
        SQLiteDatabase.CONFLICT_IGNORE) != -1L

    private fun Cursor.note(): PersonalNote {
        fun string(name: String) = getString(getColumnIndexOrThrow(name))
        fun nullableLong(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
        val reminders = JSONArray(string("reminders"))
        return PersonalNote(string("id"), NoteTarget(getInt(getColumnIndexOrThrow("group_id")),
            LocalDate.parse(string("lesson_date")), string("subject"), string("lesson_time"),
            string("kind"), string("subgroup"), getInt(getColumnIndexOrThrow("unique_on_day")) == 1,
            string("scope")), string("text"), getLong(getColumnIndexOrThrow("updated_at")),
            NoteOptions(nullableLong("visible_from"), nullableLong("visible_until"),
                List(reminders.length()) { reminders.getLong(it) }))
    }

    private class Database(c: Context) : SQLiteOpenHelper(c, "personal-notes.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) = createTables(db)
        private fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE notes (
                id TEXT PRIMARY KEY NOT NULL,
                group_id INTEGER NOT NULL, lesson_date TEXT NOT NULL,
                subject TEXT NOT NULL, subject_key TEXT NOT NULL, lesson_time TEXT NOT NULL,
                kind TEXT NOT NULL, subgroup TEXT NOT NULL, unique_on_day INTEGER NOT NULL,
                text TEXT NOT NULL, updated_at INTEGER NOT NULL,
                scope TEXT NOT NULL DEFAULT 'lesson', visible_from INTEGER, visible_until INTEGER,
                reminders TEXT NOT NULL DEFAULT '[]'
            )""")
            db.execSQL("CREATE INDEX notes_by_day ON notes(group_id, lesson_date)")
            db.execSQL("""CREATE UNIQUE INDEX notes_lesson_slot ON notes
                (group_id, lesson_date, subject_key, lesson_time, kind, subgroup) WHERE scope='lesson'""")
            db.execSQL("CREATE TABLE note_deliveries (note_id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(note_id, at))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // SQLiteOpenHelper supplies the transaction. Preserve every original row and id.
                db.execSQL("ALTER TABLE notes RENAME TO notes_v1")
                db.execSQL("DROP INDEX IF EXISTS notes_by_day")
                createTables(db)
                db.execSQL("""INSERT INTO notes (id, group_id, lesson_date, subject, subject_key,
                    lesson_time, kind, subgroup, unique_on_day, text, updated_at)
                    SELECT id, group_id, lesson_date, subject, subject_key, lesson_time, kind,
                    subgroup, unique_on_day, text, updated_at FROM notes_v1""")
                db.execSQL("DROP TABLE notes_v1")
            }
        }
    }
}
