package ru.nspu.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ImportantRevisionTest {
    private val start = LocalDate.of(2026,9,1)
    private val now = LocalDateTime.of(2026,10,23,12,0)

    @Test fun capturedGroupHasTwentyRetakesIncludingPsychologyAndCourseworkWithTheirKinds() {
        val html = javaClass.getResource("/important/group-2800.html")!!.readText()
        val page = GroupTimetableParser.parse(Jsoup.parse(html),"3.031.2.24",today=now.toLocalDate())
        val retakes = AssessmentList.entries(page.lessons,now,start).filter { it.isRetake }
        assertEquals(20,retakes.size)
        assertTrue(retakes.all { it.state == AssessmentState.PAST && it.kind != "ЗАНЯТИЕ" })
        val psychology = retakes.single { it.lesson.raw.contains("Психология, проф.Андриенко") }
        assertEquals("ЭКЗАМЕН",psychology.kind)
        assertEquals("ПЕРЕСДАЧА · ЭКЗАМЕН",psychology.kindLabel)
        assertEquals("406",psychology.info.room)
        assertEquals(LocalDate.of(2026,9,17),psychology.date)
        val coursework = retakes.single { it.lesson.raw.startsWith("Спецкурс по курсовой") && it.lesson.raw.contains("Яровая") }
        assertEquals("КУРСОВАЯ",coursework.kind)
        assertEquals("ПЕРЕСДАЧА · КУРСОВАЯ",coursework.kindLabel)
        assertEquals("313",coursework.info.room)
        assertTrue(coursework.notice.text.contains("Комиссия - пересдача"))
    }

    @Test fun signalsAreRecognisedInAnySourceAndOrdinaryMessagesStayAnnouncements() {
        for (signal in listOf("Зачёт","зачет","зач.","ЗАЧ","(з.)","Экзамен","экз.","ЭКЗ","(э.)","(к.р.)","(З. О.)","пересдача","пересд.")) {
            val banner = Notice("official-$signal","Уважаемые студенты! $signal 26.10.2026 в 10:10")
            assertTrue(signal,ImportantInfo.isAssessment(banner))
            val lessons = AssessmentList.fromNotices(listOf(banner))
            val entry = AssessmentList.entries(lessons,now,start,known=mapOf(banner.id to banner)).single()
            assertEquals(banner.id,entry.notice.id)
            assertEquals(banner.text,entry.notice.text)
            assertEquals(LocalDate.of(2026,10,26),entry.date)
            assertEquals("10:10",entry.lesson.time)
            assertEquals(AssessmentState.UPCOMING,entry.state)
        }
        for (message in listOf("Собрание первокурсников состоится в актовом зале", "Экзема и её лечение", "Экзаменационная сессия", "Иванова З. О., лекция")) {
            assertFalse(message,ImportantInfo.isAssessment(Notice("announcement",message)))
        }
    }

    @Test fun bannerAssessmentsRemainInSemesterArchiveAndUntimedEntriesDoNotInventAClock() {
        val banner = Notice("official-credit","Зачёт по математике 22.10.2026")
        val lesson = AssessmentList.fromNotices(listOf(banner)).single()
        assertEquals("",lesson.time)
        val archive = AssessmentList.merge(listOf(lesson),emptyList(),now)
        val entry = AssessmentList.entries(archive,now,start).single()
        assertEquals(AssessmentState.PAST,entry.state)
        assertEquals(banner.id,entry.notice.id)
        assertEquals(banner.text,entry.notice.text)
        assertTrue(AssessmentList.merge(archive,emptyList(),now,true).isEmpty())
    }

    @Test fun aShortAssessmentBannerAndAnUntimedDayNoteAreNotDropped() {
        assertEquals("Экз. 26.10.2026",SiteParser.parseBanner("Экз. 26.10.2026").single().text)
        val doc=Jsoup.parse("""<table class="rasp_table"><tr><td><h2>Группа 3.008.2.26</h2></td></tr>
            <tr><td>Зачёт 26.10.2026 в 10:10</td></tr></table><table class="rasp_table">
            <tr><td>Числитель</td></tr><tr><td>Понедельник</td><td colspan="2">Пересдача 28.10.2026</td></tr></table>""")
        val page=GroupTimetableParser.parse(doc,"3.008.2.26",today=now.toLocalDate())
        assertEquals(2,page.notices.count { ImportantInfo.isAssessment(it) })
        assertEquals(2,AssessmentList.fromNotices(page.notices).size)
    }

    @Test fun onlyOldAndNewRoomValuesAreBoldAndTimestampDoesNotChangeOffsets() {
        val before=Lesson(0,"8:30-10:00","Алгебра, И.И.Петров, (п.з.: 1-16 нед.), а. 411")
        val change=ChangeLog.diff(listOf(before),listOf(before.copy(raw=before.raw.replace("411","213"))),now.toLocalDate(),start).single()
        val record=change.record().prefixed("2026-10-23 12:00 · ")
        assertEquals(listOf("411","213"),record.highlights.map { record.message.substring(it.start,it.end) })
        assertTrue(record.message.contains("Алгебра: Ауд. 411 → 213"))
    }

    @Test fun freeTextReplacementPreservesBothWordingsAndOnlyChangedWordsAreBold() {
        val before=Lesson(0,"8:30-10:00","Алгебра, И.И.Петров, а. 411, взять красную тетрадь")
        val after=before.copy(raw=before.raw.replace("красную","синюю"))
        val change=ChangeLog.diff(listOf(before),listOf(after),now.toLocalDate(),start).single()
        assertTrue(change.message.contains(before.raw))
        assertTrue(change.message.contains(after.raw))
        assertEquals(listOf("красную","синюю"),change.highlights.map { change.message.substring(it.start,it.end) })
    }

    @Test fun teacherTimeWeekAndRetakeEditsAreAllRecordedWithoutBoldUnchangedWords() {
        val teacher=ChangeLog.replacement("Преподаватель","Иванов Иван Иванович","Иванов Пётр Иванович")
        assertEquals(listOf("Иван","Пётр"),teacher.highlights.map { teacher.message.substring(it.start,it.end) })
        val before=Lesson(0,"8:30-10:00","Алгебра, И.И.Петров, (п.з.: 1-16 нед.), а. 411")
        val after=before.copy(time="10:10-11:40",raw=before.raw.replace("1-16","2-17")+", пересдача")
        val change=ChangeLog.diff(listOf(before),listOf(after),now.toLocalDate(),start).single()
        assertTrue(change.message.contains("8:30-10:00 → 10:10-11:40"))
        assertTrue(change.message.contains("Сдача обычная → пересдача"))
        assertTrue(change.message.contains("Недели"))
        assertFalse(change.highlights.any { change.message.substring(it.start,it.end)=="Алгебра" })
    }

    @Test fun insertingAnotherOccurrenceDoesNotReportAnUnchangedRoomAsChanged() {
        val before=Lesson(0,"10:10-11:40","Алгебра, И.И.Петров, а. 411")
        val changes=ChangeLog.diff(listOf(before),listOf(before.copy(time="8:30-10:00"),before),now.toLocalDate(),start)
        assertEquals(1,changes.size)
        assertTrue(changes.single().message.startsWith("Добавлено:"))
    }

    @Test fun aSeparateParagraphOutsideTheTablesAlsoReachesImportantEvents() {
        val doc=Jsoup.parse("""<td id="center"><h2>Группа 3.008.2.26</h2>
            <p>Зачёт по русскому языку 26.10.2026 в 10:10</p>
            <table class="rasp_table"><tr><td>Числитель</td></tr></table></td>""")
        val page=GroupTimetableParser.parse(doc,"3.008.2.26",today=now.toLocalDate())
        assertEquals(1,page.notices.count { ImportantInfo.isAssessment(it) })
        assertTrue(page.notices.single().text.startsWith("Зачёт по русскому"))
    }

    @Test fun aMovedClassShowsItsOldAndNewDayOrDateInsteadOfTwoUnrelatedMessages() {
        val before=Lesson(0,"8:30-10:00","Алгебра, И.И.Петров, а. 411")
        val move=ChangeLog.diff(listOf(before),listOf(before.copy(day=1)),now.toLocalDate(),start).single()
        assertTrue(move.message.contains("День понедельник → вторник"))
        val dated=before.copy(date=LocalDate.of(2026,10,26))
        val change=ChangeLog.diff(listOf(dated),listOf(dated.copy(day=1,date=LocalDate.of(2026,10,27))),now.toLocalDate(),start).single()
        assertTrue(change.message.contains("Дата 2026-10-26 → 2026-10-27"))
    }
}
