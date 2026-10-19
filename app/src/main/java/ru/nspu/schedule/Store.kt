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

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("schedule", Context.MODE_PRIVATE)
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
    fun availableSubgroups(c: Context): List<String> {
        cached(c) // Populates both the parsed lessons and derived subgroup list.
        return synchronized(cacheLock) { languageSnapshot }
    }
    fun group(c: Context): String {
        val available = availableSubgroups(c)
        val saved = prefs(c).getString("subgroup", "А7") ?: "А7"
        return if (saved in available) saved else "Все"
    }
    fun setGroup(c: Context, value: String) { prefs(c).edit().putString("subgroup", value).apply() }
    fun scheduleGroupId(c: Context) = prefs(c).getInt("group_id", 3328)
    fun scheduleGroupName(c: Context) = prefs(c).getString("group_name", "3.008.2.26 ИСиТвО") ?: "3.008.2.26"
    fun scheduleGroupCode(c: Context) = scheduleGroupName(c).substringBefore(" ").trim()
    fun source(c: Context) = "https://schedule.nspu.ru/group_shedule.php?id=" + scheduleGroupId(c)

    /** Cache and change-baseline belong to one selected group only. */
    fun selectScheduleGroup(c: Context, id: Int, name: String) {
        if (id == scheduleGroupId(c)) return
        prefs(c).edit().putInt("group_id", id).putString("group_name", name)
            .putString("subgroup", "Все").putInt("offset", 0).remove("selected_date")
            .remove("lessons").remove("notices").remove("known_notice_ids")
            .remove("term_start").remove("change_history").remove("dismissed_notices")
            .remove("updated").remove("error").remove("schedule_links")
            .commit()
    }
    fun focus(c: Context, now: java.time.LocalDateTime = java.time.LocalDateTime.now(ZONE)): FocusedLesson? =
        LessonFocus.choose(now) { day -> forDate(c, day) }

    /** Null = automatic next-lesson day; non-null = user's explicit calendar day.
     * Import older offset prefs only if nonzero; 0 used to mean automatic selection.
     */
    private fun chosenDate(c: Context, today: LocalDate): LocalDate? {
        val p = prefs(c)
        p.getString("selected_date", null)?.let { raw ->
            return runCatching { LocalDate.parse(raw) }.getOrNull()
        }
        val legacyOffset = p.getInt("offset", 0)
        return if (legacyOffset != 0) today.plusDays(legacyOffset.toLong()) else null
    }

    fun date(c: Context): LocalDate {
        val today = LocalDate.now(ZONE)
        val selected = chosenDate(c, today)
        if (selected != null) return selected // No expensive focus scan on manual navigation.
        return DayNavigation.visible(today, null, focus(c)?.date)
    }

    fun selectDate(c: Context, value: LocalDate) {
        prefs(c).edit().putString("selected_date", value.toString()).remove("offset").apply()
    }

    fun jump(c: Context, daysFromToday: Int) {
        selectDate(c, LocalDate.now(ZONE).plusDays(daysFromToday.toLong()))
    }

    /** "Today" explicitly selects today, without snapping to tomorrow after last class. */
    fun resetDate(c: Context) = selectDate(c, LocalDate.now(ZONE))

    /** Optional return to the automatic live view on a future navigation action. */
    fun useAutomaticDay(c: Context) {
        prefs(c).edit().remove("selected_date").remove("offset").apply()
    }

    fun offset(c: Context, delta: Int) {
        val today = LocalDate.now(ZONE)
        val selected = chosenDate(c, today)
        val shown = if (selected != null) selected else
            DayNavigation.visible(today, null, focus(c)?.date)
        selectDate(c, shown.plusDays(delta.toLong()))
    }

    fun termStart(c: Context): LocalDate = runCatching {
        LocalDate.parse(prefs(c).getString("term_start", "2026-09-01"))
    }.getOrDefault(LocalDate.of(2026, 9, 1))
    fun week(date: LocalDate): Int = SiteParser.academicWeek(date)
    fun week(c: Context, date: LocalDate): Int = SiteParser.academicWeek(date, termStart(c))

    fun changeHistory(c: Context): List<String> {
        val raw = prefs(c).getString("change_history", "[]") ?: "[]"
        return synchronized(cacheLock) {
            if (raw != historyRaw) {
                val array = JSONArray(raw)
                historySnapshot = (0 until array.length()).map { array.getString(it) }
                historyRaw = raw
            }
            historySnapshot
        }
    }
    fun cached(c: Context): List<Lesson> {
        val raw = prefs(c).getString("lessons", "[]") ?: "[]"
        return synchronized(cacheLock) {
            if (raw != lessonRaw) {
                val a = JSONArray(raw)
                val parsed = (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Lesson(o.getInt("day"), o.getString("time"), o.getString("raw"), o.optBoolean("changed"))
                }
                lessonSnapshot = parsed
                languageSnapshot = SiteParser.languageOptions(parsed)
                lessonRaw = raw
            }
            lessonSnapshot
        }
    }
    fun notices(c: Context): List<Notice> {
        val raw = prefs(c).getString("notices", "[]") ?: "[]"
        return synchronized(cacheLock) {
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
    }
    /** Hiding affects only this phone's home-screen widget, never the official site or app. */
    fun widgetNotices(c: Context): List<Notice> {
        val hidden = prefs(c).getStringSet("dismissed_notices", emptySet()) ?: emptySet()
        return notices(c).filterNot { it.id in hidden }
    }
    fun dismissWidgetNotice(c: Context, noticeId: String) {
        if (noticeId.isBlank() || notices(c).none { it.id == noticeId }) return
        val hidden = (prefs(c).getStringSet("dismissed_notices", emptySet()) ?: emptySet()).toMutableSet()
        hidden += noticeId
        prefs(c).edit().putStringSet("dismissed_notices", hidden).apply()
        val saved = notices(c).map { if (it.id == noticeId) it.copy(isNew = false) else it }
        prefs(c).edit().putString("notices", noticesJson(saved).toString()).apply()
    }

    private fun currentLinks(c: Context): List<ScheduleLink> {
        val raw = prefs(c).getString("schedule_links", "[]") ?: "[]"
        return synchronized(cacheLock) {
            if (raw != linksRaw) {
                linksSnapshot = ScheduleLinks.decode(raw)
                linksRaw = raw
            }
            linksSnapshot
        }
    }

    fun missingScheduleLinks(c: Context) = !prefs(c).contains("schedule_links")

    fun teacherScheduleUrl(c: Context, teacher: String): String? =
        ScheduleLinks.teacherUrl(currentLinks(c), teacher)

    fun roomScheduleUrl(c: Context, room: String): String? =
        ScheduleLinks.roomUrl(currentLinks(c), room)

    fun status(c: Context) = prefs(c).getString("updated", "Ещё не загружено") ?: ""
    fun error(c: Context) = prefs(c).getString("error", "") ?: ""
    fun forDate(c: Context, date: LocalDate): List<Lesson> {
        val day = date.dayOfWeek.value - 1
        if (day !in 0..5) return emptyList()
        val start = termStart(c)
        val languageGroup = group(c)
        return cached(c).filter { it.day == day && SiteParser.applicable(it, date, start) && SiteParser.subgroupFits(it.raw, languageGroup) }
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
        val pageLinks: List<ScheduleLink>
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            val doc = Jsoup.parse(connection.inputStream, null, url)
            pageLinks = ScheduleLinks.fromPage(doc)
            doc.select("script,style,nav,footer").remove()
            snapshot = SiteParser.parse(doc.body().text(), scheduleGroupCode(c))
        } finally { connection.disconnect() }

        if (groupId != scheduleGroupId(c)) error("Группа поменялась во время загрузки")
        val start = snapshot.termStart ?: termStart(c)
        val changedTerm = snapshot.termStart != null && snapshot.termStart != termStart(c)
        val oldLessons = if (changedTerm) emptyList() else cached(c)
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
        val previousNotices = notices(c).associateBy { it.id }
        val previousNoticeIds = prefs(c).getString("known_notice_ids", "")!!.split('|').filter { it.isNotBlank() }.toSet()
        val hasNoticeBaseline = prefs(c).contains("notices")
        val currentNotices = snapshot.notices.map { notice ->
            notice.copy(isNew = (previousNotices[notice.id]?.isNew == true) ||
                (hasNoticeBaseline && notice.id !in previousNoticeIds))
        }
        val notifications = currentNotices.filter { it.id !in previousNoticeIds && it.isNew }
        val combinedKnown = (previousNoticeIds + currentNotices.map { it.id }).toList().takeLast(150)
        // Keep only ids still present: if university edits a notice, its new id appears again.
        val activeIds = currentNotices.map { it.id }.toSet()
        val hiddenNow = (prefs(c).getStringSet("dismissed_notices", emptySet()) ?: emptySet())
            .filterTo(mutableSetOf()) { it in activeIds }
        val timestamp = java.time.LocalDateTime.now(ZONE).toString().replace('T', ' ').take(16)
        val log = (semanticChanges.map { "$timestamp · " + it.message } + changeHistory(c))
            .distinct().take(100)
        val logJson = JSONArray().also { a -> log.forEach { a.put(it) } }

        // One atomic SharedPreferences update: lessons and announcements always match one successful fetch.
        val ok = prefs(c).edit()
            .putString("lessons", lessonsJson(changes).toString())
            .putString("notices", noticesJson(currentNotices).toString())
            .putStringSet("dismissed_notices", hiddenNow)
            .putString("known_notice_ids", combinedKnown.joinToString("|"))
            .putString("term_start", start.toString())
            .putString("change_history", logJson.toString())
            .putString("schedule_links", ScheduleLinks.encode(pageLinks))
            .putString("updated", timestamp)
            .putString("error", "")
            .commit()
        if (!ok) error("Не удалось сохранить расписание")
        return SyncOutcome(notifications, newLessonChanges, semanticChanges)
    }
    fun saveError(c: Context, e: Throwable) { prefs(c).edit().putString("error", e.message ?: "Ошибка сети").apply() }
}
