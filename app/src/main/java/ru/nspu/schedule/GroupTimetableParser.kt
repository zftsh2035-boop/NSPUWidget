package ru.nspu.schedule

import org.jsoup.nodes.Document
import java.time.LocalDate

/** Keep the site's cells, week columns and dated session tables separate. */
internal object GroupTimetableParser {
    private val days = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    private val oneTime = Regex("""^\s*(\d{1,2}[:.]\d{2})\s*(?:ч\.?)?\s+(?=[А-Яа-яЁё])""")
    private val academicHeading = Regex("""(?iu)\d+\s+семестр|теоретическое\s+обучение|экзаменационная\s+сессия|каникулы|(?:адаптационная|педагогическая)\s+практика\s+с""")

    fun parse(doc: Document, selectedGroup: String, extramural: Boolean = false): PageSnapshot {
        val flat = doc.body().text()
        val selectedCode = Regex("""\d\.\d{3}\.\d{1,2}\.\d{2}""").find(selectedGroup)?.value ?: selectedGroup
        require(doc.select("h2").any { it.text().startsWith("Группа ") && it.text().contains(selectedCode) }) {
            "Это не страница выбранной группы: $selectedGroup"
        }
        val tables = doc.select("table.rasp_table")
        require(tables.isNotEmpty()) { "На странице не найдены таблицы расписания" }
        val lessons = mutableListOf<Lesson>()
        val notices = mutableListOf<Notice>()
        val info = tables.firstOrNull { it.select("h2").any { h -> h.text().startsWith("Группа ") } }
        if (info != null) {
            val banner = PublicTimetableParser.directRows(info).flatMap { PublicTimetableParser.directCells(it) }
                .filter { it.select("h2").isEmpty() }.joinToString(" ") { it.text() }
            val end = academicHeading.find(banner)?.range?.first ?: banner.length
            notices += SiteParser.parseBanner(banner.take(end))
        }
        for (table in tables.filter { it.text().contains("Числитель") && !PublicTimetableParser.hasCalendarRows(it) }) {
            var day = -1
            for (row in PublicTimetableParser.directRows(table)) {
                val cells = PublicTimetableParser.directCells(row)
                if (cells.isEmpty()) continue
                var start = 0
                val found = days.indexOfFirst { cells.first().text().trim().lowercase().startsWith(it) }
                if (found >= 0) { day = found; start = 1 }
                if (day < 0 || start >= cells.size) continue
                val slot = PublicTimetableParser.time(cells[start].text())
                if (slot == null) {
                    val note = cells.drop(start).joinToString(" ") { it.text() }.trim()
                    if (note.isNotBlank()) notices += SiteParser.parseBanner("${days[day]}: $note")
                    continue
                }
                val columns = cells.drop(start + 1)
                for ((index, cell) in columns.withIndex()) {
                    val parity = if (columns.size == 1 || cell.attr("colspan") == "2") 0 else index + 1
                    for (piece in PublicTimetableParser.cellText(cell).split(';')) {
                        var raw = piece.trim().trim(',', ' ', '\u00a0')
                        if (raw.length < 4 || raw == "—") continue
                        val clock = oneTime.find(raw)
                        val time = clock?.groupValues?.get(1)?.replace('.', ':') ?: slot
                        if (clock != null) raw = raw.substring(clock.range.last + 1).trim()
                        // Free text following a semicolon belongs to the preceding lesson.
                        if (!raw.contains(',') && clock == null && lessons.lastOrNull()?.let {
                                it.day == day && it.time == slot && it.parity == parity } == true) {
                            val previous = lessons.removeAt(lessons.lastIndex)
                            lessons += previous.copy(raw = previous.raw + "; " + raw)
                        } else lessons += Lesson(day, time, raw, parity = parity)
                    }
                }
            }
        }
        val kind = if (extramural) OfficialSchedulePage.Kind.EXTRAMURAL_GROUP else OfficialSchedulePage.Kind.GROUP
        val dated = PublicTimetableParser.parse(doc, OfficialSchedulePage(kind,1,selectedGroup),0)
            .sessions.filter { it.section != PublicSection.WEEKLY }
        lessons += dated.map { Lesson(it.day,it.time,it.description,date = it.date) }
        return PageSnapshot(lessons.distinctBy { listOf(it.day,it.time,it.raw,it.parity,it.date) },
            notices.distinctBy { it.id },SiteParser.termStart(flat))
    }
}
