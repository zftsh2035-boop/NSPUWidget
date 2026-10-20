package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LessonPresentationTest {
    private val date = LocalDate.of(2026, 10, 19)
    private val term = LocalDate.of(2026, 9, 1)
    private val links = ScheduleLinks.Index(listOf(
        ScheduleLink(ScheduleLinkKind.TEACHER, "Норов Шахбоз Абдулбадеъович",
            "https://schedule.nspu.ru/teacher_shedule.php?id=452"),
        ScheduleLink(ScheduleLinkKind.ROOM, "ауд. 411",
            "https://schedule.nspu.ru/room_shedule.php?id=31")))

    @Test fun preparedCardKeepsFullTeacherAndActualNavigationTargets() {
        val card = Lesson(0, "8:30-10:00",
            "Иностранный язык, А7, Норов Шахбоз Абдулбадеъович, (п.з.: 1-16 нед.), а. 411")
            .present(date, term, links)
        assertEquals("Иностранный язык", card.subject)
        assertEquals("Норов Шахбоз Абдулбадеъович", card.teacher)
        assertEquals("411", card.room)
        assertEquals("п.з.", card.kindLabel)
        assertEquals("8:30", card.start)
        assertEquals("10:00", card.end)
        assertEquals(OfficialSchedulePage.Kind.TEACHER, card.teacherPage?.kind)
        assertEquals(452, card.teacherPage?.id)
        assertEquals(OfficialSchedulePage.Kind.ROOM, card.roomPage?.kind)
        assertEquals(31, card.roomPage?.id)
    }

    @Test fun unavailableReferencesStayPlainTextAndNeverGuessIds() {
        val card = Lesson(0, "12:10-13:40", "Алгебра, Петров И. И., а. 405")
            .present(date, term, links)
        assertEquals("Петров И. И.", card.teacher)
        assertEquals("405", card.room)
        assertNull(card.teacherPage)
        assertNull(card.roomPage)
    }

    @Test fun indexedResolutionPreservesPunctuationAndYoNormalization() {
        val index = ScheduleLinks.Index(listOf(
            ScheduleLink(ScheduleLinkKind.TEACHER, "Пётр И. И.",
                "https://schedule.nspu.ru/teacher_shedule.php?id=9"),
            ScheduleLink(ScheduleLinkKind.ROOM, "Аудитория 3п",
                "https://schedule.nspu.ru/room_shedule.php?id=8")))
        assertEquals(9, Lesson(0, "8:30-10:00", "Алгебра, Петр И.И., а. 3п")
            .present(date, term, index).teacherPage?.id)
        assertEquals(8, index.room("3п")?.let { OfficialSchedulePage.fromUrl(it.url)?.id })
        assertNull(index.room("—"))
    }
}
