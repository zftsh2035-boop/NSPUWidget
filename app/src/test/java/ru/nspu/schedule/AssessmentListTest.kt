package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AssessmentListTest {
    private val start = LocalDate.of(2026,9,1)
    private val credit = Lesson(0,"10:10-11:40","Педагогика, И.И.Иванов (зач.), а. 404",
        date = LocalDate.of(2026,10,26))

    @Test fun statusChangesAtStartAndEndWithoutInventingAResult() {
        assertEquals(AssessmentState.UPCOMING, AssessmentList.state(credit, LocalDateTime.of(2026,10,26,10,9)))
        assertEquals(AssessmentState.ONGOING, AssessmentList.state(credit, LocalDateTime.of(2026,10,26,10,10)))
        assertEquals(AssessmentState.PAST, AssessmentList.state(credit, LocalDateTime.of(2026,10,26,11,40)))
        assertEquals(LocalDateTime.of(2026,10,26,11,40), AssessmentList.nextBoundary(listOf(credit), LocalDateTime.of(2026,10,26,10,30)))
    }
    @Test fun aStartOnlyExamHasNoInventedEndTime() {
        val exam = credit.copy(time="8:30",raw="Педагогика, И.И.Иванов (экз.), а. 404")
        assertEquals(AssessmentState.TODAY, AssessmentList.state(exam, LocalDateTime.of(2026,10,26,15,0)))
        assertEquals(AssessmentState.PAST, AssessmentList.state(exam, LocalDateTime.of(2026,10,27,0,0)))
        assertEquals(LocalDateTime.of(2026,10,27,0,0), AssessmentList.nextBoundary(listOf(exam), LocalDateTime.of(2026,10,26,15,0)))
    }
    @Test fun elapsedEventsSurviveRemovalFromTheWebsiteUntilTheNextPublishedSemester() {
        val now = LocalDateTime.of(2026,10,27,12,0)
        val ordinary = credit.copy(raw="Математика, Преподаватель (л.)")
        val retained = AssessmentList.merge(listOf(credit,ordinary),emptyList(),now)
        assertEquals(listOf(credit),retained)
        assertEquals(AssessmentState.PAST,AssessmentList.entries(retained,now,start).single().state)
        assertTrue(AssessmentList.merge(retained,emptyList(),LocalDateTime.of(2027,1,10,12,0)).isNotEmpty())
        assertTrue(AssessmentList.merge(retained,emptyList(),now,newSemester=true).isEmpty())
    }
    @Test fun cancelledFutureEventsDisappearAndRoomEditsReplaceTheSameOccurrence() {
        val before = LocalDateTime.of(2026,10,22,12,0)
        assertTrue(AssessmentList.merge(listOf(credit),emptyList(),before).isEmpty())
        val edited = credit.copy(raw=credit.raw.replace("404","417"))
        assertEquals(listOf(edited),AssessmentList.merge(listOf(credit),listOf(edited),LocalDateTime.of(2026,10,27,12,0)))
    }
    @Test fun undatedAssessmentIsRetainedAsUndatedAndAmbiguousDatesAreNotGuessed() {
        val undated = credit.copy(date=null)
        val ambiguous = undated.copy(raw=undated.raw + ", 26.10.2026 или 27.10.2026")
        for (lesson in listOf(undated, ambiguous)) {
            val entry = AssessmentList.entries(listOf(lesson),LocalDateTime.of(2026,10,22,12,0),start).single()
            assertEquals(AssessmentState.UNDATED,entry.state)
            assertNull(entry.date)
            assertTrue(ImportantInfo.isAssessment(entry.notice))
        }
        assertFalse(ImportantInfo.isAssessment(Notice("banner","Уважаемые студенты! Зачёт 26.10.2026")))
    }
    @Test fun sameDayEntriesSortByClockRatherThanLexicographicTime() {
        val entries = AssessmentList.entries(listOf(credit,credit.copy(time="8:30-10:00")),
            LocalDateTime.of(2026,10,22,12,0),start)
        assertEquals(listOf("8:30-10:00","10:10-11:40"),entries.map { it.lesson.time })
    }
}
