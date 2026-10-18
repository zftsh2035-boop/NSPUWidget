package ru.nspu.schedule

import java.time.LocalDate

data class ScheduleChange(val message: String, val lessonKey: String)

/** Semantic changes in lessons (not differences in HTML whitespace or annotation text). */
object ChangeLog {
    private fun normalized(value: String) = value.trim().replace(Regex("""\s+"""), " ").lowercase()
    private fun base(l: Lesson): String {
        val subgroup = Regex("""(?<![\p{L}\d])(?:А|A)\s*(10|[1-9])(?!\d)""")
            .find(l.raw)?.groupValues?.get(1) ?: "all"
        return l.day.toString() + "|" + normalized(l.raw.substringBefore(',')) + "|" + subgroup
    }
    fun diff(old: List<Lesson>, fresh: List<Lesson>, date: LocalDate, termStart: LocalDate): List<ScheduleChange> {
        if (old.isEmpty()) return emptyList()
        val oldGroups = old.groupBy(::base)
        val freshGroups = fresh.groupBy(::base)
        val changes = mutableListOf<ScheduleChange>()
        for (key in (oldGroups.keys + freshGroups.keys)) {
            val a = oldGroups[key].orEmpty().sortedBy { it.time }
            val b = freshGroups[key].orEmpty().sortedBy { it.time }
            val label = b.firstOrNull()?.raw?.substringBefore(',') ?: a.firstOrNull()?.raw?.substringBefore(',') ?: continue
            for (i in 0 until maxOf(a.size,b.size)) {
                val before = a.getOrNull(i)
                val after = b.getOrNull(i)
                val desc = when {
                    before == null && after != null -> "Добавлено: $label, ${after.time}"
                    after == null && before != null -> "Убрано из расписания: $label, ${before.time}"
                    before != null && after != null -> {
                        val o = SiteParser.details(before,date,termStart)
                        val n = SiteParser.details(after,date,termStart)
                        val parts = mutableListOf<String>()
                        if (before.time != after.time) parts += "Время ${before.time} → ${after.time}"
                        if (normalized(o.room) != normalized(n.room)) parts += "Ауд. ${o.room} → ${n.room}"
                        if (normalized(o.teacher) != normalized(n.teacher)) parts += "Преподаватель ${o.teacher} → ${n.teacher}"
                        if (o.kind != n.kind && o.kind != "ЗАНЯТИЕ" && n.kind != "ЗАНЯТИЕ") {
                            parts += "Тип ${SiteParser.shortKind(o.kind)} → ${SiteParser.shortKind(n.kind)}"
                        }
                        if (parts.isEmpty()) null else "$label: " + parts.joinToString("; ")
                    }
                    else -> null
                }
                if (desc != null) changes += ScheduleChange(desc.take(450), key + ":" + i)
            }
        }
        return changes.distinctBy { it.message }.take(40)
    }
}
