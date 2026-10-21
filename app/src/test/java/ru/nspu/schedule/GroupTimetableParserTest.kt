package ru.nspu.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GroupTimetableParserTest {
    private val start = LocalDate.of(2026,9,1)
    private val today = LocalDate.of(2026,10,21)
    private fun doc(rows: String, tail: String = "") = Jsoup.parse("""
      <table><tr><td id="center">
      <table class="rasp_table"><tr><td><h2>Группа 3.070.2.26</h2><i>3.070.2.26</i></td>
      <td>Уважаемые студенты! Собрание состоится в актовом зале.
      1 семестр Теоретическое обучение с 01.09.2026 г.</td></tr></table>
      <table class="rasp_table"><tr><td></td><td></td><td>Числитель</td>
      <td>Знаменатель, текущая неделя номер 8</td></tr>$rows</table>
      Последнее изменение расписания - 16.10.2026 $tail
      </td></tr></table>
    """)

    @Test fun headerTableAndLineBreaksCannotHideOrSplitAClass() {
        val source = doc("""<tr><td>Понедельник</td><td>8:30-10:00</td>
          <td colspan="2">История, <br>доц. <a href="teacher_shedule.php?id=1">И.И.Иванов</a>,
          (л.: 1-24 нед.), <a href="room_shedule.php?id=2">а. 404</a></td></tr>""")
        val parsed = GroupTimetableParser.parse(source,"3.070.2.26")
        assertEquals(1,parsed.lessons.size)
        assertEquals("404",SiteParser.details(parsed.lessons.single()).room)
        assertTrue(parsed.notices.single().text.contains("Собрание"))
        val native = PublicTimetableParser.parse(source,
            OfficialSchedulePage(OfficialSchedulePage.Kind.GROUP,1,"Группа"),0,today)
        assertEquals(1,native.sessions.size)
        assertEquals("История",native.sessions.single().subject)
        assertEquals(2,native.sessions.single().links.size)
        assertTrue(native.title.startsWith("Группа 3.070.2.26"))
    }

    @Test fun neighboringWeekColumnsRemainSeparateAcrossDates() {
        val parsed = GroupTimetableParser.parse(doc("""<tr><td>Понедельник</td><td>8:30-10:00</td>
          <td>Педагогика, Преподаватель, (л.: 1-24 нед.), а. 419</td>
          <td>Психология, Преподаватель, (п.з.: 1-24 нед.), а. 313</td></tr>"""),"3.070.2.26")
        assertEquals(2,parsed.lessons.size)
        fun subjects(date: LocalDate) = parsed.lessons.filter { SiteParser.applicable(it,date,start) }
            .map { it.raw.substringBefore(',') }
        assertEquals(listOf("Психология"),subjects(LocalDate.of(2026,10,19)))
        assertEquals(listOf("Педагогика"),subjects(LocalDate.of(2026,10,26)))
    }

    @Test fun practiceWeekAnnotationDoesNotCreateAnOrdinaryLecture() {
        val lesson = Lesson(0,"8:30-10:00",
            "История Сибири, Преподаватель, (л.: 1,3,7,9,11 нед. практика: 4-5,16-17 нед.), а. 320")
        assertFalse(SiteParser.applicable(lesson,LocalDate.of(2026,9,21),start))
        assertTrue(SiteParser.applicable(lesson,LocalDate.of(2026,9,14),start))
        val practice = lesson.copy(raw="Педагогическая практика, Преподаватель, (практика: 4-5 нед.)")
        assertTrue(SiteParser.applicable(practice,LocalDate.of(2026,9,21),start))
        val outside = lesson.copy(raw="Предмет, Преподаватель, (л.): 2,6-9 нед.")
        assertFalse(SiteParser.applicable(outside,LocalDate.of(2026,8,31),start))
        assertTrue(SiteParser.applicable(outside,LocalDate.of(2026,10,19),start))
    }

    @Test fun sessionAfterLastModifiedTextIsIncludedWithItsDateAndStartOnly() {
        val source = doc("", """<h2>Расписание сессии и ликвидации академической задолженности</h2>
          <table class="rasp_table"><tr><td>05 февраля 2027<br>Пятница</td><td>8.30</td>
          <td>История социальной педагогики, доц. Катионова Анна Олеговна <b>(ЭКЗ.)</b>, а. 306а</td>
          </tr></table>""")
        val lesson = GroupTimetableParser.parse(source,"3.070.2.26").lessons.single()
        assertEquals("8:30",lesson.time)
        assertEquals(LocalDate.of(2027,2,5),lesson.date)
        assertTrue(SiteParser.applicable(lesson,LocalDate.of(2027,2,5),start))
        assertFalse(SiteParser.applicable(lesson,LocalDate.of(2027,2,12),start))
        val notice = ImportantInfo.notices(listOf(lesson),today,start).single()
        assertTrue(notice.text.startsWith("ЭКЗАМЕН · 05.02.2027 · 8:30"))
    }

    @Test fun twoDigitYearAndRecurringFromDateHaveDifferentMeaning() {
        val once = Lesson(2,"8:30-10:00","Запись в библиотеку, , 09.09.26")
        assertEquals(LocalDate.of(2026,9,9),SiteParser.fixedDate(once))
        assertFalse(SiteParser.applicable(once,today,start))
        val recurring = once.copy(raw="Педагогическая практика, Преподаватель, с 16.09.26")
        assertNull(SiteParser.fixedDate(recurring))
        assertFalse(SiteParser.applicable(recurring,LocalDate.of(2026,9,9),start))
        assertTrue(SiteParser.applicable(recurring,today,start))
    }

    @Test fun embeddedCuratorTimeAndLibraryExplanationArePreserved() {
        val source = doc("""<tr><td>Четверг</td><td>12:10-13:40</td><td colspan="2">
          История, Преподаватель, (л.: 1-24 нед.), а. 305;
          13.45 ч. Кураторский час, Преподаватель, а. 408а</td></tr>
          <tr><td>Среда</td><td>8:30-10:00</td><td colspan="2">
          Запись в библиотеку, , 09.09.26; ЧИТАЛЬНЫЙ ЗАЛ 2 этаж библиотека.</td></tr>""")
        val parsed = GroupTimetableParser.parse(source,"3.070.2.26")
        val curator = parsed.lessons.first { it.raw.startsWith("Кураторский час") }
        assertEquals("13:45",curator.time)
        assertTrue(parsed.lessons.single { it.raw.startsWith("Запись") }.raw.contains("ЧИТАЛЬНЫЙ ЗАЛ"))
        assertTrue(ImportantInfo.notices(listOf(curator),today,start).single().text.contains("13:45"))
        val focus = LessonFocus.choose(LocalDate.of(2026,10,22).atTime(13,40)) { listOf(curator) }
        assertNotNull(focus)
        assertFalse(focus!!.ongoing)
        val native = PublicTimetableParser.parse(source,
            OfficialSchedulePage(OfficialSchedulePage.Kind.GROUP,1,"Группа"),0,today)
        assertFalse(native.sessions.any { it.description.contains("ЧИТАЛЬНЫЙ ЗАЛ") })
        assertEquals("13:45",native.sessions.single { it.subject == "Кураторский час" }.time)
    }

    @Test fun aDatedRoomExceptionDoesNotRemoveAllOtherWeeks() {
        val lesson = Lesson(0,"8:30-10:00",
            "Предмет, Преподаватель, (л.: 1-24 нед.), а. 401, 17.10.2026 - в 4п")
        assertNull(SiteParser.fixedDate(lesson))
        assertTrue(SiteParser.applicable(lesson,LocalDate.of(2026,10,19),start))
    }

    @Test fun ordinaryDatedClassesDoNotFloodImportantEventsAndMeetingLinksSurvive() {
        val ordinary = Lesson(0,"8:30-10:00","Математика, Преподаватель (п.з.), а. 404",
            date=LocalDate.of(2026,11,2))
        assertTrue(ImportantInfo.notices(listOf(ordinary),today,start).isEmpty())
        val cell = Jsoup.parse("<table><tr><td>Теория перевода, Преподаватель, <a href=\"https://example.org/meeting\">Ссылка</a></td></tr></table>")
            .selectFirst("td")!!
        assertTrue(PublicTimetableParser.cellText(cell).contains("https://example.org/meeting"))
    }
}
