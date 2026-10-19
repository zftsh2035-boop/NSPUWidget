package ru.nspu.schedule

import java.time.LocalDate

/**
 * Explicit user selection must override the automatic upcoming-day focus,
 * including when the explicitly selected day is today.
 */
object DayNavigation {
    fun visible(today: LocalDate, selected: LocalDate?, automatic: LocalDate?): LocalDate =
        selected ?: automatic ?: today

    fun move(today: LocalDate, selected: LocalDate?, automatic: LocalDate?, days: Long): LocalDate =
        visible(today, selected, automatic).plusDays(days)
}
