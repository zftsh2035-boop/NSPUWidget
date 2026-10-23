package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LessonWeeksTest {
    private val start=LocalDate.of(2026,9,1)
    @Test fun disjointWeeksAndSeparateTypesStayExactInFilteringAndLabels() {
        val lesson=Lesson(0,"8:30-10:00","Алгебра, Преподаватель (л.: 2,6-9 нед.; п.з.: 10-12 нед.)")
        val parts=LessonWeeks.parts(lesson.raw)
        assertEquals(setOf(2,6,7,8,9),parts[0].weeks)
        assertEquals(setOf(10,11,12),parts[1].weeks)
        assertEquals("л.: 2, 6–9 нед. · п.з.: 10–12 нед.",LessonWeeks.label(lesson,start))
        assertFalse(SiteParser.weekMatches(lesson.raw,3))
        assertTrue(SiteParser.weekMatches(lesson.raw,10))
        assertFalse(SiteParser.weekMatches(lesson.raw,13))
    }
    @Test fun practiceAnnotationsAndOutsideParenthesesUseTheSameWeekRules() {
        for (raw in listOf("Предмет, (практика: 3-14 нед. *)","Предмет, (л.): 3-14 нед.")) {
            assertEquals(setOf(3,4,5,6,7,8,9,10,11,12,13,14),LessonWeeks.parts(raw).single().weeks)
            assertFalse(SiteParser.weekMatches(raw,2))
            assertTrue(SiteParser.weekMatches(raw,14))
        }
    }
    @Test fun aFreeDayStillExposesTheNextClassAndTheFollowingWeeksPublishedSchedule() {
        val lesson=Lesson(0,"8:30-10:00","Алгебра, И.И.Петров (л.: 9-12 нед.)")
        val sunday=LocalDate.of(2026,10,25)
        val entry=SubjectSchedule.entries(listOf(lesson),sunday,start).single()
        assertEquals(LocalDate.of(2026,10,26),entry.nextDate)
        assertTrue(entry.variants.single().contains("9–12 нед."))
        assertTrue(entry.nextWeek.contains("Пн 26.10 · 8:30-10:00"))
    }
    @Test fun subgroupParityAndTeachingStartAreRespectedInTheWholeSubjectList() {
        val monday=Lesson(0,"8:30-10:00","Иностранный язык, А7, Преподаватель (л.з.: 1-16 нед.)",parity=2)
        val other=monday.copy(raw=monday.raw.replace("А7","А8").replace("Иностранный","Немецкий"))
        val entry=SubjectSchedule.entries(listOf(monday,other),LocalDate.of(2026,10,25),start,"А7",LocalDate.of(2026,10,26)).single()
        assertEquals(LocalDate.of(2026,11,2),entry.nextDate)
        assertEquals("На следующей неделе пар нет",entry.nextWeek)
        assertTrue(entry.variants.single().contains("знаменатель"))
    }
    @Test fun aDatedClassShowsItsActualDateAndUnknownWeeksAreNeverInvented() {
        val lesson=Lesson(0,"8:30","Экономика, Преподаватель (л.)",date=LocalDate.of(2026,10,26))
        assertEquals("26.10.2026 · 9-я неделя",LessonWeeks.label(lesson,start))
        assertEquals("Недели не указаны на сайте",LessonWeeks.label(lesson.copy(date=null),start))
        val entry=SubjectSchedule.entries(listOf(lesson),LocalDate.of(2026,10,25),start).single()
        assertEquals(LocalDate.of(2026,10,26),entry.nextDate)
        assertNull(SubjectSchedule.entries(listOf(lesson),LocalDate.of(2026,10,27),start).single().nextDate)
    }
}
