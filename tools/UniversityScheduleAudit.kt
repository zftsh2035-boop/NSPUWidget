package ru.nspu.schedule

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.time.LocalDate

/** Independent date/clock-cell coverage checks for ALL saved catalogue pages.
 * Unlike a screenshot comparison, this checks both Android parsers against the
 * original cells, and verifies that dated lessons cannot recur a week later.
 */
private val auditMonths = listOf("января","февраля","марта","апреля","мая","июня",
    "июля","августа","сентября","октября","ноября","декабря")
private val dateWords = Regex("""^(\d{1,2})\s+([а-яё]+)\s+(20\d{2})""",RegexOption.IGNORE_CASE)
private val dateNumbers = Regex("""^(\d{1,2})\.(\d{1,2})\.(20\d{2}|\d{2})(?!\d)""")
private val sourceClock = Regex("""^\d{1,2}[:.]\d{2}(?:\s*[-–—]\s*\d{1,2}[:.]\d{2})?$""")
private val sourceOverride = Regex("""^(\d{1,2}[:.]\d{2}(?:\s*[-–—]\s*\d{1,2}[:.]\d{2})?)\s*(?:ч\.?)?\s+(?=[А-Яа-яЁё])""")
private fun sourceDate(value: String): LocalDate? {
    val text = value.trim()
    val words = dateWords.find(text)
    val numeric = dateNumbers.find(text)
    return runCatching {
        if (words != null) LocalDate.of(words.groupValues[3].toInt(),
            auditMonths.indexOf(words.groupValues[2].lowercase())+1,words.groupValues[1].toInt())
        else if (numeric != null) LocalDate.of(numeric.groupValues[3].toInt().let { if (it<100) 2000+it else it },
            numeric.groupValues[2].toInt(),numeric.groupValues[1].toInt()) else null
    }.getOrNull()
}
private fun auditRowsDirect(table: Element) = table.children().flatMap {
    if (it.tagName() in listOf("tbody","thead")) it.children().filter { row -> row.tagName()=="tr" }
    else if (it.tagName()=="tr") listOf(it) else emptyList()
}
private fun auditCellsDirect(table: Element) = auditRowsDirect(table)
    .flatMap { it.children().filter { cell -> cell.tagName() in listOf("td","th") } }
private fun normalizedWords(text: String) = text
    .replace(Regex("""\s+\(https?://[^)]+\)"""),"").lowercase().filter { it.isLetterOrDigit() }
private fun normalizedClock(text: String) = text.replace('.',':').replace(Regex("\\s*[-–—]\\s*"),"-")
private fun datedKey(date: LocalDate?, time: String) = "$date|${normalizedClock(time)}"

