package ru.nspu.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

internal data class WeekPart(val kind: String?, val weeks: Set<Int>)

/** Shared by filtering and presentation: gaps in a list of weeks stay gaps. */
internal object LessonWeeks {
    private val range = Regex("""(\d{1,2})(?:\s*[-–—]\s*(\d{1,2}))?""")
    private val note = Regex("""(?iu)(?:(л\s*\.\s*з\s*\.|п\s*\.\s*з\s*\.|л\s*\.|практика)\s*\)?\s*:\s*)?(\d{1,2}(?:\s*[-–—]\s*\d{1,2})?(?:\s*,\s*\d{1,2}(?:\s*[-–—]\s*\d{1,2})?)*)\s*нед(?:\.|ел[а-яё]*)""")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun parts(raw: String): List<WeekPart> = note.findAll(raw).mapNotNull { match ->
        val weeks = range.findAll(match.groupValues[2]).flatMap {
            val first = it.groupValues[1].toInt()
            val last = it.groupValues[2].toIntOrNull() ?: first
            (first..last).filter { n -> n in 1..53 }
        }.toSet()
        weeks.takeIf { it.isNotEmpty() }?.let {
            WeekPart(match.groupValues[1].replace(Regex("\\s+"), "").lowercase().ifBlank { null }, it)
        }
    }.toList()

    fun format(weeks: Set<Int>): String {
        val sorted = weeks.sorted()
        val result = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            val first = sorted[i]
            var last = first
            while (i + 1 < sorted.size && sorted[i + 1] == last + 1) last = sorted[++i]
            result += if (first == last) "$first" else "$first–$last"
            i++
        }
        return result.joinToString(", ")
    }

    fun label(lesson: Lesson, termStart: LocalDate? = null): String {
        SiteParser.fixedDate(lesson)?.let { date ->
            val week = termStart?.let { SiteParser.academicWeek(date, it) }?.takeIf { it in 1..53 }
            return date.format(dateFormat) + (week?.let { " · $it-я неделя" } ?: "")
        }
        val parity = when (lesson.parity) { 1 -> "числитель"; 2 -> "знаменатель"; else -> "" }
        val parts = parts(lesson.raw)
        val text = if (parts.isEmpty()) "Недели не указаны на сайте" else parts.joinToString(" · ") {
            (it.kind?.let { kind -> "$kind: " } ?: "Недели: ") + format(it.weeks) + " нед."
        }
        return listOf(text, parity).filter(String::isNotBlank).joinToString(" · ")
    }
}

internal data class SubjectScheduleEntry(val subject: String, val variants: List<String>,
    val nextDate: LocalDate?, val nextWeek: String)

/** The complete downloaded semester remains accessible even on a day without lessons. */
internal object SubjectSchedule {
    private val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM")
    fun entries(lessons: List<Lesson>, date: LocalDate, start: LocalDate,
        subgroup: String = "Все", teachingStart: LocalDate? = null): List<SubjectScheduleEntry> {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1)
        fun occurs(l: Lesson, day: LocalDate) = (SiteParser.fixedDate(l)?.dayOfWeek?.value?.minus(1) ?: l.day) == day.dayOfWeek.value - 1 &&
            (teachingStart == null || day >= teachingStart || SiteParser.fixedDate(l) != null) &&
            SiteParser.applicable(l, day, start)
        return lessons.filter { SiteParser.subgroupFits(it.raw, subgroup) }.groupBy {
            it.raw.substringBefore(',').trim()
        }.map { (subject, variants) ->
            val next = (0L..370L).asSequence().map(date::plusDays).firstOrNull { day -> variants.any { occurs(it, day) } }
            val nextWeek = (0L..6L).map(monday::plusDays).flatMap { day ->
                variants.filter { occurs(it, day) }.map { "${days[day.dayOfWeek.value - 1]} ${day.format(dateFormat)} · ${it.time.ifBlank { "время не указано" }}" }
            }.distinct()
            SubjectScheduleEntry(subject, variants.map { l ->
                val info = SiteParser.details(l, SiteParser.fixedDate(l) ?: date, start)
                val day = SiteParser.fixedDate(l)?.let { it.format(dateFormat) } ?: days.getOrNull(l.day).orEmpty()
                listOf("$day ${l.time}".trim(), SiteParser.eventLabel(info.kind, l.raw), LessonWeeks.label(l, start))
                    .filter(String::isNotBlank).joinToString(" · ")
            }.distinct(), next, if (nextWeek.isEmpty()) "На следующей неделе пар нет" else
                "На следующей неделе: " + nextWeek.joinToString("; "))
        }.sortedBy { it.subject.lowercase() }
    }
}
