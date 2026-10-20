package ru.nspu.schedule

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/** Compatibility facade for the app's selection. Widgets use their own ScheduleView. */
object Store {
    private val repositories = ConcurrentHashMap<Int, GroupSchedule>()
    private fun prefs(c: Context) = c.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    /** Copy v2.8 data before changing selection; preserve cache, notices and subgroup. */
    @Synchronized
    fun initialize(c: Context) {
        val legacy = prefs(c)
        if (legacy.getBoolean("groups_migrated", false)) return
        val id = legacy.getInt("group_id", 3328)
        val editor = c.getSharedPreferences("schedule-group-$id", Context.MODE_PRIVATE).edit()
        for ((key, value) in legacy.all) {
            if (key !in DATA_KEYS) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        // Preserve the old initial A7 preference only for the existing user's group.
        if (!legacy.contains("subgroup")) editor.putString("subgroup", "А7")
        check(editor.commit()) { "Не удалось перенести сохранённое расписание" }
        val items = Favorites.list(c)
        Favorites.add(c, id, items.firstOrNull { it.id == id }?.title
            ?: legacy.getString("group_name", "3.008.2.26 ИСиТвО")!!)
        check(legacy.edit().putBoolean("groups_migrated", true)
            .putInt("primary_group_id", legacy.getInt("primary_group_id", id)).commit())
    }
    private val DATA_KEYS = setOf("group_name", "subgroup", "lessons", "notices", "known_notice_ids",
        "term_start", "change_history", "dismissed_notices", "updated", "error", "schedule_links", "fetched_at")

    internal fun data(c: Context, id: Int = scheduleGroupId(c)): GroupSchedule {
        initialize(c)
        return repositories.getOrPut(id) { GroupSchedule(c.applicationContext, id) }
    }
    internal fun appView(c: Context) = ScheduleView(data(c), prefs(c))
    internal fun changed(c: Context, id: Int) {
        prefs(c).edit().putLong("data_revision_$id", System.nanoTime()).apply()
    }
    fun scheduleGroupId(c: Context) = prefs(c).getInt("group_id", 3328)
    fun scheduleGroupName(c: Context) = Favorites.find(c, scheduleGroupId(c))?.title
        ?: prefs(c).getString("group_name", "3.008.2.26 ИСиТвО")!!
    fun selectScheduleGroup(c: Context, id: Int, name: String) {
        initialize(c)
        Favorites.add(c, id, name)
        prefs(c).edit().putInt("group_id", id).putString("group_name", name)
            .remove("selected_date").remove("offset").apply()
    }
    internal fun homeSnapshot(c: Context, id: Int = scheduleGroupId(c)) =
        ScheduleView(data(c, id), prefs(c)).homeSnapshot(Favorites.list(c), Favorites.primaryId(c)) {
            date -> Notes.forDay(c, id, date)
        }.copy(nextNoteBoundary = runCatching { Notes.nextBoundary(c, id) }.getOrNull())
    fun cached(c: Context) = data(c).cached()
    fun missingScheduleLinks(c: Context) = data(c).missingScheduleLinks()
    fun source(c: Context) = data(c).source()
    fun offset(c: Context, delta: Int) = appView(c).offset(delta)
    fun jump(c: Context, days: Int) = appView(c).selectDate(LocalDate.now(ZONE).plusDays(days.toLong()))
    fun setGroup(c: Context, value: String) = data(c).setGroup(value)
    fun markAllRead(c: Context) = data(c).markAllRead()
}

/** Date navigation belongs to one view, while the repository is shared by group id. */
internal class ScheduleView(val data: GroupSchedule, private val settings: SharedPreferences) {
    private fun chosenDate(today: LocalDate): LocalDate? {
        settings.getString("selected_date", null)?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()?.let { return it }
        }
        val offset = settings.getInt("offset", 0)
        return if (offset != 0) today.plusDays(offset.toLong()) else null
    }
    fun date(now: LocalDateTime = LocalDateTime.now(ZONE)): LocalDate =
        chosenDate(now.toLocalDate()) ?: DayNavigation.visible(now.toLocalDate(), null, data.focus(now)?.date)
    fun selectDate(value: LocalDate) {
        settings.edit().putString("selected_date", value.toString()).remove("offset").apply()
    }
    fun useAutomaticDay() { settings.edit().remove("selected_date").remove("offset").apply() }
    fun offset(delta: Int) = selectDate(date().plusDays(delta.toLong()))
    fun homeSnapshot(favorites: List<FavoriteGroup>, primaryId: Int,
        loadNotes: (LocalDate) -> List<PersonalNote>): HomeScheduleSnapshot {
        val now = LocalDateTime.now(ZONE)
        val focus = data.focus(now)
        val date = chosenDate(now.toLocalDate()) ?: DayNavigation.visible(now.toLocalDate(), null, focus?.date)
        val lessons = data.forDate(date)
        val links = ScheduleLinks.Index(data.currentLinks())
        val start = data.termStart()
        val notes = runCatching { loadNotes(date) }
        notes.exceptionOrNull()?.let { android.util.Log.e("NSPUWidget", "Cannot read personal notes", it) }
        return HomeScheduleSnapshot(date, lessons,
            prepareLessonCards(data.id, date, lessons, start, links, notes.getOrDefault(emptyList())),
            DayTimeline.build(lessons), focus, data.notices(), data.scheduleGroupName(),
            data.group(), data.availableSubgroups(), data.changeHistory(), data.status(), data.error(),
            data.id, favorites, primaryId,
            if (notes.isFailure) "Не удалось прочитать заметки. Попробуй открыть их ещё раз." else "",
            dayNotes = notes.getOrDefault(emptyList()).filter { it.target.isDay })
    }
}
