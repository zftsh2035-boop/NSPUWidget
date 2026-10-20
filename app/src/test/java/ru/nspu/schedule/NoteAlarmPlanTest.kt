package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class NoteAlarmPlanTest {
    private val note = PersonalNote("first", NoteTarget.day(3328, LocalDate.of(2026, 10, 20)),
        "Тренинг", 0, NoteOptions(150, 400, listOf(100, 200, 300)))
    @Test fun multipleRemindersAdvanceIndividually() {
        val first = NoteAlarmPlan.of(listOf(note), emptySet(), 100)
        assertEquals(listOf(note to 100L), first.due)
        assertEquals(200L, first.nextReminder)
        assertEquals(150L, first.nextDisplayBoundary)
        val next = NoteAlarmPlan.of(listOf(note), setOf(note.id to 100L), 200)
        assertEquals(listOf(note to 200L), next.due)
        assertEquals(300L, next.nextReminder)
    }
    @Test fun editingTextOrRestartingDoesNotReplayDeliveredReminders() {
        val edited = note.copy(text = "Изменённый текст")
        val plan = NoteAlarmPlan.of(listOf(edited), setOf(note.id to 100L, note.id to 200L), 201)
        assertTrue(plan.due.isEmpty())
        assertEquals(300L, plan.nextReminder)
    }
    @Test fun simultaneousNotesStayIndependent() {
        val second = note.copy(id = "second")
        val plan = NoteAlarmPlan.of(listOf(note, second), setOf(note.id to 100L), 100)
        assertEquals(listOf(second to 100L), plan.due)
    }
    @Test fun removingANoteCancelsItsRemindersAndDisplayChanges() {
        val plan = NoteAlarmPlan.of(emptyList(), setOf(note.id to 100L), 101)
        assertTrue(plan.due.isEmpty()); assertNull(plan.nextReminder); assertNull(plan.nextDisplayBoundary)
    }
    @Test fun clockMovingBackDoesNotScheduleAnAlreadyDeliveredTime() {
        val plan = NoteAlarmPlan.of(listOf(note), setOf(note.id to 100L), 99)
        assertEquals(200L, plan.nextReminder)
    }
}
