package ru.nspu.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Reproductions from actual room/teacher HTML, including malformed row tags. */
class PublicTimetableRegressionTest {
    private val today = LocalDate.of(2026, 10, 22)
    private fun captured(name: String, kind: OfficialSchedulePage.Kind, id: Int): PublicTimetable {
        val page = OfficialSchedulePage(kind, id, name)
        val html = checkNotNull(javaClass.getResourceAsStream("/public/$name.html"))
            .bufferedReader().use { it.readText() }
        return PublicTimetableParser.parse(Jsoup.parse(html, page.url), page, 0, today)
    }

    @Test fun room218KeepsPublishedClassesEvenWhenEveryClockCellIsEmpty() {
        val room = captured("room-596", OfficialSchedulePage.Kind.ROOM, 596)
        val monday = room.dated(PublicSection.DATED).filter { it.date == LocalDate.of(2026, 10, 26) }
        assertEquals(4, monday.size)
        assertTrue(monday.all { it.time.isBlank() })
        assertEquals(2, monday.count { it.subject == "Педагогическая психология" })
        assertTrue(monday.first().links.any { it.url.endsWith("zgroup_shedule.php?id=2516") })
        assertTrue(room.byDay(0).isEmpty())
        assertEquals(PublicSection.DATED, room.openingSection(PublicSection.WEEKLY))
    }

    @Test fun actualTeacherMorningLessonPrecedesTenOClockOnTheSameDate() {
        val teacher = captured("teacher-2049", OfficialSchedulePage.Kind.TEACHER, 2049)
        val monday = teacher.dated(PublicSection.DATED).filter { it.date == LocalDate.of(2026, 11, 9) }
        assertEquals(listOf("8:30-10:00", "10:10-11:40"), monday.map { it.time })
        assertEquals("Экономика образования", monday.first().subject)
    }

    @Test fun multipleGroupCodesInsideOneHyperlinkCannotBecomeExtraLessonsOrLoseTheirTime() {
        val teacher = captured("teacher-289", OfficialSchedulePage.Kind.TEACHER, 289)
        val monday = teacher.dated(PublicSection.DATED).filter { it.date == LocalDate.of(2026, 11, 30) }
        assertEquals(2, monday.size)
        assertTrue(monday[0].subject.startsWith("Педагогическая практика"))
        assertTrue(monday[1].subject.endsWith("сопровождение обучающихся с ОВЗ)"))
        assertEquals("13:00", monday[1].time)
        for (event in monday) assertTrue(event.links.any { it.label.contains("3.029.1.24; 3.029.3.24") })
    }

    @Test fun clockOrderHandlesPaddedUnpaddedSingleTimesAndUnknownTimes() {
        val date = LocalDate.of(2026, 10, 26)
        val events = listOf("17:30-19:00", "8:30-10:00", "10:10", "08:00-09:00", "", "8.15")
            .map { PublicClass(it, it, it, "ЗАНЯТИЕ", emptyList(), date = date, section = PublicSection.EXAMS) }
        val timetable = PublicTimetable("", 8, today, events)
        assertEquals(listOf("08:00-09:00", "8.15", "8:30-10:00", "10:10", "17:30-19:00", ""),
            timetable.dated(PublicSection.EXAMS).map { it.time })
        val weekly = timetable.copy(sessions = events.map { it.copy(section = PublicSection.WEEKLY) })
        assertEquals(timetable.dated(PublicSection.EXAMS).map { it.time }, weekly.byDay(0).map { it.time })
    }

    @Test fun repairedRowCanMixBlankAndKnownClocksWithoutLosingOrDuplicatingDescriptions() {
        val html = """<table class="rasp_table"><tr><td rowspan="3">26 октября 2026</td>
          <td></td><td>Математика, (л.)</td></tr>
          <td>10:10-11:40</td><td>Физика, (п.з.)</td>
          <td></td><td>Математика, (л.)</td></tr></table>"""
        val parsed = PublicTimetableParser.parse(Jsoup.parse(html),
            OfficialSchedulePage(OfficialSchedulePage.Kind.ROOM, 596, "218"), 0, today)
        assertEquals(3, parsed.sessions.size)
        assertEquals(listOf("10:10-11:40", "", ""), parsed.dated(PublicSection.DATED).map { it.time })
        assertEquals(2, parsed.sessions.count { it.subject == "Математика" })
    }

    @Test fun anExplicitStartStillWorksWhenTheClockColumnIsBlank() {
        val html = """<table class="rasp_table"><tr><td>26 октября 2026</td><td></td>
          <td>Математика, (зач.), Начало — 13.30</td></tr></table>"""
        val parsed = PublicTimetableParser.parse(Jsoup.parse(html),
            OfficialSchedulePage(OfficialSchedulePage.Kind.ROOM, 596, "218"), 0, today)
        assertEquals("13:30", parsed.sessions.single().time)
    }

    @Test fun aPublishedPreferredSectionIsKeptAndEmptyPagesKeepTheirDefault() {
        val weekly = PublicClass("8:30", "Математика", "Математика", "ЛЕКЦИЯ", emptyList())
        val dated = weekly.copy(section = PublicSection.DATED, date = today)
        val timetable = PublicTimetable("", 8, today, listOf(weekly, dated))
        assertEquals(PublicSection.WEEKLY, timetable.openingSection(PublicSection.WEEKLY))
        assertEquals(PublicSection.DATED, timetable.openingSection(PublicSection.DATED))
        assertEquals(PublicSection.EXAMS, timetable.copy(sessions = emptyList()).openingSection(PublicSection.EXAMS))
    }
}
