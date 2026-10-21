package ru.nspu.schedule

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Pure parsing code: independently testable without Android or network access. */
data class Lesson(val day: Int, val time: String, val raw: String, val changed: Boolean = false,
    val parity: Int = 0, val date: LocalDate? = null)
data class Notice(val id: String, val text: String, val published: String? = null, val isNew: Boolean = false)
data class PageSnapshot(val lessons: List<Lesson>, val notices: List<Notice>, val termStart: LocalDate? = null)

object SiteParser {
    private val weekdays = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота")
    private val timePattern = Regex("(?<!\\d)(?:[01]?\\d|2[0-2])[:.]\\d{2}\\s*[-–—]\\s*(?:[01]?\\d|2[0-2])[:.]\\d{2}")
    private val weekdayPattern = Regex("(?<![А-Яа-яЁё])(?:Понедельник|Вторник|Среда|Четверг|Пятница|Суббота)(?![А-Яа-яЁё])")
    private val noticeHeading = Regex("Уважаемые\\s+студенты\\s*!|ВНИМАНИЮ\\s+СТУДЕНТОВ\\s*!*|ОБЪЯВЛЕНИЕ\\s*[:!]", RegexOption.IGNORE_CASE)
    private val datePattern = Regex("(?<!\\d)([0-3]?\\d)\\.([01]?\\d)\\.(20\\d{2})(?:\\s*г\\.)?")
    private val weeksPattern = Regex("\\(([^)]*нед\\.[^)]*)\\)", RegexOption.IGNORE_CASE)
    private val subgroupPattern = Regex("(?<![\\p{L}\\d])(?:А|A)\\s*(10|[1-9])(?!\\d)")
    private val weekNumbers = Regex("(\\d+)(?:\\s*[-–—]\\s*(\\d+))?")
    private val languagePattern = Regex("""(?iu)иностранный язык|английский язык|немецкий язык|французский язык|испанский язык""")
    private val examPattern = Regex("""(?iu)(?<![а-яё])(?:экзамен|экз\.?)\b""")
    private val creditPattern = Regex("""(?iu)(?<![а-яё])(?:зач[её]т|зач\.?)\b""")
    private val gradedCreditPattern = Regex("""(?iu)(?:\(\s*з\s*\.\s*о\s*\.\s*\)|зач[её]т\s+с\s+оценкой|дифференцированный\s+зач[её]т)""")
    private val consultationPattern = Regex("""(?iu)(?:\(\s*конс\.\s*\)|(?<![а-яё])консультация(?![а-яё]))""")
    private val courseworkPattern = Regex("""(?iu)(?:\(\s*(?:курс|к)\.?\s*р\.?\s*\)|защита\s+(?:курсовой|диплома|проекта))""")
    private val typePartsPattern = Regex("""(л\.з\.|п\.з\.|л\.)\s*\)?\s*:\s*([0-9,\s\-–—]+)\s*нед\.""", RegexOption.IGNORE_CASE)
    private val plainTypePattern = Regex("""(?iu)\(\s*(л\s*\.\s*з\s*\.|п\s*\.\s*з\s*\.|л\s*\.)\s*\)""")
    private val rangePart = Regex("""\d+\s*[-–—]\s*\d+|\d+""")
    private val numberPattern = Regex("""\d+""")
    private val roomPattern = Regex("""(?i)(?:^|[\s,(;])(?:аудитория|ауд\.?|а\.|кабинет|каб\.?)\s*([^,;()]+)""")
    private val statedStart = Regex("""(?iu)\bначало\s*[-–—:]?\s*(\d{1,2}[:.]\d{2})""")

