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
data class SyncOutcome(val newNotices: List<Notice>, val newLessonChanges: Int,
    val changes: List<ScheduleChange> = emptyList())

/** One repository per university group; downloads never depend on the open screen. */
internal class GroupSchedule(private val context: Context, val id: Int) {
    private fun prefs() = context.getSharedPreferences("schedule-group-$id", Context.MODE_PRIVATE)
    // Cached snapshots prevent repeated JSON parsing when Compose or the widget queries data.
    // The raw JSON itself is the invalidation token; edits and group changes are automatic.
    private val cacheLock = Any()
    private var lessonRaw: String? = null
    private var lessonSnapshot: List<Lesson> = emptyList()
    private var languageSnapshot: List<String> = emptyList()
    private var noticeRaw: String? = null
    private var noticeSnapshot: List<Notice> = emptyList()
    private var linksRaw: String? = null
    private var linksSnapshot: List<ScheduleLink> = emptyList()
    private var historyRaw: String? = null
    private var historySnapshot: List<String> = emptyList()
    fun availableSubgroups(): List<String> {
        cached() // Populates both the parsed lessons and derived subgroup list.
        return synchronized(cacheLock) { languageSnapshot }
    }
    fun group(): String {
        val available = availableSubgroups()
        val saved = prefs().getString("subgroup", "Все") ?: "Все"
        return if (saved in available) saved else "Все"
    }
    fun setGroup(value: String) {
        prefs().edit().putString("subgroup", value).apply()
        Store.changed(context, id)
    }
    fun scheduleGroupName() = Favorites.find(context, id)?.title
        ?: prefs().getString("group_name", "Группа $id")!!
    fun scheduleGroupCode() = scheduleGroupName().substringBefore(" ").trim()
    private fun extramural() = prefs().getBoolean("extramural", false)
    fun source() = "https://schedule.nspu.ru/" +
        (if (extramural()) "zgroup_shedule.php" else "group_shedule.php") + "?id=$id"
    fun focus(now: java.time.LocalDateTime = java.time.LocalDateTime.now(ZONE)): FocusedLesson? =
        LessonFocus.choose(now) { day -> forDate(day) }

    fun termStart(): LocalDate = runCatching {
        LocalDate.parse(prefs().getString("term_start", "2026-09-01"))
    }.getOrDefault(LocalDate.of(2026, 9, 1))
    fun week(date: LocalDate): Int = SiteParser.academicWeek(date, termStart())

    fun changeHistory(): List<String> {
        val raw = prefs().getString("change_history", "[]") ?: "[]"
        return synchronized(cacheLock) {
            if (raw != historyRaw) {
                val array = JSONArray(raw)
                historySnapshot = (0 until array.length()).map { array.getString(it) }
                historyRaw = raw
            }
            historySnapshot
        }
    }
    fun cached(): List<Lesson> {
        val raw = prefs().getString("lessons", "[]") ?: "[]"
        return synchronized(cacheLock) {
            if (raw != lessonRaw) {
                val a = JSONArray(raw)
                val parsed = (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Lesson(o.getInt("day"), o.getString("time"), o.getString("raw"), o.optBoolean("changed"),
                        o.optInt("parity"), runCatching { LocalDate.parse(o.optString("date")) }.getOrNull())
                }
                lessonSnapshot = parsed
                languageSnapshot = SiteParser.languageOptions(parsed)
                lessonRaw = raw
            }
            lessonSnapshot
        }
    }
    fun notices(): List<Notice> {
        val raw = prefs().getString("notices", "[]") ?: "[]"
        val saved = synchronized(cacheLock) {
            if (raw != noticeRaw) {
                val a = JSONArray(raw)
                noticeSnapshot = (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Notice(o.getString("id"), o.getString("text"), o.optString("published").ifBlank { null }, o.optBoolean("isNew"))
                }
                noticeRaw = raw
            }
            noticeSnapshot
        }
        return (saved + ImportantInfo.notices(cached(), LocalDate.now(ZONE), termStart()))
            .distinctBy { it.id }.filterNot { SiteParser.noticeIsOutdated(it, LocalDate.now(ZONE)) }
    }
    /** Hiding affects only this phone's home-screen widget, never the official site or app. */
    fun widgetNotices(): List<Notice> {
        val hidden = prefs().getStringSet("dismissed_notices", emptySet()) ?: emptySet()
        return notices().filterNot { it.id in hidden }
    }
    fun dismissWidgetNotice(noticeId: String) {
        if (noticeId.isBlank() || notices().none { it.id == noticeId }) return
        val hidden = (prefs().getStringSet("dismissed_notices", emptySet()) ?: emptySet()).toMutableSet()
        hidden += noticeId
        prefs().edit().putStringSet("dismissed_notices", hidden).apply()
        val saved = notices().map { if (it.id == noticeId) it.copy(isNew = false) else it }
        prefs().edit().putString("notices", noticesJson(saved).toString()).apply()
        Store.changed(context, id)
    }

