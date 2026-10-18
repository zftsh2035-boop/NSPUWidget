package ru.nspu.schedule

/** A window is a whole missing period BETWEEN two known scheduled lessons.
 * It is never an ordinary 10/30-minute break, or time before/after classes.
 */
sealed class DayRow {
    data class LessonRow(val lesson: Lesson) : DayRow()
    data class WindowRow(val start: String, val end: String, val periods: Int) : DayRow() {
        val heading: String get() = if (periods == 1) "Окно" else "$periods окна"
        val message: String get() = if (periods == 1) "Нет пары. Может, по кофе? ☕"
            else "Свободное время. Может, по кофе? ☕"
        val time: String get() = "$start–$end"
    }
}

object DayTimeline {
    // Official standard bell slots used by the university, not an arbitrary time-gap threshold.
    private val bellTimes = listOf(
        "8:30" to "10:00",
        "10:10" to "11:40",
        "12:10" to "13:40",
        "14:10" to "15:40",
        "15:50" to "17:20",
        "17:30" to "19:00",
        "19:10" to "20:40",
        "20:50" to "22:20"
    )

    fun build(lessons: List<Lesson>): List<DayRow> {
        if (lessons.isEmpty()) return emptyList()
        fun order(lesson: Lesson): Int {
            val start = lesson.time.substringBefore('-').trim()
            val normalized = start.split(':').mapNotNull { it.toIntOrNull() }
            return if (normalized.size == 2) normalized[0] * 60 + normalized[1] else Int.MAX_VALUE
        }
        fun slot(lesson: Lesson): Int {
            val value = order(lesson)
            return bellTimes.indexOfFirst { pair ->
                val parts = pair.first.split(':')
                parts[0].toInt() * 60 + parts[1].toInt() == value
            }
        }
        val sorted = lessons.sortedWith(compareBy<Lesson> { order(it) }.thenBy { it.raw })
        val result = mutableListOf<DayRow>()
        var previousSlot = -1
        for (lesson in sorted) {
            val currentSlot = slot(lesson)
            // Only insert an "окно" where both neighboring lesson slots are recognized.
            if (previousSlot >= 0 && currentSlot > previousSlot + 1) {
                val from = previousSlot + 1
                val to = currentSlot - 1
                result.add(DayRow.WindowRow(bellTimes[from].first,
                    bellTimes[to].second, to - from + 1))
            }
            result.add(DayRow.LessonRow(lesson))
            if (currentSlot >= 0) previousSlot = currentSlot
            else previousSlot = -1
        }
        return result
    }
}