    fun parse(text: String, selectedGroup: String = "3.008.2.26"): PageSnapshot {
        val flat = text.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
        require(flat.contains("Группа " + selectedGroup) || flat.contains(">> " + selectedGroup)) {
            "Это не страница выбранной группы: " + selectedGroup
        }
        val notices = parseNotices(flat, selectedGroup)
        val weekMarker = flat.indexOf("Числитель", ignoreCase = true)
        require(weekMarker >= 0) { "На странице не найдено расписание по дням" }
        val end = flat.indexOf("Последнее изменение расписания", weekMarker).let { if (it >= 0) it else flat.length }
        val schedule = flat.substring(weekMarker, end)
        val dayHeaders = weekdayPattern.findAll(schedule).toList()
        require(dayHeaders.isNotEmpty()) { "На странице не найдены дни недели" }
        val entries = mutableListOf<Lesson>()
        for ((i, dayMatch) in dayHeaders.withIndex()) {
            val dayIndex = weekdays.indexOf(dayMatch.value)
            if (dayIndex < 0) continue
            val start = dayMatch.range.last + 1
            val stop = dayHeaders.getOrNull(i + 1)?.range?.first ?: schedule.length
            val dayBlock = schedule.substring(start, stop)
            val slots = timePattern.findAll(dayBlock).toList()
            for ((slotIndex, slot) in slots.withIndex()) {
                val next = slots.getOrNull(slotIndex + 1)?.range?.first ?: dayBlock.length
                val body = dayBlock.substring(slot.range.last + 1, next).trim()
                val time = slot.value.replace('.', ':').replace(Regex("\\s*[-–—]\\s*"), "-")
                // On the NSPU page the semicolon separates different subjects/subgroups in one slot.
                body.split(';').map { it.trim().trim(',', ' ') }.filter { it.length > 10 }.forEach { raw ->
                    entries.add(Lesson(dayIndex, time, raw.take(900)))
                }
            }
        }
        require(entries.isNotEmpty()) { "В расписании не найдено занятий; формат страницы мог измениться" }
        return PageSnapshot(entries.distinctBy { key(it) + "/" + it.raw }, notices, termStart(flat))
    }

    fun parseNotices(flat: String, selectedGroup: String = "3.008.2.26"): List<Notice> {
        val beforeSemester = Regex("(?<!\\d)[12]\\s+семестр(?![А-Яа-яЁё])", RegexOption.IGNORE_CASE).find(flat)?.range?.first ?: -1
        if (beforeSemester < 0) return emptyList()
        val preamble = flat.substring(0, beforeSemester)
        val lastGroup = preamble.lastIndexOf(selectedGroup)
        if (lastGroup < 0) return emptyList()
        val banner = preamble.substring(lastGroup + selectedGroup.length).trim()
        return parseBanner(banner)
    }

    internal fun parseBanner(banner: String): List<Notice> {
        val headings = noticeHeading.findAll(banner).toList()
        // The university sometimes publishes a banner without the usual salutation.
        val pieces = if (headings.isEmpty()) {
            if (banner.length > 22) listOf(banner) else emptyList()
        } else headings.mapIndexed { index, match ->
            val start = match.range.first
            val end = headings.getOrNull(index + 1)?.range?.first ?: banner.length
            banner.substring(start, end)
        }
        return pieces.mapNotNull { piece ->
            val message = piece.trim().replace(Regex("\\s+"), " ")
            if (message.length < 22) null else {
                // Dates in the text are NOT necessarily publication dates.
                Notice(hash(message), message)
            }
        }.distinctBy { it.id }
    }

    /** The university publishes the dates for each semester in the page header. */
    fun termStart(text: String): LocalDate? {
        val heading = Regex("""(?iu)теоретическое\s+обучение""").find(text) ?: return null
        val after = text.substring(heading.range.last + 1).take(400)
        val end = Regex("""(?iu)экзаменационная\s+сессия|каникулы|нерабочие|числитель""")
            .find(after)?.range?.first ?: after.length
        return ImportantInfo.dates(after.take(end)).minOrNull()
    }

