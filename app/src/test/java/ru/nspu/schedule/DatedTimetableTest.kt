package ru.nspu.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DatedTimetableTest {
    private val date = LocalDate.of(2026,10,26)
    private val code = "4.021.2.26"
    private fun source(body: String) = Jsoup.parse("<h2>Группа $code Образовательный менеджмент</h2><table class='rasp_table'>$body</table>")

    @Test fun futureExtramuralCalendarNeverBecomesTheSameWeekdayInAnotherWeek() {
        val doc = source("<tr><td rowspan='2'>26 октября 2026<br>Понедельник</td><td>8:30-10:00</td><td>Предмет, Иванов (л.), а. 404</td></tr><tr><td>10:10-11:40</td><td>Предмет, Иванов (п.з.), а. 404</td></tr>")
        val parsed = GroupTimetableParser.parse(doc,code,true).lessons
        assertEquals(2,parsed.size)
        assertTrue(parsed.all { it.date == date && SiteParser.applicable(it,date) })
        for (other in listOf(date.minusWeeks(1),date.plusWeeks(1),LocalDate.of(2026,10,22)))
            assertTrue(parsed.none { SiteParser.applicable(it,other) })
        assertEquals(listOf("ЛЕКЦИЯ","ПРАКТИКА"),parsed.map { SiteParser.details(it,date).kind })
    }
    @Test fun multipleClocksInARepairedRowKeepTheirOwnTimesAndDescriptions() {
        val doc = source("<tr><td>26 октября 2026 Понедельник</td><td>8:30-10:00</td><td>Предмет 1, Иванов (л.)</td><td>10:10-11:40</td><td>Предмет 2, Петров <b>(зач.)</b></td></tr>")
        val parsed = GroupTimetableParser.parse(doc,code,true).lessons
        assertEquals(listOf("8:30-10:00","10:10-11:40"),parsed.map { it.time })
        assertEquals(listOf("Предмет 1","Предмет 2"),parsed.map { SiteParser.details(it,date).subject })
        assertEquals("ЗАЧЁТ",SiteParser.details(parsed.last(),date).kind)
    }
    @Test fun numericCalendarDatesAndPlainTypesUseTheSameRulesOnBothScreens() {
        val doc = source("<tr><td>26.10.26 Понедельник</td><td>8:30-10:00</td><td><strong>Предмет</strong>, Иванов (л.з.)</td></tr>")
        val lesson = GroupTimetableParser.parse(doc,code,true).lessons.single()
        val native = PublicTimetableParser.parse(doc,OfficialSchedulePage(OfficialSchedulePage.Kind.EXTRAMURAL_GROUP,2424,code),0,date).sessions.single()
        assertEquals(date,lesson.date)
        assertEquals("ЛАБОРАТОРНАЯ",SiteParser.details(lesson,date).kind)
        assertEquals(SiteParser.details(lesson,date).kind,native.kind)
        assertNull(SiteParser.specialKind(lesson.raw))
    }
    @Test fun aFutureSemesterCannotLeakIntoThePrecedingWeek() {
        val lesson = Lesson(2,"8:30-10:00","Предмет, Иванов (л.: 1-12 нед.)")
        assertEquals(0,SiteParser.academicWeek(LocalDate.of(2026,10,21),date))
        assertFalse(SiteParser.applicable(lesson,LocalDate.of(2026,10,21),date))
        assertTrue(SiteParser.applicable(lesson,LocalDate.of(2026,10,28),date))
    }
    @Test fun overlappingWebsiteIdsHaveIndependentStorageIdentities() {
        val full = GroupIdentity.catalogueId(3170,false)
        val extra = GroupIdentity.catalogueId(3170,true)
        assertNotEquals(full,extra)
        assertEquals(3170,GroupIdentity.siteId(full))
        assertEquals(3170,GroupIdentity.siteId(extra))
        assertTrue(GroupIdentity.extramural(extra))
        assertFalse(GroupIdentity.extramural(GroupIdentity.alternateFullTimeId(full)))
    }
}