    fun currentLinks(): List<ScheduleLink> {
        val raw = prefs().getString("schedule_links", "[]") ?: "[]"
        return synchronized(cacheLock) {
            if (raw != linksRaw) {
                linksSnapshot = ScheduleLinks.decode(raw)
                linksRaw = raw
            }
            linksSnapshot
        }
    }

    fun needsRefresh() = prefs().getInt("parser_version", 0) != 1 || missingScheduleLinks() ||
        System.currentTimeMillis() - prefs().getLong("fetched_at", 0) >= 3L * 60 * 60 * 1000

    fun missingScheduleLinks() = !prefs().contains("schedule_links")

    fun teacherScheduleUrl(teacher: String): String? =
        ScheduleLinks.teacherUrl(currentLinks(), teacher)

    fun teacherNameForLesson(raw: String): String? =
        ScheduleLinks.teacherLink(currentLinks(), raw)?.label

    fun roomScheduleUrl(room: String): String? =
        ScheduleLinks.roomUrl(currentLinks(), room)

    fun status() = prefs().getString("updated", "Ещё не загружено") ?: ""
    fun error() = prefs().getString("error", "") ?: ""
    fun forDate(date: LocalDate): List<Lesson> {
        val day = date.dayOfWeek.value - 1
        val start = termStart()
        val languageGroup = group()
        return cached().filter {
            val explicitDay = SiteParser.fixedDate(it)?.dayOfWeek?.value?.minus(1)
            (explicitDay ?: it.day) == day && SiteParser.applicable(it, date, start) && SiteParser.subgroupFits(it.raw, languageGroup)
        }
            .sortedWith(compareBy<Lesson>({ it.time.substringBefore(':').toIntOrNull() ?: 99 }, { it.time.substringAfter(':').take(2).toIntOrNull() ?: 0 }))
    }
    fun markAllRead() {
        val lessonArray = lessonsJson(cached().map { it.copy(changed = false) })
        val current = notices()
        val noticeArray = noticesJson(current.map { it.copy(isNew = false) })
        val known = prefs().getString("known_notice_ids", "").orEmpty().split('|').filter(String::isNotBlank)
        prefs().edit().putString("lessons", lessonArray.toString()).putString("notices", noticeArray.toString())
            .putString("known_notice_ids", (known + current.map { it.id }).distinct().takeLast(150).joinToString("|")).apply()
        Store.changed(context, id)
    }
    private fun lessonsJson(items: List<Lesson>): JSONArray = JSONArray().also { a ->
        items.forEach { a.put(JSONObject().put("day", it.day).put("time", it.time).put("raw", it.raw)
            .put("changed", it.changed).put("parity", it.parity).put("date", it.date?.toString() ?: "")) }
    }
    private fun noticesJson(items: List<Notice>): JSONArray = JSONArray().also { a ->
        items.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("published", it.published ?: "").put("isNew", it.isNew)) }
    }

    /** No changes are committed if download/parsing/validation fails. */
    @Synchronized
    fun fetchAndSave(requestedAt: Long = 0): SyncOutcome {
        if (requestedAt > 0 && prefs().getLong("fetched_at", 0) >= requestedAt)
            return SyncOutcome(emptyList(), 0)
        val url = source()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 18000
            setRequestProperty("User-Agent", "Mozilla/5.0 NSPUWidget/1.2")
            setRequestProperty("Accept", "text/html")
        }
        val snapshot: PageSnapshot
        val pageLinks: List<ScheduleLink>
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            val doc = Jsoup.parse(connection.inputStream, null, url)
            pageLinks = ScheduleLinks.fromPage(doc)
            doc.select("script,style,nav,footer").remove()
            snapshot = GroupTimetableParser.parse(doc, scheduleGroupCode(), extramural())
        } finally { connection.disconnect() }

        val start = snapshot.termStart ?: termStart()
        val changedTerm = snapshot.termStart != null && snapshot.termStart != termStart()
        // Rebuild an older parser's baseline without reporting format repairs as university edits.
        val oldLessons = if (changedTerm || prefs().getInt("parser_version", 0) != 1) emptyList() else cached()
        val semanticChanges = ChangeLog.diff(oldLessons, snapshot.lessons, LocalDate.now(ZONE), start)
        val previous = oldLessons.associateBy { SiteParser.key(it) }
        val changes = snapshot.lessons.map { lesson ->
            val before = previous[SiteParser.key(lesson)]
            lesson.copy(changed = if (oldLessons.isEmpty()) false else before == null || before.changed || before.raw != lesson.raw)
        }
        val newLessonChanges = changes.count { current ->
            val before = previous[SiteParser.key(current)]
            oldLessons.isNotEmpty() && (before == null || before.raw != current.raw)
        }
        val previousNotices = notices().associateBy { it.id }
        val previousNoticeIds = prefs().getString("known_notice_ids", "")!!.split('|').filter { it.isNotBlank() }.toSet()
        val hasNoticeBaseline = prefs().contains("notices")
        val incomingNotices = (snapshot.notices + ImportantInfo.notices(snapshot.lessons, LocalDate.now(ZONE), start))
            .distinctBy { it.id }
        val currentNotices = incomingNotices.map { notice ->
            notice.copy(isNew = (previousNotices[notice.id]?.isNew == true) ||
                (hasNoticeBaseline && notice.id !in previousNoticeIds))
        }
        val notifications = currentNotices.filter { it.id !in previousNoticeIds && it.isNew }
        val combinedKnown = (previousNoticeIds + currentNotices.map { it.id }).toList().takeLast(150)
        // Keep only ids still present: if university edits a notice, its new id appears again.
        val activeIds = currentNotices.map { it.id }.toSet()
        val hiddenNow = (prefs().getStringSet("dismissed_notices", emptySet()) ?: emptySet())
            .filterTo(mutableSetOf()) { it in activeIds }
        val timestamp = java.time.LocalDateTime.now(ZONE).toString().replace('T', ' ').take(16)
        val log = (semanticChanges.map { "$timestamp · " + it.message } + changeHistory())
            .distinct().take(100)
        val logJson = JSONArray().also { a -> log.forEach { a.put(it) } }

        // One atomic SharedPreferences update: lessons and announcements always match one successful fetch.
        val ok = prefs().edit()
            .putString("lessons", lessonsJson(changes).toString())
            .putString("notices", noticesJson(currentNotices).toString())
            .putStringSet("dismissed_notices", hiddenNow)
            .putString("known_notice_ids", combinedKnown.joinToString("|"))
            .putString("term_start", start.toString())
            .putString("change_history", logJson.toString())
            .putString("schedule_links", ScheduleLinks.encode(pageLinks))
            .putInt("parser_version", 1)
            .putLong("fetched_at", System.currentTimeMillis())
            .putString("updated", timestamp)
            .putString("error", "")
            .commit()
        if (!ok) error("Не удалось сохранить расписание")
        Store.changed(context, id)
        return SyncOutcome(notifications, newLessonChanges, semanticChanges)
    }
    fun saveError(e: Throwable) {
        prefs().edit().putString("error", e.message ?: "Ошибка сети").apply()
        Store.changed(context, id)
    }
}
