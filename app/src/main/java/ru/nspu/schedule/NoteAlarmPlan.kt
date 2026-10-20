package ru.nspu.schedule

internal data class NoteAlarmPlan(val due: List<Pair<PersonalNote, Long>>, val nextReminder: Long?,
    val nextDisplayBoundary: Long?) {
    companion object {
        fun of(notes: List<PersonalNote>, delivered: Set<Pair<String, Long>>, now: Long): NoteAlarmPlan {
            val pending = notes.flatMap { note -> note.options.reminders.map { note to it } }
                .filter { (it.first.id to it.second) !in delivered }
            return NoteAlarmPlan(pending.filter { it.second <= now },
                pending.asSequence().map { it.second }.filter { it > now }.minOrNull(),
                notes.asSequence().flatMap { sequenceOf(it.options.visibleFrom, it.options.visibleUntil) }
                    .filterNotNull().filter { it > now }.minOrNull())
        }
    }
}
