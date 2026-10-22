package ru.nspu.schedule

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Source-cell coverage includes empty clock cells; no expected data is taken from the app parser. */
private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val datePattern = Regex("""^(\d{1,2})\s+([а-яё]+)\s+(20\d{2})""")
private val clockPattern = Regex("""^\d{1,2}[:.]\d{2}(?:\s*[-–—]\s*\d{1,2}[:.]\d{2})?$""")
private val leadingClock = Regex("""^\d{1,2}[:.]\d{2}(?:\s*[-–—]\s*\d{1,2}[:.]\d{2})?\s*(?:ч\.?)?\s+(?=[А-Яа-яЁё])""")
private fun rows(table: Element) = table.children().flatMap {
    if (it.tagName() in listOf("tbody", "thead")) it.children().filter { row -> row.tagName() == "tr" }
    else if (it.tagName() == "tr") listOf(it) else emptyList()
}
private fun words(text: String) = text.replace(Regex("""\s*\(https?://[^)]+\)"""), "")
    .lowercase().filter { it.isLetterOrDigit() }
private fun sourceDate(text: String): LocalDate? = datePattern.find(text)?.let {
    runCatching { LocalDate.of(it.groupValues[3].toInt(), months.indexOf(it.groupValues[2]) + 1,
        it.groupValues[1].toInt()) }.getOrNull()
}
private fun minute(text: String) = runCatching {
    LocalTime.parse(text.substringBefore('-').trim().replace('.', ':'), DateTimeFormatter.ofPattern("H:mm"))
        .let { it.hour * 60 + it.minute }
}.getOrDefault(Int.MAX_VALUE)

fun main(args: Array<String>) {
    val root = File(args[0])
    val today = LocalDate.parse(args.getOrElse(1) { "2026-10-22" })
    var pages = 0; var records = 0; var unknown = 0; var failures = 0
    val report = StringBuilder("url\tweekly\tdated\tmissing_time\tissues\n")
    for (line in File(root, "manifest.tsv").readLines()) {
        val (name, url) = line.split('\t')
        val page = checkNotNull(OfficialSchedulePage.fromUrl(url))
        val doc = Jsoup.parse(File(root, name).readText(), url)
        val expected = linkedMapOf<LocalDate, StringBuilder>()
        for (table in doc.select("table.rasp_table")) {
            val cells = rows(table).flatMap { it.children().filter { c -> c.tagName() in listOf("td", "th") } }
            if (cells.none { sourceDate(it.text()) != null }) continue
            var date: LocalDate? = null
            var hasSlot = false
            for (cell in cells) {
                val text = cell.text().trim()
                val found = sourceDate(text)
                when {
                    found != null -> { date = found; hasSlot = false }
                    cell.select("h1, h2").isNotEmpty() -> { date = null; hasSlot = false }
                    date != null && (text.isBlank() || clockPattern.matches(text)) -> hasSlot = true
                    date != null && hasSlot && text.length >= 4 && text != "—" ->
                        expected.getOrPut(date) { StringBuilder() }.append(text.replace(leadingClock, ""))
                }
            }
        }
        val parsed = PublicTimetableParser.parse(doc, page, 0, today)
        val calendar = parsed.sessions.filter { it.section != PublicSection.WEEKLY }
        val actual = calendar.groupBy { it.date }.mapValues { (_, events) ->
            events.joinToString("") { it.description }
        }
        val issues = mutableListOf<String>()
        for ((date, text) in expected) {
            if (words(text.toString()) != words(actual[date].orEmpty())) issues += "source text/date $date"
        }
        if (actual.keys != expected.keys) issues += "calendar dates"
        for (section in listOf(PublicSection.DATED, PublicSection.EXAMS)) {
            for ((date, events) in parsed.dated(section).groupBy { it.date }) {
                val clocks = events.map { minute(it.time) }
                if (clocks != clocks.sorted()) issues += "clock order $section $date"
            }
        }
        for (day in 0..6) {
            val clocks = parsed.byDay(day).map { minute(it.time) }
            if (clocks != clocks.sorted()) issues += "weekly order $day"
        }
        val missing = calendar.count { it.time.isBlank() }
        pages++; records += calendar.size; unknown += missing; failures += issues.size
        report.appendLine("$url\t${parsed.sessions.size - calendar.size}\t${calendar.size}\t$missing\t${issues.joinToString("; ")}")
        if (issues.isNotEmpty()) println("$name: ${issues.joinToString()}")
    }
    File(root, "native-audit.tsv").writeText(report.toString())
    println("Public native audit: $pages pages, $records dated records, $unknown missing source clocks, $failures issues")
    check(failures == 0)
}
