package ru.nspu.schedule

import java.time.LocalDate

data class ChangedRange(val start: Int, val end: Int)
data class ChangeRecord(val message: String, val highlights: List<ChangedRange> = emptyList()) {
    fun prefixed(prefix: String) = ChangeRecord(prefix + message,
        highlights.map { ChangedRange(it.start + prefix.length, it.end + prefix.length) })
}
data class ScheduleChange(val message: String, val lessonKey: String, val lesson: Lesson? = null,
    val highlights: List<ChangedRange> = emptyList()) {
    fun record() = ChangeRecord(message, highlights)
}

/** The history stores offsets, never markup mixed into the university's wording. */
object ChangeLog {
    private fun normalized(value: String) = value.trim().replace(Regex("""\s+"""), " ").lowercase()
    private fun base(l: Lesson) = normalized(l.raw.substringBefore(',')) + "|" + SiteParser.subgroupKey(l)

    /** Word-level LCS keeps every unchanged fragment in ordinary type on BOTH sides. */
    private fun edits(before: String, after: String): Pair<List<ChangedRange>, List<ChangedRange>> {
        val token = Regex("""\S+|\s+""")
        val a = token.findAll(before).toList()
        val b = token.findAll(after).toList()
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i].value == b[j].value) lengths[i+1][j+1] + 1
                else maxOf(lengths[i+1][j], lengths[i][j+1])
        }
        val matchedA = mutableSetOf<Int>()
        val matchedB = mutableSetOf<Int>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i].value == b[j].value) { matchedA += i++; matchedB += j++ }
            else if (lengths[i+1][j] >= lengths[i][j+1]) i++ else j++
        }
        fun ranges(tokens: List<MatchResult>, matched: Set<Int>) = tokens.mapIndexedNotNull { n, t ->
            if (n in matched || t.value.isBlank()) null else ChangedRange(t.range.first, t.range.last + 1)
        }
        return ranges(a, matchedA) to ranges(b, matchedB)
    }

    internal fun replacement(label: String, before: String, after: String): ChangeRecord {
        val prefix = "$label "
        val separator = " → "
        val (a, b) = edits(before, after)
        return ChangeRecord(prefix + before + separator + after,
            a.map { ChangedRange(it.start + prefix.length, it.end + prefix.length) } +
            b.map { ChangedRange(it.start + prefix.length + before.length + separator.length,
                it.end + prefix.length + before.length + separator.length) })
    }

    /** Older versions saved plain strings; recognise only their explicit field arrows. */
    internal fun legacyRecord(message: String): ChangeRecord {
        val field = Regex("""(Время|Дата|Недели|Ауд\.|Преподаватель|Тип)\s+([^;]+?)\s+→\s+([^;]+)(?=;|$)""")
        val ranges = field.findAll(message).flatMap { match ->
            val before = match.groups[2]!!
            val after = match.groups[3]!!
            val (a,b) = edits(before.value, after.value)
            a.map { ChangedRange(it.start + before.range.first, it.end + before.range.first) } +
                b.map { ChangedRange(it.start + after.range.first, it.end + after.range.first) }
        }.toList()
        return ChangeRecord(message, ranges)
    }

    private fun joined(parts: List<ChangeRecord>, separator: String): ChangeRecord {
        val text = StringBuilder()
        val spans = mutableListOf<ChangedRange>()
        parts.forEachIndexed { i, p ->
            if (i > 0) text.append(separator)
            val offset = text.length
            spans += p.highlights.map { ChangedRange(it.start + offset, it.end + offset) }
            text.append(p.message)
        }
        return ChangeRecord(text.toString(), spans)
    }

    fun diff(old: List<Lesson>, fresh: List<Lesson>, date: LocalDate, termStart: LocalDate): List<ScheduleChange> {
        if (old.isEmpty()) return emptyList()
        val oldGroups = old.groupBy(::base)
        val freshGroups = fresh.groupBy(::base)
        val changes = mutableListOf<ScheduleChange>()
        for (key in (oldGroups.keys + freshGroups.keys)) {
            val previous = oldGroups[key].orEmpty().toMutableList()
            val incoming = freshGroups[key].orEmpty().toMutableList()
            // Unchanged occurrences must not be paired with an added or removed row.
            previous.toList().forEach { a ->
                val match = incoming.firstOrNull { b -> a.day == b.day && a.time == b.time && a.date == b.date &&
                    a.parity == b.parity && normalized(a.raw) == normalized(b.raw) }
                if (match != null) { previous.remove(a); incoming.remove(match) }
            }
            val order = compareBy<Lesson> { it.date ?: LocalDate.MAX }.thenBy { it.day }.thenBy {
                val clock = it.time.substringBefore('-').split(':')
                (clock.firstOrNull()?.toIntOrNull() ?: 99) * 60 + (clock.getOrNull(1)?.toIntOrNull() ?: 0)
            }.thenBy { it.parity }
            val a = previous.sortedWith(order)
            val b = incoming.sortedWith(order)
            for (i in 0 until maxOf(a.size,b.size)) {
                val before = a.getOrNull(i)
                val after = b.getOrNull(i)
                val label = (after ?: before)!!.raw.substringBefore(',')
                val record = when {
                    before == null && after != null -> {
                        val value = listOf(after.time, after.raw).filter(String::isNotBlank).joinToString(" · ")
                        val prefix = "Добавлено: "
                        ChangeRecord(prefix + value, listOf(ChangedRange(prefix.length, prefix.length + value.length)))
                    }
                    after == null && before != null -> {
                        val value = listOf(before.time, before.raw).filter(String::isNotBlank).joinToString(" · ")
                        val prefix = "Убрано из расписания: "
                        ChangeRecord(prefix + value, listOf(ChangedRange(prefix.length, prefix.length + value.length)))
                    }
                    before != null && after != null -> {
                        val o = SiteParser.details(before,date,termStart)
                        val n = SiteParser.details(after,date,termStart)
                        val parts = mutableListOf<ChangeRecord>()
                        fun add(name: String, from: String, to: String) {
                            if (normalized(from) != normalized(to)) parts += replacement(name, from, to)
                        }
                        add("Время", before.time, after.time)
                        add("Дата", before.date?.toString() ?: "не указана", after.date?.toString() ?: "не указана")
                        if (before.day != after.day && before.date == null && after.date == null) {
                            val days = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
                            add("День", days.getOrNull(before.day) ?: "не указан", days.getOrNull(after.day) ?: "не указан")
                        }
                        fun column(value: Int) = when (value) { 1 -> "числитель"; 2 -> "знаменатель"; else -> "обе недели" }
                        add("Чередование", column(before.parity), column(after.parity))
                        add("Ауд.", o.room, n.room)
                        add("Преподаватель", o.teacher, n.teacher)
                        add("Тип", o.kind, n.kind)
                        add("Недели", LessonWeeks.label(before, termStart), LessonWeeks.label(after, termStart))
                        add("Сдача", if (SiteParser.isRetake(before.raw)) "пересдача" else "обычная",
                            if (SiteParser.isRetake(after.raw)) "пересдача" else "обычная")
                        // Preserve annotation edits even when a room or time changes simultaneously.
                        fun annotation(raw: String, info: SiteParser.LessonInfo) = raw
                            .replace(info.teacher, "").replace(info.room.takeIf { it != "—" } ?: "\u0000", "")
                            .replace(Regex("""(?iu)\([^)]*нед\.[^)]*\)|\((?:л\.|п\.з\.|л\.з\.|зач\.|экз\.|к\.р\.)\)|пересдач[а-яё]*"""), "")
                        if (normalized(before.raw) != normalized(after.raw) && (parts.isEmpty() ||
                                normalized(annotation(before.raw, o)) != normalized(annotation(after.raw, n)))) {
                            parts += replacement("Изменение на сайте:", before.raw, after.raw)
                        }
                        if (parts.isEmpty()) null else joined(parts, "; ").prefixed("$label: ")
                    }
                    else -> null
                }
                if (record != null) changes += ScheduleChange(record.message, "$key:$i", after ?: before, record.highlights)
            }
        }
        return changes.distinctBy { it.message }.take(40)
    }
}
