package ru.nspu.schedule

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.Locale

enum class PublicSection { WEEKLY, DATED, EXAMS }

data class PublicReference(val label: String, val url: String, val type: String)
data class PublicClass(
    val time: String,
    val subject: String,
    val description: String,
    val kind: String,
    val links: List<PublicReference>,
    val day: Int = 0,
    val date: LocalDate? = null,
    val section: PublicSection = PublicSection.WEEKLY
)

data class PublicTimetable(
    val title: String,
    val week: Int,
    val weekStart: LocalDate,
    val sessions: List<PublicClass>,
    val roomInfo: String = "",
    val hasPastButton: Boolean = false
) {
    fun byDay(day: Int) = sessions.filter { it.section == PublicSection.WEEKLY && it.day == day }
    fun dated(section: PublicSection) = sessions.filter { it.section == section }
        .sortedWith(compareBy<PublicClass> { it.date }.thenBy { it.time })
}

/**
 * Pure Jsoup parser for the PUBLIC teacher/room timetable pages. No WebView, JS, or
 * private student data. NSPU uses one weekly grid with numerator/denominator columns,
 * then separate date-based tables, some with rows malformed by legacy HTML markup.
 */
object PublicTimetableParser {
    private val days = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val period = Regex("""(?<!\d)([012]?\d[:.]\d\d)\s*[-–—]\s*([012]?\d[:.]\d\d)""")
    private val examTime = Regex("""^\s*[012]?\d[:.]\d\d\s*$""")
    private val monthNames = mapOf(
        "января" to 1, "февраля" to 2, "марта" to 3, "апреля" to 4,
        "мая" to 5, "июня" to 6, "июля" to 7, "августа" to 8,
        "сентября" to 9, "октября" to 10, "ноября" to 11, "декабря" to 12
    )
    private val dated = Regex("""(\d{1,2})\s+([а-яё]+)\s+(20\d{2})""", RegexOption.IGNORE_CASE)
    private val numericDate = Regex("""^\s*(\d{1,2})\.(\d{1,2})\.(20\d{2}|\d{2})(?!\d)""")
    private val leadingClock = Regex("""^\s*(\d{1,2}[:.]\d{2})\s*(?:ч\.?)?\s+(?=[А-Яа-яЁё])""")

