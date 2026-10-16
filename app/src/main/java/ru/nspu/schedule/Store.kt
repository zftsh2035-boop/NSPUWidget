package ru.nspu.schedule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

const val SOURCE = "https://schedule.nspu.ru/group_shedule.php?id=3328"
val ZONE: ZoneId = ZoneId.of("Asia/Novosibirsk")
val DAYS = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота")
data class SyncOutcome(val newNotices: List<Notice>, val newLessonChanges: Int)

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("schedule", Context.MODE_PRIVATE)
    fun group(c: Context) = prefs(c).getString("subgroup", "А7") ?: "А7"
    fun setGroup(c: Context, value: String) { prefs(c).edit().putString("subgroup", value).apply() }
    fun scheduleGroupId(c: Context) = prefs(c).getInt("group_id", 3328)
    fun scheduleGroupName(c: Context) = prefs(c).getString("group_name", "3.008.2.26 ИСиТвО") ?: "3.008.2.26"
    fun scheduleGroupCode(c: Context) = scheduleGroupName(c).substringBefore(" ").trim()
    fun source(c: Context) = "https://schedule.nspu.ru/group_shedule.php?id=" + scheduleGroupId(c)

    /** Cache and change-baseline belong to one selected group only. */
    fun selectScheduleGroup(c: Context, id: Int, name: String) {
        if (id == scheduleGroupId(c)) return
        prefs(c).edit().putInt("group_id", id).putString("group_name", name)
            .putString("subgroup", "Все").putInt("offset", 0)
            .remove("lessons").remove("notices").remove("known_notice_ids")
            .remove("archived_notices").remove("updated").remove("error")
            .commit()
    }
    fun date(c: Context): LocalDate = LocalDate.now(ZONE).plusDays(prefs(c).getInt("offset", 0).toLong())
    fun resetDate(c: Context) { prefs(c).edit().putInt("offset", 0).apply() }
    fun offset(c: Context, delta: Int) {
        val p = prefs(c)
        p.edit().putInt("offset", (p.getInt("offset", 0) + delta).coerceIn(-7, 21)).apply()
    }
    fun week(date: LocalDate): Int = (ChronoUnit.DAYS.between(LocalDate.of(2026, 8, 31), date) / 7 + 1).toInt()
    fun cached(c: Context): List<Lesson> {
        val a = JSONArray(prefs(c).getString("lessons", "[]"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Lesson(o.getInt("day"), o.getString("time"), o.getString("raw"), o.optBoolean("changed"))
        }
    }
    fun notices(c: Context): List<Notice> {
        val a = JSONArray(prefs(c).getString("notices", "[]"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Notice(o.getString("id"), o.getString("text"), o.optString("published").ifBlank { null }, o.optBoolean("isNew"))
        }
    }
    fun archivedNotices(c: Context): List<Notice> {
        val a = JSONArray(prefs(c).getString("archived_notices", "[]"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Notice(o.getString("id"), o.getString("text"), o.optString("published").ifBlank { null }, false)
        }
    }
    fun status(c: Context) = prefs(c).getString("updated", "Ещё не загружено") ?: ""
    fun error(c: Context) = prefs(c).getString("error", "") ?: ""
    fun forDate(c: Context, date: LocalDate): List<Lesson> {
        val day = date.dayOfWeek.value - 1
        if (day !in 0..5) return emptyList()
        return cached(c).filter { it.day == day && SiteParser.applicable(it, date) && SiteParser.subgroupFits(it.raw, group(c)) }
            .sortedWith(compareBy<Lesson>({ it.time.substringBefore(':').toIntOrNull() ?: 99 }, { it.time.substringAfter(':').take(2).toIntOrNull() ?: 0 }))
    }
    fun markAllRead(c: Context) {
        val lessonArray = lessonsJson(cached(c).map { it.copy(changed = false) })
        val noticeArray = noticesJson(notices(c).map { it.copy(isNew = false) })
        prefs(c).edit().putString("lessons", lessonArray.toString()).putString("notices", noticeArray.toString()).apply()
    }
    private fun lessonsJson(items: List<Lesson>): JSONArray = JSONArray().also { a ->
        items.forEach { a.put(JSONObject().put("day", it.day).put("time", it.time).put("raw", it.raw).put("changed", it.changed)) }
    }
    private fun noticesJson(items: List<Notice>): JSONArray = JSONArray().also { a ->
        items.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("published", it.published ?: "").put("isNew", it.isNew)) }
    }

    /** No changes are committed if download/parsing/validation fails. */
    fun fetchAndSave(c: Context): SyncOutcome {
        val groupId = scheduleGroupId(c)
        val url = source(c)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 18000
            setRequestProperty("User-Agent", "Mozilla/5.0 NSPUWidget/1.2")
            setRequestProperty("Accept", "text/html")
        }
        val snapshot: PageSnapshot
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            val doc = Jsoup.parse(connection.inputStream, null, url)
            doc.select("script,style,nav,footer").remove()
            snapshot = SiteParser.parse(doc.body().text(), scheduleGroupCode(c))
        } finally { connection.disconnect() }

        if (groupId != scheduleGroupId(c)) error("Группа поменялась во время загрузки")
        val oldLessons = cached(c)
        val previous = oldLessons.associateBy { SiteParser.key(it) }
        val changes = snapshot.lessons.map { lesson ->
            val before = previous[SiteParser.key(lesson)]
            lesson.copy(changed = if (oldLessons.isEmpty()) false else before == null || before.changed || before.raw != lesson.raw)
        }
        val newLessonChanges = changes.count { current ->
            val before = previous[SiteParser.key(current)]
            oldLessons.isNotEmpty() && (before == null || before.raw != current.raw)
        }
        val previousNotices = notices(c).associateBy { it.id }
        val previousNoticeIds = prefs(c).getString("known_notice_ids", "")!!.split('|').filter { it.isNotBlank() }.toSet()
        val hasNoticeBaseline = prefs(c).contains("notices")
        val currentNotices = snapshot.notices.map { notice ->
            notice.copy(isNew = (previousNotices[notice.id]?.isNew == true) ||
                (hasNoticeBaseline && notice.id !in previousNoticeIds))
        }
        val notifications = currentNotices.filter { it.id !in previousNoticeIds && it.isNew }
        // A message leaves the current feed only if the official site removes it.
        val active = currentNotices.map { it.id }.toSet()
        val removed = (notices(c).filter { it.id !in active }.map { it.copy(isNew = false) } +
            archivedNotices(c)).distinctBy { it.id }.filter { it.id !in active }.takeLast(100)
        val combinedKnown = (previousNoticeIds + currentNotices.map { it.id }).toList().takeLast(150)
        val timestamp = java.time.LocalDateTime.now(ZONE).toString().replace('T', ' ').take(16)
        // One atomic SharedPreferences update: lessons and announcements always match one successful fetch.
        val ok = prefs(c).edit()
            .putString("lessons", lessonsJson(changes).toString())
            .putString("notices", noticesJson(currentNotices).toString())
            .putString("archived_notices", noticesJson(removed).toString())
            .putString("known_notice_ids", combinedKnown.joinToString("|"))
            .putString("updated", timestamp)
            .putString("error", "")
            .commit()
        if (!ok) error("Не удалось сохранить расписание")
        return SyncOutcome(notifications, newLessonChanges)
    }
    fun saveError(c: Context, e: Throwable) { prefs(c).edit().putString("error", e.message ?: "Ошибка сети").apply() }
}
