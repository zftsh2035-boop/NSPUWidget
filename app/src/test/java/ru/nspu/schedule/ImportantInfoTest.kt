package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import org.jsoup.Jsoup

class ImportantInfoTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val start = LocalDate.of(2026, 9, 1)
    private val credit = Lesson(5, "10:10-11:40", "Математика в профильной подготовке, ст.преп. А.А.Варжавин, а. 404, 28.11.2026 г. - зачёт")

    @Test fun datedCreditIsVisibleInAdvanceAndOnlyOnItsDate() {
        val notice = ImportantInfo.notices(listOf(credit), today, start).single()
        assertTrue(notice.text.startsWith("ЗАЧЁТ · 28.11.2026 · 10:10-11:40"))
        assertTrue(notice.text.contains("а. 404"))
        assertEquals(LocalDate.of(2026, 11, 28), ImportantInfo.date(notice))
        assertFalse(SiteParser.applicable(credit, LocalDate.of(2026, 11, 21), start))
        assertTrue(SiteParser.applicable(credit, LocalDate.of(2026, 11, 28), start))
        assertTrue(ImportantInfo.notices(listOf(credit), LocalDate.of(2026, 11, 29), start).isEmpty())
    }
    @Test fun bothParenthesizedAbbreviationsAndFreeTextUseTheSameKinds() {
        for (raw in listOf("(зач.)", "(ЗАЧ)", "(ЗАЧЁТ)", "28.11.2026 - зачет с оценкой"))
            assertEquals(raw, "ЗАЧЁТ", SiteParser.specialKind(raw))
        for (raw in listOf("(экз.)", "(ЭКЗ)", "Экзамен"))
            assertEquals(raw, "ЭКЗАМЕН", SiteParser.specialKind(raw))
        assertEquals("КОНСУЛЬТАЦИЯ", SiteParser.specialKind("Предмет (конс.)"))
        assertEquals("КУРСОВАЯ", SiteParser.specialKind("Предмет (курс.р.)"))
        assertNull(SiteParser.specialKind("Информационная безопасность и защита информации (л.)"))
        assertNull(SiteParser.specialKind("Экзема и её лечение (п.з.)"))
    }
    @Test fun repeatFetchAndWhitespaceDoNotRepeatAnEventButRoomEditsDo() {
        fun id(l: Lesson) = ImportantInfo.notices(listOf(l), today, start).single().id
        assertEquals(id(credit), id(credit.copy(raw = credit.raw.replace(" ", "  ").uppercase())))
        assertNotEquals(id(credit), id(credit.copy(raw = credit.raw.replace("404", "417"))))
        assertNotEquals(id(credit), id(credit.copy(raw = credit.raw.replace("28.11.2026", "05.12.2026"))))
    }
    @Test fun unfamiliarAnnotationIsPreservedInsteadOfInventingItsMeaning() {
        val before = Lesson(0, "8:30-10:00", "Алгебра, Преподаватель, (л.: 1-24 нед.), а. 404")
        val after = before.copy(raw = before.raw + ", взять калькулятор")
        val change = ChangeLog.diff(listOf(before), listOf(after), today, start).single()
        assertTrue(change.message.contains("Изменение на сайте"))
        assertTrue(change.message.contains("взять калькулятор"))
        assertTrue(ChangeLog.diff(listOf(after), listOf(after), today, start).isEmpty())
    }
    @Test fun ambiguousDatesAndOrdinaryBannerDatesAreNotGivenAnInventedExpiry() {
        val ambiguous = credit.copy(raw = credit.raw + ", перенос на 05.12.2026")
        val event = ImportantInfo.notices(listOf(ambiguous), today, start).single()
        assertNull(ImportantInfo.date(event))
        assertTrue(event.text.contains("даты в тексте"))
        val banner = Notice("official-banner", "Зачёт 28.11.2026, следи за обновлениями")
        assertFalse(SiteParser.noticeIsOutdated(banner, LocalDate.of(2026, 12, 1)))
    }
    @Test fun explicitSessionDateCanFallOutsideTheTeachingWeeks() {
        val exam = credit.copy(raw = "Предмет, Преподаватель, а. 404, 15.03.2027 - ЭКЗ.")
        assertTrue(SiteParser.applicable(exam, LocalDate.of(2027, 3, 15), start))
    }
    @Test fun boldExtramuralTypesAreParsedFromRealTableStructure() {
        val page = OfficialSchedulePage(OfficialSchedulePage.Kind.EXTRAMURAL_GROUP, 3170, "3.028.1.22")
        val doc = Jsoup.parse("""<table class="main_table"><tr><td id="center"><h2>Группа 3.028.1.22</h2>
          <table class="rasp_table"><tr><td>03 ноября 2026<br>Вторник</td><td>12:10-13:40</td>
          <td>Компьютерное моделирование, Преподаватель <b>(ЗАЧ.)</b>, а. 417</td></tr>
          <tr><td>04 ноября 2026<br>Среда</td><td>10:10-11:40</td>
          <td>Методика обучения, Преподаватель <strong>(ЭКЗ.)</strong>, а. 415</td></tr></table>
          </td></tr></table>""", page.url)
        val parsed = PublicTimetableParser.parse(doc, page, 0, today)
        assertEquals(setOf("ЗАЧЁТ", "ЭКЗАМЕН"), parsed.sessions.map { it.kind }.toSet())
    }
}
