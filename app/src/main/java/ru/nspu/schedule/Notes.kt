package ru.nspu.schedule

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.util.UUID

/** Personal data is separate from every schedule cache and survives cache replacement. */
internal object Notes {
    @Volatile private var helper: Database? = null
    private fun database(c: Context): SQLiteDatabase {
        val owner = helper ?: synchronized(this) {
            helper ?: Database(c.applicationContext).also { helper = it }
        }
        return owner.writableDatabase
    }

    fun forDay(c: Context, groupId: Int, date: LocalDate): List<PersonalNote> =
        database(c).query("notes", null, "group_id=? AND lesson_date=?",
            arrayOf(groupId.toString(), date.toString()), null, null, "lesson_time, updated_at DESC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.note()) } }

    fun all(c: Context): List<PersonalNote> = database(c).query("notes", null, null, null,
        null, null, "lesson_date DESC, lesson_time, updated_at DESC")
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.note()) } }

    fun find(c: Context, id: String): PersonalNote? = database(c).query("notes", null,
        "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) it.note() else null }

    /** Call on IO. The transaction completes before the editor reports success. */
    fun save(c: Context, request: NoteEditorRequest, value: String) {
        val text = value.trim()
        require(text.length <= PersonalNote.MAX_LENGTH)
        if (text.isBlank()) { request.note?.let { delete(c, it) }; return }
        val target = request.target
        require(target.groupId > 0 && target.subjectKey.isNotBlank())
        val db = database(c)
        db.beginTransaction()
        try {
            // Reuse the row id even if an unambiguous same-day time change moved it.
            val existing = request.note?.let { find(c, it.id) }
                ?: forDay(c, target.groupId, target.date).singleOrNull { it.target.slot == target.slot }
            val values = ContentValues().apply {
                put("id", existing?.id ?: UUID.randomUUID().toString())
                put("group_id", target.groupId)
                put("lesson_date", target.date.toString())
                put("subject", target.subject)
                put("subject_key", target.subjectKey)
                put("lesson_time", target.time)
                put("kind", target.kind)
                put("subgroup", target.subgroup)
                put("unique_on_day", if (target.uniqueOnDay) 1 else 0)
                put("text", text)
                put("updated_at", System.currentTimeMillis())
            }
            if (existing == null) db.insertOrThrow("notes", null, values)
            else check(db.update("notes", values, "id=?", arrayOf(existing.id)) == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        Store.changed(c, target.groupId)
    }

    fun delete(c: Context, note: PersonalNote) {
        database(c).delete("notes", "id=?", arrayOf(note.id))
        Store.changed(c, note.target.groupId)
    }

    private fun Cursor.note() = PersonalNote(getString(getColumnIndexOrThrow("id")),
        NoteTarget(getInt(getColumnIndexOrThrow("group_id")),
            LocalDate.parse(getString(getColumnIndexOrThrow("lesson_date"))),
            getString(getColumnIndexOrThrow("subject")), getString(getColumnIndexOrThrow("lesson_time")),
            getString(getColumnIndexOrThrow("kind")), getString(getColumnIndexOrThrow("subgroup")),
            getInt(getColumnIndexOrThrow("unique_on_day")) == 1),
        getString(getColumnIndexOrThrow("text")), getLong(getColumnIndexOrThrow("updated_at")))

    private class Database(c: Context) : SQLiteOpenHelper(c, "personal-notes.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE notes (
                id TEXT PRIMARY KEY NOT NULL,
                group_id INTEGER NOT NULL, lesson_date TEXT NOT NULL,
                subject TEXT NOT NULL, subject_key TEXT NOT NULL, lesson_time TEXT NOT NULL,
                kind TEXT NOT NULL, subgroup TEXT NOT NULL, unique_on_day INTEGER NOT NULL,
                text TEXT NOT NULL, updated_at INTEGER NOT NULL,
                UNIQUE(group_id, lesson_date, subject_key, lesson_time, kind, subgroup)
            )""")
            db.execSQL("CREATE INDEX notes_by_day ON notes(group_id, lesson_date)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future migrations must preserve personal data; never drop this table.
            error("Missing personal notes migration: $oldVersion -> $newVersion")
        }
    }
}
