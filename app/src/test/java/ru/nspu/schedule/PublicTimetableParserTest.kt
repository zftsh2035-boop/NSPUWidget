package ru.nspu.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PublicTimetableParserTest {
    @Test fun aMissingSourceClockIsVisibleWithoutInventingAnInterval() {
        val lesson = PublicClass("", "Экономика образования", "Экономика образования, (л.)",
            "ЛЕКЦИЯ", emptyList())
        assertEquals("Время не указано", lesson.present().time)
    }
    private val room = OfficialSchedulePage(OfficialSchedulePage.Kind.ROOM,159,"Ауд. 206")
    private fun document(week: Int = 8) = Jsoup.parse("""
      <html><body><table class="main_table"><tr><td id="center">
        <div class="room_info"><div class="spoiler_title"><h2>206 · ИФМИТО</h2></div>
          <div class="spoiler_body" style="display:none">Вместимость: 24<br>Этаж: 2</div></div>
        <table class="rasp_table">
          <tr><td></td><td></td><td>Числитель</td><td>Знаменатель, текущая неделя номер $week</td></tr>
          <tr>
            <td id="color" rowspan="3">Понедельник</td><td>8:30-10:00</td>
            <td colspan="2">Русский язык и культура речи, <a href="group_shedule.php?id=3328">3.008.2.26</a>, (л.: 2,6-9 нед.) <a href="teacher_shedule.php?id=452">И.Н.Азарова</a></td>
          </tr>
          <tr><td>10:10-11:40</td>
            <td>Геометрия, <a href="group_shedule.php?id=120">3.007.2.26</a>, (л.: 7-9 нед.)</td>
            <td>Физика, <a href="group_shedule.php?id=333">3.009.2.26</a>, (п.з.: 8-10 нед.)</td>
          </tr>
          <tr><td>12:10-13:40</td><td colspan="2">
            Иностранный язык, <a href="group_shedule.php?id=3328">3.008.2.26</a>,
            (л.з.: 3-14 нед.) ст.преп. <a href="teacher_shedule.php?id=583">С.А.Урман</a>, А6;
            <br>Клиническая психология, <a href="group_shedule.php?id=21">3.011.2.26</a>,
            (п.з.: 11-18 нед.) доц. <a href="teacher_shedule.php?id=726">О.Б.Ганпанцурова</a>
          </td></tr>
          <tr><td rowspan="1">Вторник</td><td>8:30-10:00</td>
            <td colspan="2">&nbsp;</td></tr>
        </table>
        <h2>Заочное отделение, аспирантура</h2>
        <a href="room_shedule.php?id=159&amp;old=1">Показать прошедшие</a>
        <table class="rasp_table">
          <tr><td colspan="3"><h1>Октябрь</h1></td></tr>
          <tr><td rowspan="2">19 октября 2026<br>Понедельник</td>
            <td>14:10-15:40</td><td>Речевые практики,
            <a href="zgroup_shedule.php?id=4422">3.028.1.26</a>, ИФМИТО (л.),
            ст.преп. <a href="teacher_shedule.php?id=452">И.Н.Азарова</a></td></tr>
          <tr><td>15:50-17:20</td><td>Речевые практики,
            <a href="zgroup_shedule.php?id=4423">3.031.1.26</a>, (п.з.)</td></tr>
        </table>
        <h2>Расписание консультаций и экзаменов</h2>
        <h2>Сентябрь</h2>
        <table class="rasp_table">
          <tr><td>02 сентября 2026<br>Среда</td><td>14.10</td>
            <td>Речевые практики, <a href="group_shedule.php?id=3152">3.057.4.25</a>, (экз.) - пересдача</td>
          </tr>
        </table>
      </td></tr></table></body></html>
    """.trimIndent(), "https://schedule.nspu.ru/room_shedule.php?id=159")

    @Test fun parityDayAndWeekRangesArePreserved() {
        val p = PublicTimetableParser.parse(document(), room, 0, LocalDate.of(2026,10,19))
        assertEquals(8,p.week)
        val monday = p.byDay(0)
        assertTrue(monday.any { it.subject.contains("Русский язык") })
        assertTrue(monday.any { it.subject.contains("Физика") })
        assertFalse(monday.any { it.subject.contains("Геометрия") })
        assertTrue(monday.any { it.subject.contains("Иностранный язык") })
        assertFalse(monday.any { it.subject.contains("Клиническая психология") })
        assertTrue(p.byDay(1).isEmpty())
        assertTrue(p.roomInfo.contains("Вместимость: 24"))
        assertTrue(p.hasPastButton)
        val lang = monday.first { it.subject.contains("Иностранный") }
        assertTrue(lang.links.any { it.type == "teacher" && it.url.endsWith("id=583") })
        assertTrue(lang.links.any { it.type == "group" && it.url.endsWith("id=3328") })
    }

    @Test fun numeratorAndMultipleDateSections() {
        val week9 = PublicTimetableParser.parse(document(9),room,1,LocalDate.of(2026,10,19))
        assertEquals(9,week9.week)
        assertTrue(week9.byDay(0).any { it.subject.contains("Геометрия") })
        assertFalse(week9.byDay(0).any { it.subject.contains("Физика") })
        val dated = week9.dated(PublicSection.DATED)
        assertEquals(2,dated.size)
        assertEquals(LocalDate.of(2026,10,19),dated.first().date)
        val exam = week9.dated(PublicSection.EXAMS)
        assertEquals(1, exam.size)
        assertEquals("14:10",exam.first().time)
        assertEquals(LocalDate.of(2026,9,2),exam.first().date)
        assertEquals("ЭКЗАМЕН",exam.first().kind)
    }

    @Test fun nativeNavigationDoesNotTrustExternalOrMalformedLinks() {
        assertNotNull(OfficialSchedulePage.fromUrl(
            "https://schedule.nspu.ru/teacher_shedule.php?id=452","Азарова"))
        assertNotNull(OfficialSchedulePage.fromUrl(
            "https://schedule.nspu.ru/room_shedule.php?id=159"))
        assertNotNull(OfficialSchedulePage.fromUrl(
            "https://schedule.nspu.ru/zgroup_shedule.php?id=4423"))
        assertNull(OfficialSchedulePage.fromUrl("https://evil.example/teacher_shedule.php?id=452"))
        assertNull(OfficialSchedulePage.fromUrl("https://schedule.nspu.ru/a_login.php?id=452"))
        assertNull(OfficialSchedulePage.fromUrl("http://schedule.nspu.ru/room_shedule.php?id=159"))
        assertNull(OfficialSchedulePage.fromUrl("https://schedule.nspu.ru/room_shedule.php?id=159&old=1"))
        assertNull(OfficialSchedulePage.fromUrl("https://schedule.nspu.ru/room_shedule.php?id=0"))
    }
    @Test fun presentationShowsWeekRangesWithoutExpandingCard() {
        val lesson = PublicClass(
            time = "8:30-10:00", subject = "Русский язык и культура речи",
            description = "Русский язык и культура речи, 3.008.2.26, Кабинет №206, (л.): 2,6-9 нед.",
            kind = "ЛЕКЦИЯ",
            links = listOf(
                PublicReference("3.008.2.26", "https://schedule.nspu.ru/group_shedule.php?id=3328","group"),
                PublicReference("Кабинет №206", "https://schedule.nspu.ru/room_shedule.php?id=159","room")
            )
        )
        val view = lesson.present()
        assertEquals("8:30-10:00", view.time)
        assertTrue(view.weekNotes.contains("2,6-9 нед."))
        assertTrue(view.metadata.contains("3.008.2.26"))
        assertTrue(view.metadata.contains("Кабинет №206"))
        assertEquals("л.", view.kindLabel)
    }

    @Test fun weekRangeAfterClosingTypeParenthesisIsAlwaysDisplayed() {
        val event = PublicClass(
            "8:30-10:00", "Русский язык",
            "Русский язык, 3.008.2.26 ИСиТвО, Кабинет №206, (л.): 2,6-9 нед.",
            "ЛЕКЦИЯ", emptyList()
        )
        val ui = event.present()
        assertTrue("Exact official week range must be visible", ui.weekNotes.contains("2,6-9 нед."))
        assertTrue(ui.weekNotes.contains("(л.)"))
        assertTrue(ui.metadata.contains("3.008.2.26"))
    }
    @Test fun bothWeekAnnotationStylesAreSupported() {
        val event = PublicClass("10:10-11:40", "Математика",
            "Математика, (л.: 2 нед.), (п.з.): 6-10 нед.",
            "ПРАКТИКА", emptyList())
        val notes = event.present().weekNotes
        assertTrue(notes.contains("2 нед."))
        assertTrue(notes.contains("6-10 нед."))
    }

}
