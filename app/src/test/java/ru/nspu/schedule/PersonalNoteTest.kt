package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PersonalNoteTest {
    private val date = LocalDate.of(2026, 10, 20)
    private val target = NoteTarget(3328, date, "Алгебра", "8:30-10:00", "ПРАКТИКА", "all")
    private val note = PersonalNote("own-id", target, "Взять бинты\nи тетрадь", 1L)

    private fun match(vararg targets: NoteTarget, notes: List<PersonalNote> = listOf(note)) =
        NoteMatching.resolve(targets.toList(), notes)

    @Test fun noteBelongsToOneGroupDateKindAndSubgroup() {
        assertEquals(note, match(target)[target])
        assertTrue(match(target.copy(groupId = 9001)).isEmpty())
        assertTrue(match(target.copy(date = date.plusWeeks(1))).isEmpty())
        assertTrue(match(target.copy(kind = "ЛЕКЦИЯ")).isEmpty())
        assertTrue(match(target.copy(subgroup = "7")).isEmpty())
        assertTrue(match(target.copy(subject = "Физкультура")).isEmpty())
    }

    @Test fun roomTeacherWeekAndChangedFlagDoNotAffectTarget() {
        val first = Lesson(1, target.time, "Алгебра, Петров И. И., (п.з.: 1-16 нед.), а. 411")
        val replacement = first.copy(raw = "Алгебра, Иванов И. И., (п.з.: 1-18 нед.), а. 313", changed = true)
        val cards = prepareLessonCards(3328, date, listOf(first, replacement), date.minusWeeks(6),
            ScheduleLinks.Index(emptyList()), listOf(note))
        assertEquals(cards[first]?.noteTarget, cards[replacement]?.noteTarget)
        assertEquals(note, cards[replacement]?.note)
    }

    @Test fun uniqueSameDayTimeChangeKeepsNote() {
        val moved = target.copy(time = "10:20-11:50")
        assertEquals(note, match(moved)[moved])
    }

    @Test fun twoSameSubjectLessonsNeverInheritEachOthersNotes() {
        val second = target.copy(time = "10:20-11:50")
        val result = match(target, second)
        assertEquals(note, result[target])
        assertNull(result[second])
        assertTrue(match(second, target.copy(time = "12:10-13:40")).isEmpty())
    }

    @Test fun twoOldNotesAreAmbiguousAfterScheduleChanges() {
        val other = PersonalNote("other", target.copy(time = "10:20-11:50"), "Другая запись", 2L)
        assertTrue(match(target.copy(time = "12:10-13:40"), notes = listOf(note, other)).isEmpty())
        assertEquals(note, match(target, notes = listOf(note, other))[target])
    }

    @Test fun cancellingOneOfTwoLessonsDoesNotMoveItsNoteToTheOther() {
        val first = Lesson(1, "8:30-10:00", "Алгебра, Петров И. И., (п.з.: 1-16 нед.), а. 411")
        val second = first.copy(time = "10:20-11:50")
        val cards = prepareLessonCards(3328, date, listOf(first, second), date.minusWeeks(6),
            ScheduleLinks.Index(emptyList()), emptyList())
        val savedTarget = cards.getValue(first).noteTarget!!
        assertFalse(savedTarget.uniqueOnDay)
        val survivor = cards.getValue(second).noteTarget!!.copy(uniqueOnDay = true)
        assertTrue(match(survivor, notes = listOf(note.copy(target = savedTarget))).isEmpty())
    }

    @Test fun classroomNamedA7IsNotALanguageSubgroup() {
        assertEquals("all", SiteParser.subgroupKey(Lesson(1, target.time, "Алгебра, Петров, а. А7")))
        assertEquals("7", SiteParser.subgroupKey(Lesson(1, target.time, "Иностранный язык, А7, Норов, а. А8")))
    }

    @Test fun typographyAndLeadingZeroDoNotDuplicateAnOccurrence() {
        val formatted = target.copy(subject = "  АЛГЕБРА  ", time = "08:30–10:00")
        assertEquals(target.slot, formatted.slot)
        assertEquals(note, match(formatted)[formatted])
        assertEquals("Взять бинты и тетрадь", note.preview)
    }

    @Test fun languageSubgroupsStaySeparateAndCodesAreStable() {
        assertEquals("7", SiteParser.subgroupKey(Lesson(1, target.time, "Иностранный язык, А7, Норов")))
        assertEquals("7,8", SiteParser.subgroupKey(Lesson(1, target.time, "Иностранный язык, A8, А7, Норов")))
        val english = target.copy(subject = "Иностранный язык", subgroup = "7")
        val languageNote = note.copy(target = english)
        assertTrue(match(english.copy(subgroup = "8"), notes = listOf(languageNote)).isEmpty())
    }
}
