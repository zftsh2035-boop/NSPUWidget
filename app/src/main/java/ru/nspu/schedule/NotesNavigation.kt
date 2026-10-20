package ru.nspu.schedule

import android.content.Context
import android.content.Intent
import java.time.LocalDate

internal object NotesNavigation {
    fun fill(intent: Intent, target: NoteTarget, note: PersonalNote?): Intent = intent
        .putExtra("open_note", true).putExtra("note_id", note?.id)
        .putExtra("note_date", target.date.toString()).putExtra("note_subject", target.subject)
        .putExtra("note_time", target.time).putExtra("note_kind", target.kind)
        .putExtra("note_subgroup", target.subgroup)
        .putExtra("note_unique", target.uniqueOnDay)
        .putExtra("note_scope", target.scope)

    /** Re-read on IO, so an old launcher view never resurrects deleted or edited text. */
    fun read(c: Context, intent: Intent): NoteEditorRequest? {
        if (!intent.getBooleanExtra("open_note", false)) return null
        // Stable id also handles renamed day notes, edited dates and deleted notifications.
        intent.getStringExtra("note_id")?.let { id ->
            val note = Notes.find(c, id) ?: return null
            return NoteEditorRequest(note.target, note)
        }
        val groupId = intent.getIntExtra("open_group_id", -1).takeIf { it > 0 } ?: return null
        val date = runCatching { LocalDate.parse(intent.getStringExtra("note_date")) }.getOrNull() ?: return null
        val subject = intent.getStringExtra("note_subject")?.takeIf { it.isNotBlank() && it.length <= 900 }
            ?: return null
        val time = intent.getStringExtra("note_time")?.takeIf { it.length <= 32 } ?: return null
        val kind = intent.getStringExtra("note_kind")?.take(80) ?: return null
        val subgroup = intent.getStringExtra("note_subgroup")?.take(80) ?: return null
        val target = NoteTarget(groupId, date, subject, time, kind, subgroup,
            intent.getBooleanExtra("note_unique", false),
            if (intent.getStringExtra("note_scope") == "day") "day" else "lesson")
        val note = intent.getStringExtra("note_id")?.let { Notes.find(c, it) }
            ?.takeIf { it.target.identity == target.identity }
            ?: Notes.forDay(c, groupId, date).singleOrNull { it.target.slot == target.slot }
        return NoteEditorRequest(target, note)
    }
}
