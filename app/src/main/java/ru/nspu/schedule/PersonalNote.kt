package ru.nspu.schedule

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A dated occurrence, independent of the downloaded HTML, room and teacher. */
@Immutable
internal data class NoteTarget(
    val groupId: Int,
    val date: LocalDate,
    val subject: String,
    val time: String,
    val kind: String,
    val subgroup: String,
    val uniqueOnDay: Boolean = true,
    val scope: String = "lesson"
) {
    val isDay = scope == "day"
    val subjectKey = normalize(subject)
    val identity = listOf(groupId.toString(), date.toString(), subjectKey, kind, subgroup)
    val slot = identity + normalizeTime(time)

    companion object {
        private val spaces = Regex("\\s+")
        private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT)
            .replace('ё', 'е').replace(spaces, " ")
        private fun normalizeTime(value: String) = value.replace('–', '-').replace('—', '-')
            .split('-').joinToString("-") { it.trim().padStart(5, '0') }

        fun of(groupId: Int, card: LessonPresentation) = NoteTarget(groupId, card.date,
            card.subject, card.lesson.time, card.kind, SiteParser.subgroupKey(card.lesson))

        fun day(groupId: Int, date: LocalDate) = NoteTarget(groupId, date,
            "Заметка на день", "", "", "", scope = "day")
    }
}

@Immutable
internal data class PersonalNote(val id: String, val target: NoteTarget, val text: String,
    val updatedAt: Long, val options: NoteOptions = NoteOptions()) {
    val preview = text.trim().replace(previewSpaces, " ")
    companion object {
        const val MAX_LENGTH = 20000
        private val previewSpaces = Regex("\\s+")
    }
}

@Immutable
internal data class NoteOptions(val visibleFrom: Long? = null, val visibleUntil: Long? = null,
    val reminders: List<Long> = emptyList()) {
    fun valid() = (visibleFrom == null || visibleUntil == null || visibleUntil > visibleFrom) &&
        reminders.size <= 32 && reminders.distinct().size == reminders.size

    /** Other calendar pages preview the window; today's page observes the exact time. */
    fun visibleOn(date: LocalDate, now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (date == today) (visibleFrom == null || now >= visibleFrom) &&
            (visibleUntil == null || now < visibleUntil)
        else (visibleFrom == null || visibleFrom < end) && (visibleUntil == null || visibleUntil > start)
    }

    companion object {
        fun forDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = NoteOptions(
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }
}

internal data class NoteDraft(val target: NoteTarget, val text: String, val options: NoteOptions)

@Immutable
internal data class NoteEditorRequest(val target: NoteTarget, val note: PersonalNote? = null)

/** Never guess between two same-subject lessons. A unique time change on that day is safe. */
internal object NoteMatching {
    fun resolve(targets: List<NoteTarget>, notes: List<PersonalNote>): Map<NoteTarget, PersonalNote> {
        val notesByIdentity = notes.filterNot { it.target.isDay }.groupBy { it.target.identity }
        val occurrences = targets.groupingBy { it.identity }.eachCount()
        return targets.mapNotNull { target ->
            val candidates = notesByIdentity[target.identity].orEmpty()
            val exact = candidates.singleOrNull { it.target.slot == target.slot }
            val moved = if (occurrences[target.identity] == 1)
                candidates.singleOrNull()?.takeIf { it.target.uniqueOnDay } else null
            (exact ?: moved)?.let { target to it }
        }.toMap()
    }
}

/** One background pass supplies both app rows and RemoteViews; rows never read storage. */
internal fun prepareLessonCards(groupId: Int, date: LocalDate, lessons: List<Lesson>,
    termStart: LocalDate, links: ScheduleLinks.Index, notes: List<PersonalNote>): Map<Lesson, LessonPresentation> {
    val cards = lessons.map { it.present(date, termStart, links) }
    val initialTargets = cards.map { NoteTarget.of(groupId, it) }
    val counts = initialTargets.groupingBy { it.identity }.eachCount()
    val targets = initialTargets.map { it.copy(uniqueOnDay = counts[it.identity] == 1) }
    val matches = NoteMatching.resolve(targets, notes)
    return cards.zip(targets).associate { (card, target) ->
        card.lesson to card.copy(noteTarget = target, note = matches[target])
    }
}
