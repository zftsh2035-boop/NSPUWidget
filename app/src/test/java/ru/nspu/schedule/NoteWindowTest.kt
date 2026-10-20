package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class NoteWindowTest {
    private val zone = ZoneId.of("Asia/Novosibirsk")
    private val day = LocalDate.of(2026, 10, 20)
    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun boundariesAreStartInclusiveEndExclusive() {
        val options = NoteOptions(at(day, 10), at(day, 12))
        assertFalse(options.visibleOn(day, at(day, 9), zone))
        assertTrue(options.visibleOn(day, at(day, 10), zone))
        assertFalse(options.visibleOn(day, at(day, 12), zone))
    }

    @Test fun calendarPreviewsOnlyDatesIntersectingWindow() {
        val options = NoteOptions(at(day, 10), at(day.plusDays(2), 0))
        val now = at(day.minusDays(1), 12)
        assertFalse(options.visibleOn(day.minusDays(1), now, zone))
        assertTrue(options.visibleOn(day, now, zone))
        assertTrue(options.visibleOn(day.plusDays(1), now, zone))
        assertFalse(options.visibleOn(day.plusDays(2), now, zone))
    }

    @Test fun midnightAndDaylightSavingUseCalendarDays() {
        val berlin = ZoneId.of("Europe/Berlin")
        val options = NoteOptions.forDay(LocalDate.of(2026, 3, 29), berlin)
        assertEquals(23 * 60 * 60 * 1000L, options.visibleUntil!! - options.visibleFrom!!)
        assertTrue(options.valid())
        assertFalse(options.copy(visibleUntil = options.visibleFrom).valid())
    }

    @Test fun dayNotesDoNotAttachToALessonWithTheSameTitle() {
        val target = NoteTarget(3328, day, "Алгебра", "8:30-10:00", "ПРАКТИКА", "all")
        val note = PersonalNote("day", target.copy(scope = "day"), "Тренинг", 1)
        assertTrue(NoteMatching.resolve(listOf(target), listOf(note)).isEmpty())
    }

    @Test fun remindersAndDisplayWindowsAreIndependent() {
        val options = NoteOptions.forDay(day, zone).copy(reminders = listOf(at(day.minusDays(1), 15), at(day, 8)))
        assertTrue(options.valid())
        assertFalse(options.copy(reminders = listOf(1L, 1L)).valid())
    }
}
