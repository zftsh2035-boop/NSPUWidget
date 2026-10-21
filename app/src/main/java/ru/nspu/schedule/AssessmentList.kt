package ru.nspu.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal enum class AssessmentState(val label: String) {
    UPCOMING("Предстоит"), TODAY("Сегодня"), ONGOING("Идёт"), PAST("Прошёл"), UNDATED("Дата уточняется")
}

internal data class AssessmentEntry(val notice: Notice, val lesson: Lesson, val kind: String,
    val date: LocalDate?, val state: AssessmentState, val info: SiteParser.LessonInfo)

/** An assessment is not a result: elapsed time never means the student passed it. */
internal object AssessmentList {
    private val clock = DateTimeFormatter.ofPattern("H:mm")
    private fun time(value: String) = runCatching { LocalTime.parse(value.trim(), clock) }.getOrNull()

    fun state(lesson: Lesson, now: LocalDateTime): AssessmentState {
        val date = SiteParser.fixedDate(lesson) ?: return AssessmentState.UNDATED
        if (date < now.toLocalDate()) return AssessmentState.PAST
        if (date > now.toLocalDate()) return AssessmentState.UPCOMING
        val parts = lesson.time.split('-')
        val start = time(parts.first())
        val end = parts.getOrNull(1)?.let(::time)
        if (end != null && now.toLocalTime() >= end) return AssessmentState.PAST
        if (start != null && now.toLocalTime() < start) return AssessmentState.UPCOMING
        return if (start != null && end != null) AssessmentState.ONGOING else AssessmentState.TODAY
    }

    fun nextBoundary(lessons: List<Lesson>, now: LocalDateTime): LocalDateTime? = lessons
        .filter { SiteParser.specialKind(it.raw) != null }.flatMap { lesson ->
            val date = SiteParser.fixedDate(lesson) ?: return@flatMap emptyList()
            listOfNotNull(time(lesson.time.substringBefore('-'))?.let(date::atTime),
                lesson.time.substringAfter('-', "").takeIf { it.isNotBlank() }?.let(::time)?.let(date::atTime),
                date.plusDays(1).atStartOfDay())
        }.filter { it > now }.minOrNull()

    private fun occurrence(lesson: Lesson): String = listOf(
        SiteParser.fixedDate(lesson), lesson.day, lesson.time,
        lesson.raw.substringBefore(',').trim().lowercase().replace(Regex("\\s+"), " "),
        SiteParser.specialKind(lesson.raw), SiteParser.subgroupKey(lesson)
    ).joinToString("|")

    /** Retain elapsed occurrences even after the site removes its old calendar rows.
     * A confirmed new semester resets the archive; the phone's year change does not.
     * Missing future entries are removed, so a cancellation cannot remain upcoming.
     */
    fun merge(previous: List<Lesson>, fresh: List<Lesson>, now: LocalDateTime,
        newSemester: Boolean = false): List<Lesson> {
        val current = fresh.filter { SiteParser.specialKind(it.raw) != null }
        val keys = current.map(::occurrence).toSet()
        val retained = if (newSemester) emptyList() else previous.filter {
            SiteParser.specialKind(it.raw) != null && state(it, now) == AssessmentState.PAST && occurrence(it) !in keys
        }
        return (current + retained).distinctBy(::occurrence)
    }

    fun entries(lessons: List<Lesson>, now: LocalDateTime, termStart: LocalDate,
        subgroup: String = "Все", known: Map<String, Notice> = emptyMap()): List<AssessmentEntry> = lessons
        .filter { SiteParser.subgroupFits(it.raw, subgroup) }.mapNotNull { lesson ->
            val kind = SiteParser.specialKind(lesson.raw) ?: return@mapNotNull null
            val source = ImportantInfo.notices(listOf(lesson), now.toLocalDate(), termStart, includePast = true)
                .singleOrNull() ?: return@mapNotNull null
            AssessmentEntry(known[source.id] ?: source, lesson, kind, SiteParser.fixedDate(lesson),
                state(lesson, now), SiteParser.details(lesson, SiteParser.fixedDate(lesson) ?: now.toLocalDate(), termStart))
        }.distinctBy { it.notice.id }.sortedWith(compareBy<AssessmentEntry> { it.date ?: LocalDate.MAX }
            .thenBy { time(it.lesson.time.substringBefore('-')) ?: LocalTime.MAX })
}