fun main(args: Array<String>) {
    val directory = File(args[0])
    val today = LocalDate.parse(args.getOrElse(1) { "2026-10-22" })
    val manifest = File(directory,"manifest.tsv").readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
    var pages=0; var noTables=0; var failures=0; var issues=0; var calendarPages=0; var datedLessons=0
    val kinds = sortedMapOf<String,Int>()
    val output = StringBuilder("file\ttitle\ttables\tlessons\tdated\tstatus\n")
    val details = StringBuilder()
    fun issue(file: String, message: String) { issues++; details.appendLine("$file\t$message") }
    for ((filename,title) in manifest) {
        val file = File(directory,filename)
        if (!file.isFile) continue // download-summary.json separately accounts for transport failures.
        pages++
        val doc = Jsoup.parse(file.readText(),"https://schedule.nspu.ru/")
        val tables = doc.select("table.rasp_table")
        val extra = filename.startsWith("extramural")
        if (tables.isEmpty()) { noTables++; output.appendLine("$filename\t$title\t0\t0\t0\tNO_TABLES");continue }
        val parsed = runCatching { GroupTimetableParser.parse(doc,title,extra) }
        if (parsed.isFailure) {
            failures++; output.appendLine("$filename\t$title\t${tables.size}\t0\t0\tPARSE_FAILURE")
            details.appendLine("$filename\t${parsed.exceptionOrNull()?.message}"); continue
        }
        val primary = parsed.getOrThrow()
        val page = OfficialSchedulePage(if (extra) OfficialSchedulePage.Kind.EXTRAMURAL_GROUP else OfficialSchedulePage.Kind.GROUP,
            filename.substringAfterLast('-').substringBefore('.').toInt(),title)
        val native = runCatching { PublicTimetableParser.parse(doc,page,0,today) }
        if (native.isFailure) { issue(filename,"Native parser: ${native.exceptionOrNull()?.message}");continue }
        val dated = primary.lessons.filter { it.date != null }
        datedLessons += dated.size
        val nativeDated = native.getOrThrow().sessions.filter { it.section != PublicSection.WEEKLY }
        val actual = dated.groupBy { datedKey(it.date,it.time) }
            .mapValues { (_,lessons) -> lessons.joinToString(";") { it.raw } }
        val expected = linkedMapOf<String,MutableList<String>>()
        for (table in tables) {
            val cells = auditCellsDirect(table)
            if (cells.none { sourceDate(it.text()) != null }) continue
            var date: LocalDate? = null
            var time: String? = null
            for (cell in cells) {
                val text = cell.text().trim()
                val foundDate = sourceDate(text)
                if (foundDate != null) { date=foundDate;time=null;continue }
                if (sourceClock.matches(text)) { time=normalizedClock(text);continue }
                if (date != null && time != null && text.length>3 && text!="—") {
                    for (part in text.split(';').map { it.trim() }.filter { it.length>3 }) {
                        val explicit = sourceOverride.find(part)
                        val stated = Regex("""(?iu)\bначало\s*[-–—:]?\s*(\d{1,2}[:.]\d{2})""").find(part)?.groupValues?.get(1)
                        val slot = stated ?: explicit?.groupValues?.get(1) ?: time
                        val body = if (explicit != null) part.substring(explicit.range.last+1) else part
                        expected.getOrPut(datedKey(date,slot)) { mutableListOf() }.add(body)
                    }
                }
            }
        }
        if (expected.isNotEmpty()) calendarPages++
        if (expected.keys != actual.keys) issue(filename,"Calendar slots: missing=${(expected.keys-actual.keys).take(3)} extra=${(actual.keys-expected.keys).take(3)}")
        for ((key,source) in expected) {
            val sourceWords = normalizedWords(source.distinct().joinToString(";"))
            val actualWords = normalizedWords(actual[key].orEmpty())
            if (sourceWords != actualWords) issue(filename,"Calendar cell text at $key: expected=${sourceWords.take(160)} actual=${actualWords.take(160)}")
        }
        val primaryKeys = dated.map { "${it.date}|${it.time}|${normalizedWords(it.raw)}" }.toSet()
        val nativeKeys = nativeDated.map { "${it.date}|${it.time}|${normalizedWords(it.description)}" }.toSet()
        if (primaryKeys != nativeKeys) issue(filename,"Main/native dated mismatch: ${primaryKeys.size}/${nativeKeys.size}")
        for (lesson in primary.lessons) {
            val date = lesson.date ?: today
            val kind = SiteParser.lessonKind(lesson,date,primary.termStart ?: LocalDate.of(2026,9,1))
            kinds[kind] = kinds.getOrDefault(kind,0)+1
            if (lesson.date != null && (SiteParser.applicable(lesson,lesson.date.minusWeeks(1)) ||
                    SiteParser.applicable(lesson,lesson.date.plusWeeks(1)))) issue(filename,"Dated lesson repeated: ${lesson.raw.take(100)}")
            if (sourceClock.matches(lesson.raw)) issue(filename,"Clock became a subject: ${lesson.raw}")
            if (Regex("""(?iu)\(\s*(?:л\.|п\.з\.|л\.з\.)\s*\)""").containsMatchIn(lesson.raw) && kind=="ЗАНЯТИЕ")
                issue(filename,"Unrecognized ordinary type: ${lesson.raw.take(120)}")
        }
        output.appendLine("$filename\t${title.replace('\t',' ')}\t${tables.size}\t${primary.lessons.size}\t${dated.size}\tOK")
    }
    File(directory,"calendar-audit.tsv").writeText(output.toString())
    File(directory,"calendar-issues.tsv").writeText(details.toString())
    println("SUMMARY pages=$pages calendarPages=$calendarPages datedLessons=$datedLessons noTables=$noTables parseFailures=$failures issues=$issues")
    println("KINDS $kinds")
    println(details.toString().take(12000))
}
