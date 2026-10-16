package ru.nspu.schedule

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Pure parsing code: independently testable without Android or network access. */
data class Lesson(val day: Int, val time: String, val raw: String, val changed: Boolean = false)
data class Notice(val id: String, val text: String, val published: String? = null, val isNew: Boolean = false)
data class PageSnapshot(val lessons: List<Lesson>, val notices: List<Notice>)

object SiteParser {
    private val weekdays = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота")
    private val timePattern = Regex("(?<!\\d)(?:[01]?\\d|2[0-2])[:.]\\d{2}\\s*[-–—]\\s*(?:[01]?\\d|2[0-2])[:.]\\d{2}")
    private val weekdayPattern = Regex("(?<![А-Яа-яЁё])(?:Понедельник|Вторник|Среда|Четверг|Пятница|Суббота)(?![А-Яа-яЁё])")
    private val noticeHeading = Regex("Уважаемые\\s+студенты\\s*!|ВНИМАНИЮ\\s+СТУДЕНТОВ\\s*!*|ОБЪЯВЛЕНИЕ\\s*[:!]", RegexOption.IGNORE_CASE)
    private val datePattern = Regex("(?<!\\d)([0-3]?\\d)\\.([01]?\\d)\\.(20\\d{2})(?:\\s*г\\.)?")
    private val weeksPattern = Regex("\\(([^)]*нед\\.[^)]*)\\)", RegexOption.IGNORE_CASE)
    private val subgroupPattern = Regex("(?<![\\p{L}\\d])(?:А|A)\\s*(10|[1-9])(?!\\d)")

    fun parse(text: String): PageSnapshot {
        val flat = text.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
        require(flat.contains("Группа 3.008.2.26") || flat.contains("3.008.2.26 ИСиТвО")) {
            "Это не страница группы 3.008.2.26"
        }
        val notices = parseNotices(flat)
        val weekMarker = flat.indexOf("Числитель", ignoreCase = true)
        require(weekMarker >= 0) { "На странице не найдено расписание по дням" }
        val end = flat.indexOf("Последнее изменение расписания", weekMarker).let { if (it >= 0) it else flat.length }
        val schedule = flat.substring(weekMarker, end)
        val dayHeaders = weekdayPattern.findAll(schedule).toList()
        require(dayHeaders.size >= 5) { "На странице не найдены дни недели" }
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
        require(entries.size >= 12) { "Слишком мало занятий (${entries.size}): формат страницы мог измениться" }
        return PageSnapshot(entries.distinctBy { key(it) + "/" + it.raw }, notices)
    }

    fun parseNotices(flat: String): List<Notice> {
        val beforeSemester = Regex("(?<!\\d)1\\s+семестр(?![А-Яа-яЁё])", RegexOption.IGNORE_CASE).find(flat)?.range?.first ?: -1
        if (beforeSemester < 0) return emptyList()
        val preamble = flat.substring(0, beforeSemester)
        val lastGroup = preamble.lastIndexOf("3.008.2.26")
        if (lastGroup < 0) return emptyList()
        val banner = preamble.substring(lastGroup + "3.008.2.26".length).trim()
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
                val matchDate = datePattern.find(message)
                val published = matchDate?.let { d ->
                    try {
                        LocalDate.of(d.groupValues[3].toInt(), d.groupValues[2].toInt(), d.groupValues[1].toInt()).toString()
                    } catch (_: Exception) { null }
                }
                Notice(hash(message), message, published)
            }
        }.distinctBy { it.id }
    }

    fun applicable(lesson: Lesson, date: LocalDate): Boolean {
        val week = ((java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2026, 8, 31), date) / 7) + 1).toInt()
        if (week !in 1..16) return false
        val weeks = weeksPattern.find(lesson.raw)?.groupValues?.get(1)
        if (weeks == null) {
            // Dated exams/consultations appear outside a normal week range.
            val dateMatch = datePattern.find(lesson.raw) ?: return true
            val d = dateMatch.groupValues
            return date.dayOfMonth == d[1].toInt() && date.monthValue == d[2].toInt() && date.year == d[3].toInt()
        }
        return Regex("(\\d+)(?:\\s*-\\s*(\\d+))?").findAll(weeks).any { m ->
            val first = m.groupValues[1].toInt()
            val last = m.groupValues[2].toIntOrNull() ?: first
            week in first..last
        }
    }

    fun subgroupFits(raw: String, subgroup: String): Boolean {
        if (raw.contains("Немецкий язык", true)) return subgroup == "Немецкий"
        val codes = subgroupPattern.findAll(raw).map { "А" + it.groupValues[1] }.toList()
        return codes.isEmpty() || codes.contains(subgroup)
    }

    // Room/time changes should compare the same subject at the same point in the week.
    fun key(l: Lesson): String {
        val name = l.raw.substringBefore(',').trim().lowercase()
        val subgroup = subgroupPattern.find(l.raw)?.groupValues?.get(1) ?: if (l.raw.contains("Немецкий язык", true)) "de" else "all"
        return "${l.day}|${l.time}|$name|$subgroup"
    }

    fun noticeIsOutdated(notice: Notice, today: LocalDate): Boolean =
        notice.published?.let { runCatching { LocalDate.parse(it).isBefore(today.minusDays(7)) }.getOrDefault(false) } ?: false

    fun formattedNoticeDate(notice: Notice): String = notice.published?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.forLanguageTag("ru-RU"))) }.getOrDefault(it)
    } ?: "Дата не указана"

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.lowercase().toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