    fun parse(doc: Document, page: OfficialSchedulePage, offset: Int,
              today: LocalDate = LocalDate.now(ZONE)): PublicTimetable {
        val tables = doc.select("table.rasp_table")
        require(tables.isNotEmpty()) { "На странице НГПУ не найдены таблицы расписания" }
        val title = when (page.kind) {
            OfficialSchedulePage.Kind.ROOM ->
                doc.selectFirst(".room_info h2")?.text()?.trim()
            OfficialSchedulePage.Kind.GROUP, OfficialSchedulePage.Kind.EXTRAMURAL_GROUP ->
                doc.select("h2").firstOrNull { it.text().startsWith("Группа ") }?.text()?.trim()
            else -> doc.selectFirst("td#center > h2")?.text()?.trim()
        }?.takeIf { it.isNotBlank() } ?: page.title

        val weekHeader = tables.firstOrNull { it.text().contains("Числитель") }?.text().orEmpty()
        val number = Regex("""неделя\s+номер\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(weekHeader)?.groupValues?.get(1)?.toIntOrNull()
            ?: SiteParser.academicWeek(today.plusWeeks(offset.toLong()))
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(offset.toLong())
        val roomInfo = doc.selectFirst(".room_info .spoiler_body")?.text()?.trim().orEmpty()
        val sessions = mutableListOf<PublicClass>()
        val termStart = SiteParser.termStart(doc.body().text())
        var examSection = false
        for (element in doc.select("h2, table.rasp_table")) {
            if (element.tagName() == "h2") {
                if (Regex("(?iu)расписание\\s+(?:консультаций|сессии|гиа)").containsMatchIn(element.text())) {
                    examSection = true
                }
                continue
            }
            if (!element.hasClass("rasp_table")) continue
            val calendarTable = page.kind == OfficialSchedulePage.Kind.EXTRAMURAL_GROUP || hasCalendarRows(element)
            if (!calendarTable && element.text().contains("Числитель")) {
                sessions += weekly(element, number, monday, termStart ?: monday.minusWeeks(number - 1L))
            } else if (calendarTable) {
                sessions += datedRows(element,
                    if (examSection) PublicSection.EXAMS else PublicSection.DATED)
            }
        }
        return PublicTimetable(title, number, monday,
            sessions.distinctBy { listOf(it.section, it.date, it.day, it.time, it.description).joinToString("|") },
            roomInfo, doc.select("a[href*=old=1]").isNotEmpty())
    }

    internal fun hasCalendarRows(table: Element): Boolean = directRows(table).any { row ->
        calendarDate(directCells(row).firstOrNull()?.text().orEmpty()) != null
    }

    private fun calendarDate(text: String): LocalDate? {
        val match = dated.find(text)
        val numeric = if (match == null) numericDate.find(text) else null
        val day = (match ?: numeric)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val month = if (match != null) monthNames[match.groupValues[2].lowercase(Locale.ROOT)]
            else numeric!!.groupValues[2].toIntOrNull()
        val year = (match ?: numeric)!!.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
        return month?.let { runCatching { LocalDate.of(year, it, day) }.getOrNull() }
    }

    internal fun directRows(table: Element): List<Element> =
        table.children().flatMap { child ->
            when (child.tagName()) {
                "tbody", "thead" -> child.children().filter { it.tagName() == "tr" }
                "tr" -> listOf(child)
                else -> emptyList()
            }
        }

    internal fun directCells(row: Element) =
        row.children().filter { it.tagName() in listOf("td", "th") }

    private fun dayOf(text: String): Int = days.indexOfFirst {
        text.trim().lowercase(Locale.ROOT).startsWith(it)
    }

    internal fun time(raw: String): String? {
        val m = period.find(raw)
        if (m != null) return m.groupValues[1].replace('.', ':') + "-" +
            m.groupValues[2].replace('.', ':')
        return raw.trim().replace('.', ':').takeIf { examTime.matches(it) }
    }

    private fun weekly(table: Element, week: Int, monday: LocalDate, termStart: LocalDate): List<PublicClass> {
        val result = mutableListOf<PublicClass>()
        var currentDay = -1
        for (row in directRows(table)) {
            val cells = directCells(row)
            if (cells.isEmpty()) continue
            var start = 0
            val maybeDay = dayOf(cells.first().text())
            if (maybeDay >= 0) { currentDay = maybeDay; start++ }
            if (currentDay < 0 || start >= cells.size) continue
            val slot = time(cells[start].text()) ?: continue
            val descriptions = cells.drop(start + 1)
            if (descriptions.isEmpty()) continue
            // For combined columns (colspan=2) lessons apply to both weeks;
            // otherwise the first cell is Числитель, second is Знаменатель.
            val selected = if (descriptions.size == 1) descriptions.first()
                else descriptions[if (week % 2 == 1) 0 else 1]
            if (selected.text().isBlank()) continue
            val fragments = splitCell(selected)
            val date = monday.plusDays(currentDay.toLong())
            for (fragment in fragments) {
                if (SiteParser.applicable(Lesson(currentDay,slot,fragment.text),date, termStart)) {
                    result += lesson(fragment, slot, currentDay, date, PublicSection.WEEKLY, termStart)
                }
            }
        }
        return result
    }

    private fun datedRows(table: Element, section: PublicSection): List<PublicClass> {
        val result = mutableListOf<PublicClass>()
        var currentDate: LocalDate? = null
        for (row in directRows(table)) {
            val cells = directCells(row)
            if (cells.isEmpty()) continue
            val date = calendarDate(cells.first().text())
            var start = 0
            if (date != null) { currentDate = date; start++ }
            // h1 month rows carry no date/lesson; skip safely.
            if (currentDate == null || start >= cells.size) continue
            val dayIndex = currentDate.dayOfWeek.value - 1
            // Some NSPU calendars omit <tr> after the first lesson of a date.
            // Jsoup then repairs several time/description pairs into one row.
            // Each clock starts its own slot; a later clock is never a subject.
            while (start < cells.size) {
                val slot = time(cells[start].text())
                if (slot == null) { start++; continue }
                start++
                while (start < cells.size && time(cells[start].text()) == null) {
                    for (fragment in splitCell(cells[start])) {
                        result += lesson(fragment, slot, dayIndex, currentDate, section)
                    }
                    start++
                }
            }
        }
        return result
    }

    private fun splitCell(cell: Element): List<Fragment> {
        // A <br> often separates subject and teacher, not two different lessons.
        val text = cellText(cell)
        val refs = cell.select("a[href]").mapNotNull { a ->
            val path = a.attr("href").substringBefore('?')
            val type = when (path) {
                "teacher_shedule.php" -> "teacher"
                "room_shedule.php" -> "room"
                "group_shedule.php", "zgroup_shedule.php" -> "group"
                else -> null
            } ?: return@mapNotNull null
            val id = Regex("""(?:\?|&)id=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)
                ?: return@mapNotNull null
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null
            PublicReference(title, "https://schedule.nspu.ru/$path?id=$id", type)
        }
        val result = mutableListOf<Fragment>()
        for (raw in text.split(';')) {
            val part = raw.trim().trim(',', ' ', '\u00a0')
            if (part.length < 4 || part == "—") continue
            val links = refs.filter { it.label.length > 2 && part.contains(it.label, ignoreCase = true) }
                .distinctBy { it.url }
            if (!part.contains(',') && !leadingClock.containsMatchIn(part) && result.isNotEmpty()) {
                val previous = result.removeAt(result.lastIndex)
                result += previous.copy(text = previous.text + "; " + part,
                    refs = (previous.refs + links).distinctBy { it.url })
            } else result += Fragment(part,links)
        }
        return result
    }

    private data class Fragment(val text: String, val refs: List<PublicReference>)

    internal fun cellText(cell: Element): String {
        val copy = cell.clone()
        for (link in copy.select("a[href]")) {
            val href = link.attr("href")
            val uri = runCatching { java.net.URI(href) }.getOrNull() ?: continue
            if (uri.scheme in listOf("https", "http") && uri.host != null &&
                uri.host != "schedule.nspu.ru" && !link.text().contains(href)) {
                // Preserve public meeting/resource URLs instead of reducing them to "Ссылка".
                link.text(link.text() + " (" + href + ")")
            }
        }
        return copy.text()
    }

    private fun matchesWeek(fragment: Fragment, week: Int): Boolean {
        return SiteParser.weekMatches(fragment.text, week)
    }

    private fun lesson(fragment: Fragment, slot: String, day: Int, date: LocalDate,
                       section: PublicSection, termStart: LocalDate = LocalDate.of(2026,9,1)): PublicClass {
        val clock = leadingClock.find(fragment.text)
        val text = if (clock == null) fragment.text else fragment.text.substring(clock.range.last + 1).trim()
        val group = fragment.refs.firstOrNull { it.type == "group" }
        val subject = if (group != null) text.substringBefore(group.label)
            .trimEnd(' ', ',') else text.substringBefore(",").trim()
        val name = subject.ifBlank { text.substringBefore(",").trim() }
        val kind = SiteParser.lessonKind(Lesson(day, slot, text), date, termStart)
        return PublicClass(clock?.groupValues?.get(1)?.replace('.', ':') ?: slot, name, text, kind,
            fragment.refs, day, if (section == PublicSection.WEEKLY) null else date, section)
    }

}
