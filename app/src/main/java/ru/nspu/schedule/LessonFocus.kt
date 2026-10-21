package ru.nspu.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class FocusedLesson(val date: LocalDate, val lesson: Lesson, val ongoing: Boolean)

object LessonFocus {
    private val clock = DateTimeFormatter.ofPattern("H:mm")

    private fun bounds(date: LocalDate, lesson: Lesson): Pair<LocalDateTime, LocalDateTime>? {
        val parts = lesson.time.split('-')
        if (parts.size !in 1..2) return null
        return runCatching {
            Pair(date.atTime(LocalTime.parse(parts[0].trim(), clock)),
                date.atTime(LocalTime.parse(parts.getOrElse(1) { parts[0] }.trim(), clock)))
        }.getOrNull()
    }

    /** Current class wins; otherwise first upcoming class across subsequent teaching days. */
    fun choose(now: LocalDateTime, lessons: (LocalDate) -> List<Lesson>): FocusedLesson? {
        for (delta in 0..35) {
            val day = now.toLocalDate().plusDays(delta.toLong())
            val candidates = lessons(day).mapNotNull { l ->
                bounds(day, l)?.let { (start, end) -> Triple(l, start, end) }
            }.sortedBy { it.second }
            val running = candidates.firstOrNull { now >= it.second && now < it.third }
            if (running != null) return FocusedLesson(day, running.first, true)
            val next = candidates.firstOrNull { it.second > now }
            if (next != null) return FocusedLesson(day, next.first, false)
        }
        return null
    }

    fun matches(focus: FocusedLesson?, date: LocalDate, lesson: Lesson): Boolean =
        focus != null && focus.date == date && focus.lesson.time == lesson.time &&
            focus.lesson.raw == lesson.raw && focus.lesson.day == lesson.day

    /** Next time the live state can change, for scheduling a lightweight widget redraw. */
    fun transitionAt(now: LocalDateTime, focus: FocusedLesson?): LocalDateTime? {
        if (focus == null) return null
        val (start, end) = bounds(focus.date, focus.lesson) ?: return null
        return (if (focus.ongoing) end else start).takeIf { it > now }
    }
}
