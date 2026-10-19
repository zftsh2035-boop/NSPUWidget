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
    private val annotations = Regex("""\(([^)]*нед\.[^)]*)\)""", RegexOption.IGNORE_CASE)
    private val ranges = Regex("""(\d+)(?:\s*[-–]\s*(\d+))?""")
    private val kindRanges = Regex("""(л\.з\.|п\.з\.|л\.)\s*:\s*([\d,\s–-]+)\s*нед\.""", RegexOption.IGNORE_CASE)
    private val allTypes = Regex("""(?iu)\((экз\.?|зач\.?|л\.з\.|п\.з\.|л\.)\)""")

    fun parse(doc: Document, page: OfficialSchedulePage, offset: Int,
              today: LocalDate = LocalDate.now(ZONE)): PublicTimetable {
        val tables = doc.select("table.rasp_table")
        require(tables.isNotEmpty()) { "На странице НГПУ не найдены таблицы расписания" }
        val title = when (page.kind) {
            OfficialSchedulePage.Kind.ROOM ->
                doc.selectFirst(".room_info h2")?.text()?.trim()
            else -> doc.selectFirst("td#center > h2")?.text()?.trim()
        }?.takeIf { it.isNotBlank() } ?: page.title

        val weekHeader = directRows(tables.first()).firstOrNull()?.text().orEmpty()
        val number = Regex("""неделя\s+номер\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(weekHeader)?.groupValues?.get(1)?.toIntOrNull()
            ?: SiteParser.academicWeek(today.plusWeeks(offset.toLong()))
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(offset.toLong())
        val roomInfo = doc.selectFirst(".room_info .spoiler_body")?.text()?.trim().orEmpty()
        val sessions = mutableListOf<PublicClass>()
        var examSection = false
        var first = true
        val center = doc.selectFirst("td#center") ?: doc
        for (element in center.select("h2, table.rasp_table")) {
            if (element.tagName() == "h2") {
                if (element.text().contains("Расписание консультаций", ignoreCase = true)) {
                    examSection = true
                }
                continue
            }
            if (!element.hasClass("rasp_table")) continue
            if (first) {
                sessions += weekly(element, number, monday)
                first = false
            } else {
                sessions += datedRows(element,
                    if (examSection) PublicSection.EXAMS else PublicSection.DATED)
            }
        }
        // Malformed legacy HTML can move tables out of td#center: never silently lose them.
        if (sessions.isEmpty() && tables.isNotEmpty()) {
            sessions += weekly(tables.first(), number, monday)
            tables.drop(1).forEachIndexed { index, table ->
                sessions += datedRows(table,
                    if (index >= 1) PublicSection.EXAMS else PublicSection.DATED)
            }
        }
        return PublicTimetable(title, number, monday,
            sessions.distinctBy { listOf(it.section, it.date, it.day, it.time, it.description).joinToString("|") },
            roomInfo, doc.select("a[href*=old=1]").isNotEmpty())
    }

    private fun directRows(table: Element): List<Element> =
        table.children().flatMap { child ->
            when (child.tagName()) {
                "tbody", "thead" -> child.children().filter { it.tagName() == "tr" }
                "tr" -> listOf(child)
                else -> emptyList()
            }
        }

    private fun directCells(row: Element) =
        row.children().filter { it.tagName() in listOf("td", "th") }

    private fun dayOf(text: String): Int = days.indexOfFirst {
        text.trim().lowercase(Locale.ROOT).startsWith(it)
    }

    private fun time(raw: String): String? {
        val m = period.find(raw)
        if (m != null) return m.groupValues[1].replace('.', ':') + "-" +
            m.groupValues[2].replace('.', ':')
        return raw.trim().replace('.', ':').takeIf { examTime.matches(it) }
    }

    private fun weekly(table: Element, week: Int, monday: LocalDate): List<PublicClass> {
        val result = mutableListOf<PublicClass>()
        var currentDay = -1
        for (row in directRows(table).drop(1)) {
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
                if (matchesWeek(fragment, week)) {
                    result += lesson(fragment, slot, currentDay, date, PublicSection.WEEKLY)
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
            val match = dated.find(cells.first().text())
            var start = 0
            if (match != null) {
                val day = match.groupValues[1].toIntOrNull()
                val month = monthNames[match.groupValues[2].lowercase(Locale.ROOT)]
                val year = match.groupValues[3].toIntOrNull()
                currentDate = if (day != null && month != null && year != null)
                    runCatching { LocalDate.of(year, month, day) }.getOrNull() else null
                start++
            }
            // h1 month rows carry no date/lesson; skip safely.
            if (currentDate == null || start >= cells.size) continue
            val slot = time(cells[start].text()) ?: continue
            val dayIndex = currentDate.dayOfWeek.value - 1
            cells.drop(start + 1).forEach { cell ->
                for (fragment in splitCell(cell)) {
                    result += lesson(fragment, slot, dayIndex, currentDate, section)
                }
            }
        }
        return result
    }

    private fun splitCell(cell: Element): List<Fragment> {
        val html = cell.html().replace(Regex("""(?i)<br\s*/?>"""), " ⟦break⟧ ")
        // Jsoup text is deterministic; we don't execute arbitrary HTML or JS.
        val text = Jsoup.parseBodyFragment(html).body().text()
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
        return text.split(Regex("""⟦break⟧|;\s*""")).mapNotNull { raw ->
            val part = raw.trim().trim(',', ' ', '\u00a0')
            if (part.length < 4 || part == "—") null else Fragment(part,
                refs.filter { it.label.length > 2 && part.contains(it.label, ignoreCase = true) }
                    .distinctBy { it.url })
        }
    }

    private data class Fragment(val text: String, val refs: List<PublicReference>)

    private fun matchesWeek(fragment: Fragment, week: Int): Boolean {
        val captures = annotations.findAll(fragment.text).toList()
        if (captures.isEmpty()) return true
        // A lesson may combine multiple types, e.g. 2 weeks lecture, 6-10 practical.
        return captures.any { annotation ->
            ranges.findAll(annotation.groupValues[1]).any { r ->
                val first = r.groupValues[1].toInt()
                val last = r.groupValues[2].toIntOrNull() ?: first
                week in first..last
            }
        }
    }

    private fun lesson(fragment: Fragment, slot: String, day: Int, date: LocalDate,
                       section: PublicSection): PublicClass {
        val text = fragment.text
        val group = fragment.refs.firstOrNull { it.type == "group" }
        val subject = if (group != null) text.substringBefore(group.label)
            .trimEnd(' ', ',') else text.substringBefore(",").trim()
        val name = subject.ifBlank { text.substringBefore(",").trim() }
        val kind = classify(text, section, date)
        return PublicClass(slot, name.take(125), text.take(800), kind,
            fragment.refs, day, if (section == PublicSection.WEEKLY) null else date, section)
    }

    private fun classify(text: String, section: PublicSection, date: LocalDate): String {
        if (Regex("""(?iu)(?:экзамен|экз\.)""").containsMatchIn(text)) return "ЭКЗАМЕН"
        if (Regex("""(?iu)(?:зачёт|зачет|зач\.)""").containsMatchIn(text)) return "ЗАЧЁТ"
        val marked = kindRanges.findAll(text).firstOrNull { match ->
            val week = SiteParser.academicWeek(date)
            ranges.findAll(match.groupValues[2]).any { r ->
                val from = r.groupValues[1].toInt()
                val to = r.groupValues[2].toIntOrNull() ?: from
                week in from..to
            }
        }?.groupValues?.get(1) ?: kindRanges.find(text)?.groupValues?.get(1)
        val type = marked ?: allTypes.find(text)?.groupValues?.get(1)
        return when (type?.lowercase(Locale.ROOT)) {
            "л." -> "ЛЕКЦИЯ"
            "п.з." -> "ПРАКТИКА"
            "л.з." -> "ЛАБОРАТОРНАЯ"
            "зач.", "зач" -> "ЗАЧЁТ"
            "экз.", "экз" -> "ЭКЗАМЕН"
            else -> "ЗАНЯТИЕ"
        }
    }
}
