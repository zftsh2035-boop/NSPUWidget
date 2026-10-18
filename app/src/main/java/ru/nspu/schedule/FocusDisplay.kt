package ru.nspu.schedule

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class FocusTiming(val label: String, val progress: Float, val remainingMinutes: Long)

/** Shows an informative timer in place of "СЛЕДУЮЩАЯ"/"ИДЁТ СЕЙЧАС". */
object FocusDisplay {
    fun of(now: LocalDateTime, focus: FocusedLesson): FocusTiming? {
        val times = focus.lesson.time.split('-')
        if (times.size != 2) return null
        val start = runCatching { focus.date.atTime(LocalTime.parse(times[0].trim(), DateTimeFormatter.ofPattern("H:mm"))) }.getOrNull() ?: return null
        val end = runCatching { focus.date.atTime(LocalTime.parse(times[1].trim(), DateTimeFormatter.ofPattern("H:mm"))) }.getOrNull() ?: return null
        if (end <= start) return null
        val underway = now >= start && now < end
        val remaining = if (underway) Duration.between(now, end) else Duration.between(now, start)
        val minutes = ((remaining.seconds + 59).coerceAtLeast(0) / 60)
        val timeLabel = when {
            minutes >= 24 * 60 -> {
                val days = minutes / (24 * 60)
                val hours = minutes % (24 * 60) / 60
                "$days д $hours ч"
            }
            minutes >= 60 -> "${minutes / 60} ч ${minutes % 60} мин"
            else -> "$minutes мин"
        }
        val duration = Duration.between(start, end).seconds.toFloat()
        val elapsed = Duration.between(start, now).seconds.toFloat()
        val fraction = if (underway) (elapsed / duration).coerceIn(0f, 1f) else 0f
        return FocusTiming(if (underway) "Осталось $timeLabel" else "Через $timeLabel", fraction, minutes)
    }
}
