package ru.nspu.schedule

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.time.LocalDate

/** Compare saved public HTML with the actual parsers, without Android or a live request. */
private fun auditRows(table: Element) = table.children().flatMap {
    if (it.tagName() in listOf("tbody", "thead")) it.children().filter { row -> row.tagName() == "tr" }
    else if (it.tagName() == "tr") listOf(it) else emptyList()
}
private fun auditCells(row: Element) = row.children().filter { it.tagName() in listOf("td", "th") }
private fun normal(raw: String) = raw.replace(Regex("""\s+\(https?://[^)]+\)"""), "").replace(Regex("\\s+"), " ").trim().trim(',', ' ')
private fun identity(lesson: Lesson) = "${lesson.day}|${lesson.time}|${normal(lesson.raw)}"
private val auditDays = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
private val auditTime = Regex("""^\d{1,2}[:.]\d{2}\s*[-–—]\s*\d{1,2}[:.]\d{2}$""")

private fun sourceWeek(table: Element, week: Int): List<Lesson> {
    val out = mutableListOf<Lesson>()
    var day = -1
    for (row in auditRows(table)) {
        val cells = auditCells(row)
        if (cells.isEmpty()) continue
        var start = 0
        val label = cells.first().text().trim().lowercase()
        val found = auditDays.indexOfFirst { label.startsWith(it) }
        if (found >= 0) { day = found; start = 1 }
        if (day < 0 || start >= cells.size) continue
        val time = cells[start].text().trim()
        if (!auditTime.matches(time)) continue
        val descriptions = cells.drop(start + 1)
        if (descriptions.isEmpty()) continue
        val cell = if (descriptions.size == 1) descriptions.first()
            else descriptions[if (week % 2 == 1) 0 else 1]
        for (part in cell.text().split(';').map(::normal).filter { it.length > 3 }) {
            val clock = Regex("""^(\d{1,2}[:.]\d{2})\s*(?:ч\.?)?\s+(?=[А-Яа-яЁё])""").find(part)
            val slot = clock?.groupValues?.get(1)?.replace('.', ':') ?: time.replace('.', ':').replace(Regex("\\s*[-–—]\\s*"), "-")
            val body = if (clock == null) part else part.substring(clock.range.last + 1).trim()
            if (!body.contains(',') && clock == null && out.lastOrNull()?.let { it.day == day && it.time == slot } == true) {
                val previous = out.removeAt(out.lastIndex)
                out += previous.copy(raw = previous.raw + "; " + body)
            } else out += Lesson(day,slot,body)
        }
    }
    return out
}

fun main(args: Array<String>) {
    val today = LocalDate.of(2026, 10, 21)
    val titles = File(args.single(), "manifest.tsv").readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
    var pages = 0
    var weeklyIssues = 0
    var nativeIssues = 0
    var parseFailures = 0
    for (file in File(args.single()).listFiles().orEmpty().filter {
        it.name.matches(Regex("(?:group|extramural_group)-\\d+\\.html"))
    }.sortedBy { it.name }) {
        val doc = Jsoup.parse(file.readText(), "https://schedule.nspu.ru/")
        val isExtra = file.name.startsWith("extramural")
        val kind = if (isExtra) OfficialSchedulePage.Kind.EXTRAMURAL_GROUP else OfficialSchedulePage.Kind.GROUP
        val id = file.name.substringAfterLast('-').substringBefore('.').toInt()
        val title = titles[file.name].orEmpty()
        val code = title.substringBefore(' ')
        val tables = doc.select("table.rasp_table")
                val primary = runCatching { GroupTimetableParser.parse(doc, code, isExtra) }
        val native = runCatching { PublicTimetableParser.parse(doc, OfficialSchedulePage(kind,id,title),0,today) }
        pages++
        println("PAGE ${file.name} $title tables=${tables.size} primary=${primary.getOrNull()?.lessons?.size ?: primary.exceptionOrNull()?.message} native=${native.getOrNull()?.sessions?.size ?: native.exceptionOrNull()?.message}")
        if (native.isFailure) parseFailures++
        if (!isExtra && primary.isFailure) parseFailures++
        val first = tables.firstOrNull { it.text().contains("Числитель") }
        if (first == null && !isExtra) continue
        if (!isExtra && primary.isSuccess) {
            val parsed = primary.getOrThrow()
            val term = parsed.termStart ?: LocalDate.of(2026,9,1)
            val firstMonday = term.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            var missing = 0
            var extra = 0
            var firstDifference = ""
            for (week in 1..24) {
                val expected = sourceWeek(first!!,week).filter { lesson ->
                    SiteParser.applicable(lesson,firstMonday.plusWeeks(week-1L).plusDays(lesson.day.toLong()),term)
                }.map(::identity).toSet()
                val actual = parsed.lessons.filter { it.date == null }.filter { lesson ->
                    SiteParser.applicable(lesson,firstMonday.plusWeeks(week-1L).plusDays(lesson.day.toLong()),term)
                }.map(::identity).toSet()
                val lost = expected - actual
                val added = actual - expected
                missing += lost.size; extra += added.size
                if (firstDifference.isBlank() && (lost.isNotEmpty() || added.isNotEmpty()))
                    firstDifference = "week=$week missing=${lost.firstOrNull()} extra=${added.firstOrNull()}"
            }
            if (missing + extra > 0) {
                weeklyIssues++
                println("  WEEKLY_DIFF missing=$missing extra=$extra $firstDifference")
            }
            val currentWeek = native.getOrNull()?.week ?: SiteParser.academicWeek(today,term)
            val expectedRaw = sourceWeek(first!!,currentWeek).filter {
                SiteParser.applicable(it,firstMonday.plusWeeks(currentWeek-1L).plusDays(it.day.toLong()),term)
            }.map(::identity).toSet()
            val nativeRaw = native.getOrNull()?.sessions.orEmpty().filter { it.section == PublicSection.WEEKLY }
                .map { identity(Lesson(it.day,it.time,it.description)) }.toSet()
            if (expectedRaw != nativeRaw) {
                nativeIssues++
                println("  NATIVE_DIFF expected=${expectedRaw.size} actual=${nativeRaw.size} missing=${(expectedRaw-nativeRaw).firstOrNull()} extra=${(nativeRaw-expectedRaw).firstOrNull()}")
            }
            val annotations = parsed.lessons.filter { it.raw.contains(Regex("(?iu)с\\s+\\d+[^,;]{0,40}(?:ауд|нед|н\\.)")) }
            for (lesson in annotations.take(2)) println("  ANNOTATION ${lesson.raw}")
            println("  IMPORTANT " + ImportantInfo.notices(parsed.lessons,today,term).joinToString(" / ") { it.text.substringBefore('\n') })
        } else if (isExtra && native.isSuccess) {
            val parsed = native.getOrThrow()
            val badFragments = parsed.sessions.filter { Regex("(?iu)^(?:ст\\.|доц\\.|проф\\.|а\\.)").containsMatchIn(it.subject) }
            if (badFragments.isNotEmpty()) { nativeIssues++;println("  DETACHED_METADATA ${badFragments.size} ${badFragments.first().description}") }
            println("  IMPORTANT " + parsed.sessions.filter { SiteParser.importantKind(it.kind) }.joinToString(" / ") { "${it.date} ${it.time} ${it.kind} ${it.subject}" })
        }
    }
    println("SUMMARY pages=$pages weeklyPagesWithDifferences=$weeklyIssues nativePagesWithDifferences=$nativeIssues parseFailures=$parseFailures")
}
