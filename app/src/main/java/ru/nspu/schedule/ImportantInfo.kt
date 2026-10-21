package ru.nspu.schedule

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Rules recognise facts; the original wording remains visible when a phrase is ambiguous. */
internal object ImportantInfo {
    private const val PREFIX = "schedule-event:"
    private val datePattern = Regex("""(?<!\d)([0-3]?\d)\.([01]?\d)\.(20\d{2})(?!\d)""")
    private val alertWords = Regex("""(?iu)(?<![а-яё])(?:отмен[а-яё]*|перен[её]с[а-яё]*|перенос[а-яё]*|замен[а-яё]*|дистанцион[а-яё]*|онлайн|собрание|тренинг|пересдач[а-яё]*|не\s+будет)(?![а-яё])""")
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    fun dates(raw: String): List<LocalDate> {
        if (!raw.contains(".20")) return emptyList()
        return datePattern.findAll(raw).mapNotNull { m ->
        runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(),
            m.groupValues[1].toInt()) }.getOrNull()
        }.distinct().toList()
    }
    fun isEvent(notice: Notice) = notice.id.startsWith(PREFIX)
    fun date(notice: Notice): LocalDate? = if (isEvent(notice))
        dates(notice.text.substringBefore('\n')).singleOrNull() else null
    fun expired(notice: Notice, today: LocalDate) = date(notice)?.isBefore(today) == true

    fun notices(lessons: List<Lesson>, today: LocalDate, termStart: LocalDate): List<Notice> = lessons.mapNotNull { lesson ->
        val dates = dates(lesson.raw)
        if (dates.isNotEmpty() && dates.all { it.isBefore(today) }) return@mapNotNull null
        val special = SiteParser.specialKind(lesson.raw)
        val keyword = alertWords.find(lesson.raw.substringAfter(',', ""))?.value
        if (special == null && keyword == null && dates.isEmpty()) return@mapNotNull null
        if (dates.isEmpty() && (0L..168L).none {
            val day = today.plusDays(it)
            day.dayOfWeek.value - 1 == lesson.day && SiteParser.applicable(lesson, day, termStart)
        }) return@mapNotNull null
        val label = when {
            keyword != null -> "ВАЖНОЕ УТОЧНЕНИЕ"
            special != null -> SiteParser.displayKind(special)
            else -> "ЗАНЯТИЕ ПО ДАТЕ"
        }
        val day = dates.singleOrNull()
        val heading = "$label · " + (day?.format(formatter) ?: if (dates.isEmpty()) "дата не указана" else "даты в тексте") +
            " · ${lesson.time}"
        // Date/time/room or note edits create a new id, whitespace and case changes do not.
        val canonical = "${lesson.day}|${lesson.time}|${lesson.raw}".trim()
            .replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
        val id = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
        Notice(PREFIX + id, heading + "\n" + lesson.raw)
    }.distinctBy { it.id }.sortedBy { date(it) ?: LocalDate.MAX }
}
