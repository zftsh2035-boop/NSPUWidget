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
    private var historySnapshot: List<ChangeRecord> = emptyList()
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
    fun scheduleGroupCode() = scheduleGroupName().trim()
    private fun extramural() = prefs().getBoolean("extramural", GroupIdentity.extramural(id))
    fun source() = "https://schedule.nspu.ru/" +
        (if (extramural()) "zgroup_shedule.php" else "group_shedule.php") + "?id=${GroupIdentity.siteId(id)}"
    fun focus(now: java.time.LocalDateTime = java.time.LocalDateTime.now(ZONE)): FocusedLesson? =
        LessonFocus.choose(now) { day -> forDate(day) }

    fun termStart(): LocalDate = runCatching {
        LocalDate.parse(prefs().getString("term_start", "2026-09-01"))
    }.getOrDefault(LocalDate.of(2026, 9, 1))
    fun week(date: LocalDate): Int = SiteParser.academicWeek(date, termStart())

    fun changeHistory(): List<ChangeRecord> {
        val raw = prefs().getString("change_history", "[]") ?: "[]"
        return synchronized(cacheLock) {
            if (raw != historyRaw) {
                val array = JSONArray(raw)
                historySnapshot = (0 until array.length()).map { index ->
                    val entry = array.optJSONObject(index)
                    if (entry == null) ChangeRecord(array.getString(index)) else {
                        val text = entry.getString("message")
                        val ranges = entry.optJSONArray("highlights") ?: JSONArray()
                        ChangeRecord(text, (0 until ranges.length()).mapNotNull { n ->
                            val range = ranges.optJSONObject(n) ?: return@mapNotNull null
                            val from = range.optInt("start", -1); val to = range.optInt("end", -1)
                            if (from >= 0 && to > from && to <= text.length) ChangedRange(from, to) else null
                        })
                    }
                }
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
                        o.optInt("parity"), runCatching { LocalDate.parse(o.optString("date")) }.getOrNull(),
                        o.optString("sourceNoticeId").ifBlank { null })
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
    private fun archivedAssessments(): List<Lesson> = runCatching {
        val a = JSONArray(prefs().getString("assessment_archive", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Lesson(o.getInt("day"), o.getString("time"), o.getString("raw"),
                parity = o.optInt("parity"), date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull(),
                sourceNoticeId = o.optString("sourceNoticeId").ifBlank { null })
        }
    }.getOrDefault(emptyList())
    fun assessments(now: java.time.LocalDateTime = java.time.LocalDateTime.now(ZONE)): List<AssessmentEntry> {
        val current = notices()
        val lessons = AssessmentList.merge(archivedAssessments(), cached() + AssessmentList.fromNotices(current), now)
        return AssessmentList.entries(lessons, now, termStart(), group(), current.associateBy { it.id })
    }
    fun nextAssessmentBoundary(now: java.time.LocalDateTime) =
        AssessmentList.nextBoundary(AssessmentList.merge(archivedAssessments(), cached() + AssessmentList.fromNotices(notices()), now), now)

    fun subjects(date: LocalDate) = SubjectSchedule.entries(cached(), date, termStart(), group(),
        runCatching { LocalDate.parse(prefs().getString("teaching_start", "")) }.getOrNull())
    /** Hiding affects only this phone's home-screen widget, never the official site or app. */
    fun widgetNotices(): List<Notice> {
        val hidden = prefs().getStringSet("dismissed_notices", emptySet()) ?: emptySet()
        return notices().filterNot { it.id in hidden || ImportantInfo.isAssessment(it) }
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

    fun needsRefresh() = prefs().getInt("parser_version", 0) != 4 || missingScheduleLinks() ||
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
        val teachingStart = runCatching { LocalDate.parse(prefs().getString("teaching_start", "")) }.getOrNull()
        return cached().filter {
            val explicitDay = SiteParser.fixedDate(it)?.dayOfWeek?.value?.minus(1)
            // A legacy calendar cache without dates must never become weekly classes.
            (!extramural() || it.date != null || SiteParser.fixedDate(it) != null) &&
                (teachingStart == null || date >= teachingStart || SiteParser.fixedDate(it) != null) &&
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
    fun markCategoryRead(assessments: Boolean) {
        val current = notices()
        val read = current.filter { ImportantInfo.isAssessment(it) == assessments }
        val known = prefs().getString("known_notice_ids", "").orEmpty().split('|').filter(String::isNotBlank)
        val editor = prefs().edit().putString("notices", noticesJson(current.map {
            if (ImportantInfo.isAssessment(it) == assessments) it.copy(isNew = false) else it
        }).toString()).putString("known_notice_ids", (known + read.map { it.id }).distinct().takeLast(300).joinToString("|"))
        if (!assessments) editor.putString("lessons", lessonsJson(cached().map { it.copy(changed = false) }).toString())
        editor.apply()
        Store.changed(context, id)
    }
    private fun lessonsJson(items: List<Lesson>): JSONArray = JSONArray().also { a ->
        items.forEach { a.put(JSONObject().put("day", it.day).put("time", it.time).put("raw", it.raw)
            .put("changed", it.changed).put("parity", it.parity).put("date", it.date?.toString() ?: "")
            .put("sourceNoticeId", it.sourceNoticeId ?: "")) }
    }
    private fun noticesJson(items: List<Notice>): JSONArray = JSONArray().also { a ->
        items.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("published", it.published ?: "").put("isNew", it.isNew)) }
    }

    private fun download(extra: Boolean): Pair<PageSnapshot, List<ScheduleLink>> {
        val url = "https://schedule.nspu.ru/" + (if (extra) "zgroup_shedule.php" else "group_shedule.php") +
            "?id=${GroupIdentity.siteId(id)}&old=1"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 18000
            setRequestProperty("User-Agent", "Mozilla/5.0 NSPUWidget/1.2")
            setRequestProperty("Accept", "text/html")
        }
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            val doc = Jsoup.parse(connection.inputStream, null, url)
            val pageLinks = ScheduleLinks.fromPage(doc)
            doc.select("script,style,nav,footer").remove()
            return GroupTimetableParser.parse(doc, scheduleGroupCode(), extra) to pageLinks
        } finally { connection.disconnect() }
    }

    /** No changes are committed if download/parsing/validation fails. */
    @Synchronized
    fun fetchAndSave(requestedAt: Long = 0): SyncOutcome {
        if (requestedAt > 0 && prefs().getLong("fetched_at", 0) >= requestedAt)
            return SyncOutcome(emptyList(), 0)
        var resolvedExtra = extramural()
        val received = try { download(resolvedExtra) } catch (original: Exception) {
            if (original !is IllegalArgumentException && original !is ScheduleUnavailableException) throw original
            // Older favorites did not remember zgroup_shedule. Repair the source
            // only after the other official page validates against this group's code.
            val alternative = runCatching { download(!resolvedExtra) }
            if (alternative.isFailure) throw original
            resolvedExtra = !resolvedExtra
            alternative.getOrThrow()
        }
        val (snapshot, pageLinks) = received

        val start = snapshot.termStart ?: termStart()
        val changedTerm = snapshot.termStart != null && snapshot.termStart != termStart()
        val assessmentArchive = AssessmentList.merge(archivedAssessments() + cached(), snapshot.lessons + AssessmentList.fromNotices(snapshot.notices),
            java.time.LocalDateTime.now(ZONE), changedTerm)
        // Rebuild an older parser's baseline without reporting format repairs as university edits.
        val parserChanged = prefs().getInt("parser_version", 0) != 4
        val oldLessons = if (changedTerm || parserChanged) emptyList() else cached()
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
        val hasNoticeBaseline = prefs().contains("notices") && !parserChanged
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
        val log = (semanticChanges.map { it.record().prefixed("$timestamp · ") } + changeHistory())
            .distinctBy { it.message }.take(100)
        val logJson = JSONArray().also { a -> log.forEach { entry ->
            a.put(JSONObject().put("message", entry.message).put("highlights", JSONArray().also { spans ->
                entry.highlights.forEach { spans.put(JSONObject().put("start", it.start).put("end", it.end)) }
            }))
        } }

        // One atomic SharedPreferences update: lessons and announcements always match one successful fetch.
        val ok = prefs().edit()
            .putString("lessons", lessonsJson(changes).toString())
            .putString("assessment_archive", lessonsJson(assessmentArchive).toString())
            .putBoolean("extramural", resolvedExtra)
            .putString("notices", noticesJson(currentNotices).toString())
            .putStringSet("dismissed_notices", hiddenNow)
            .putString("known_notice_ids", combinedKnown.joinToString("|"))
            .putString("term_start", start.toString())
            .putString("teaching_start", snapshot.teachingStart?.toString() ?: "")
            .putString("change_history", logJson.toString())
            .putString("schedule_links", ScheduleLinks.encode(pageLinks))
            .putInt("parser_version", 4)
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