    fun academicWeek(date: LocalDate, start: LocalDate = LocalDate.of(2026, 9, 1)): Int {
        val firstMonday = start.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return (Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(firstMonday, date), 7L) + 1).toInt()
    }

    fun applicable(lesson: Lesson, date: LocalDate, termStart: LocalDate = LocalDate.of(2026, 9, 1)): Boolean {
        fixedDate(lesson)?.let { return date == it }
        if (date < termStart) return false
        val week = academicWeek(date, termStart)
        if (week !in 1..24) return false
        if (lesson.parity == 1 && week % 2 != 1 || lesson.parity == 2 && week % 2 != 0) return false
        // "с 15.09.26" starts a recurring practice, rather than limiting it to one day.
        val explicit = ImportantInfo.dates(lesson.raw)
        if (explicit.size == 1 && Regex("(?iu)\\bс\\s+\\d{1,2}\\.\\d{1,2}\\.").containsMatchIn(lesson.raw)
            && date < explicit.single()) return false
        return weekMatches(lesson.raw, week)
    }

    fun fixedDate(lesson: Lesson): LocalDate? {
        lesson.date?.let { return it }
        if (weeksPattern.containsMatchIn(lesson.raw) || typePartsPattern.containsMatchIn(lesson.raw) ||
            Regex("(?iu)\\b(?:с|по|до)\\s+\\d{1,2}\\.\\d{1,2}\\.").containsMatchIn(lesson.raw)) return null
        return ImportantInfo.dates(lesson.raw).singleOrNull()
    }

    internal fun weekMatches(raw: String, week: Int): Boolean {
        val parts = typePartsPattern.findAll(raw).toList()
        // A practice block in the annotation excludes ordinary lectures/practicals.
        if (parts.isNotEmpty()) return parts.any { part ->
            weekNumbers.findAll(part.groupValues[2]).any { m ->
                val first = m.groupValues[1].toInt()
                week in first..(m.groupValues[2].toIntOrNull() ?: first)
            }
        }
        val weeks = weeksPattern.find(raw)?.groupValues?.get(1) ?: return true
        return weekNumbers.findAll(weeks).any { m ->
            val first = m.groupValues[1].toInt()
            val last = m.groupValues[2].toIntOrNull() ?: first
            week in first..last
        }
    }

    /** Only codes actually mentioned next to a language lesson are selectable. */
    fun languageOptions(lessons: List<Lesson>): List<String> {
        val languageLessons = lessons.filter {
            languagePattern.containsMatchIn(it.raw)
        }
        val groups = languageLessons.flatMap { subgroupPattern.findAll(it.raw).map { m -> "А" + m.groupValues[1] }.toList() }
            .distinct().sortedBy { it.drop(1).toIntOrNull() ?: 999 }
        val german = if (languageLessons.any { it.raw.contains("Немецкий язык", true) }) listOf("Немецкий") else emptyList()
        return groups + german
    }

    fun subgroupFits(raw: String, subgroup: String): Boolean {
        if (subgroup == "Все") return true
        if (raw.contains("Немецкий язык", true)) return subgroup == "Немецкий"
        val codes = subgroupPattern.findAll(raw).map { "А" + it.groupValues[1] }.toList()
        return codes.isEmpty() || codes.contains(subgroup)
    }

    // Room/time changes should compare the same subject at the same point in the week.
    fun key(l: Lesson): String {
        val name = l.raw.substringBefore(',').trim().lowercase()
        val subgroup = subgroupPattern.find(l.raw)?.groupValues?.get(1) ?: if (l.raw.contains("Немецкий язык", true)) "de" else "all"
        return "${l.day}|${l.time}|$name|$subgroup" +
            (if (l.parity != 0) "|${l.parity}" else "") + (l.date?.let { "|$it" } ?: "")
    }

    fun subgroupKey(l: Lesson): String {
        if (!languagePattern.containsMatchIn(l.raw.substringBefore(','))) return "all"
        val withoutRoom = roomPattern.find(l.raw)?.range?.first?.let { l.raw.take(it) } ?: l.raw
        return subgroupPattern.findAll(withoutRoom).map { it.groupValues[1].toInt() }
            .distinct().sorted().joinToString(",")
            .ifEmpty { if (l.raw.contains("Немецкий язык", true)) "de" else "all" }
    }

    /** Arbitrary notices have no reliable expiry rule. The site controls visibility. */
    @Suppress("UNUSED_PARAMETER")
    fun noticeIsOutdated(notice: Notice, today: LocalDate): Boolean = ImportantInfo.expired(notice, today)
    fun noticeLabel(notice: Notice): String = if (notice.isNew) "● НОВОЕ" else "ОБЪЯВЛЕНИЕ НГПУ"

    fun formattedNoticeDate(@Suppress("UNUSED_PARAMETER") notice: Notice): String = "На сайте НГПУ"


    /** Resolves multiple event types in one official slot for the selected calendar week. */
    fun lessonKind(lesson: Lesson, date: LocalDate, termStart: LocalDate = LocalDate.of(2026, 9, 1)): String {
        val raw = lesson.raw
        specialKind(raw)?.let { return it }
        val week = academicWeek(date, termStart)
        val typeParts = typePartsPattern.findAll(raw).toList()
        val matched = typeParts.firstOrNull { match ->
            rangePart.findAll(match.groupValues[2]).any { part ->
                val ends = numberPattern.findAll(part.value).map { it.value.toInt() }.toList()
                week in ends.first()..ends.last()
            }
        }?.groupValues?.get(1) ?: typeParts.firstOrNull()?.groupValues?.get(1)
            ?: plainTypePattern.find(raw)?.groupValues?.get(1)
        return when (matched?.lowercase()?.replace(Regex("\\s+"), "")) {
            "л." -> "ЛЕКЦИЯ"
            "п.з." -> "ПРАКТИКА"
            "л.з." -> "ЛАБОРАТОРНАЯ"
            else -> "ЗАНЯТИЕ"
        }
    }

    data class LessonInfo(val subject: String, val teacher: String, val room: String, val kind: String)

    /** A stated start time overrides the grid; no end time is guessed. */
    internal fun statedTime(raw: String): String? = statedStart.find(raw)?.groupValues?.get(1)?.replace('.', ':')

    fun details(l: Lesson, date: LocalDate = LocalDate.now(), termStart: LocalDate = LocalDate.of(2026, 9, 1)): LessonInfo {
        val subject = l.raw.substringBefore(',').trim()
        val teacherFragment = l.raw.substringAfter(',', "").substringBefore(",").substringBefore("(").trim()
        val teacher = teacherFragment.take(60).ifBlank { "Не указан" }
        val room = roomPattern.find(l.raw)?.groupValues?.get(1)?.trim()
            ?.replace(Regex("""(?iu)\s+(?:начало\b|\d{1,2}[:.]\d{2}).*$"""), "")?.take(30) ?: "—"
        return LessonInfo(subject, teacher, room, lessonKind(l, date, termStart))
    }

    fun lessonColor(kind: String): Int = when (kind) {
        "ЛЕКЦИЯ" -> 0xFF6DBEFF.toInt()
        "ПРАКТИКА" -> 0xFF4BE1CD.toInt()
        "ЛАБОРАТОРНАЯ" -> 0xFFC3A9FF.toInt()
        "ЗАЧЁТ", "ЗАЧЁТ С ОЦЕНКОЙ" -> 0xFFFFCD80.toInt()
        "ЭКЗАМЕН" -> 0xFFFF92A6.toInt()
        "КОНСУЛЬТАЦИЯ", "КУРСОВАЯ" -> 0xFFD4A6FF.toInt()
        else -> 0xFFA9BEDB.toInt()
    }

    fun shortKind(kind: String): String = when (kind) {
        "ЛЕКЦИЯ" -> "л."
        "ПРАКТИКА" -> "п.з."
        "ЛАБОРАТОРНАЯ" -> "л.з."
        "ЗАЧЁТ" -> "зач."
        "ЗАЧЁТ С ОЦЕНКОЙ" -> "з.о."
        "ЭКЗАМЕН" -> "экз."
        "КОНСУЛЬТАЦИЯ" -> "конс."
        "КУРСОВАЯ" -> "курс.р."
        else -> "—"
    }

    fun specialKind(raw: String): String? = when {
        examPattern.containsMatchIn(raw) -> "ЭКЗАМЕН"
        gradedCreditPattern.containsMatchIn(raw) -> "ЗАЧЁТ С ОЦЕНКОЙ"
        creditPattern.containsMatchIn(raw) -> "ЗАЧЁТ"
        consultationPattern.containsMatchIn(raw) -> "КОНСУЛЬТАЦИЯ"
        courseworkPattern.containsMatchIn(raw) -> "КУРСОВАЯ"
        else -> null
    }
    fun importantKind(kind: String) = kind in setOf("ЭКЗАМЕН", "ЗАЧЁТ", "ЗАЧЁТ С ОЦЕНКОЙ", "КОНСУЛЬТАЦИЯ", "КУРСОВАЯ")
    fun displayKind(kind: String) = if (importantKind(kind)) kind else shortKind(kind)

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.lowercase().toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
