package ru.nspu.schedule

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.os.Bundle
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.util.SizeF
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScheduleWidget : AppWidgetProvider() {
    private fun background(context: Context, recover: Boolean = true, block: () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try { block() }
            catch (e: Exception) {
                android.util.Log.e("NSPUWidget", "Widget action failed", e)
                WidgetDiagnostics.record(context, "callback_failed", error = e)
                if (recover) runCatching {
                    val manager = AppWidgetManager.getInstance(context)
                    manager.getAppWidgetIds(ComponentName(context, ScheduleWidget::class.java))
                        .forEach { recovery(context, manager, it) }
                }
            }
            finally { pending.finish() }
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = background(context) {
        ids.forEach { WidgetDiagnostics.record(context, "on_update", it) }
        Store.initialize(context)
        // Display the cache before scheduling background work. A scheduler failure
        // must not strand every widget at its initial XML layout.
        ids.forEach { render(context, manager, it) }
        Sync.schedule(context)
        ids.forEach { id ->
            val data = Store.data(context, WidgetSettings.groupId(context, id))
            if (data.cached().isEmpty()) Sync.now(context, data.id)
        }
        FocusClock.schedule(context)
    }
    override fun onDeleted(context: Context, ids: IntArray) = background(context, recover = false) {
        ids.forEach { WidgetSettings.delete(context, it) }
        FocusClock.schedule(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle) = background(context) {
            WidgetDiagnostics.record(context, "options_changed", appWidgetId)
            render(context, manager, appWidgetId)
        }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            background(context) { renderAll(context) }
            return
        }
        if (intent.action == "ru.nspu.schedule.NAV") {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (id < 0) return
            background(context) {
                val command = intent.getStringExtra("cmd")?.takeIf {
                    it in setOf("prev", "next", "today", "refresh", "dismiss", "groups", "close", "select")
                } ?: "unknown"
                WidgetDiagnostics.record(context, "action_$command", id)
                val manager = AppWidgetManager.getInstance(context)
                if (id !in manager.getAppWidgetIds(ComponentName(context, ScheduleWidget::class.java)))
                    return@background
                val view = WidgetSettings.view(context, id)
                when (intent.getStringExtra("cmd")) {
                    "prev" -> view.offset(-1)
                    "next" -> view.offset(1)
                    "today" -> view.selectDate(java.time.LocalDate.now(ZONE))
                    "refresh" -> Sync.now(context, view.data.id)
                    "dismiss" -> view.data.dismissWidgetNotice(intent.getStringExtra("notice_id") ?: "")
                    "groups" -> WidgetSettings.setPicker(context, id, true)
                    "close" -> WidgetSettings.setPicker(context, id, false)
                    "select" -> {
                        WidgetSettings.select(context, id, intent.getIntExtra("favorite_id", -1))
                        val selected = Store.data(context, WidgetSettings.groupId(context, id))
                        if (selected.needsRefresh()) Sync.now(context, selected.id)
                    }
                }
                render(context, manager, id)
                FocusClock.schedule(context)
            }
            return
        }
        super.onReceive(context, intent)
    }
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private fun recovery(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_compact_tiny).apply {
                setTextViewText(R.id.compactHeader, "Расписание НГПУ")
                setTextViewText(R.id.compactSubject, "Не удалось обновить виджет")
                setTextViewText(R.id.compactTime, "Нажми, чтобы открыть приложение")
                setTextViewText(R.id.compactTeacher, "Настройки → Диагностика виджетов")
                val intent = Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("nspuwidget://recovery/$id"))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                setOnClickPendingIntent(R.id.compactRoot, PendingIntent.getActivity(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                setViewVisibility(R.id.groups, View.GONE)
            }
            manager.updateAppWidget(id, views)
        }
        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidget::class.java))
            ids.forEach { render(context, manager, it) }
            if (ids.isNotEmpty()) FocusClock.schedule(context)
        }
        fun renderGroup(context: Context, groupId: Int) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, ScheduleWidget::class.java))
                .filter { WidgetSettings.groupId(context, it) == groupId }
                .forEach { render(context, manager, it) }
            FocusClock.schedule(context)
        }
        private fun action(context: Context, widgetId: Int, cmd: String,
            mutable: Boolean = false, extra: Intent.() -> Unit = {}): PendingIntent {
            val intent = Intent(context, ScheduleWidget::class.java)
                .setAction("ru.nspu.schedule.NAV")
                .setData(Uri.parse("nspuwidget://action/$widgetId/$cmd"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra("cmd", cmd).apply(extra)
            return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE))
        }
        private fun open(context: Context, widgetId: Int, groupId: Int,
            notices: Boolean = false, favorites: Boolean = false, mutable: Boolean = false,
            noteTarget: NoteTarget? = null, note: PersonalNote? = null): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setData(Uri.parse("nspuwidget://open/$widgetId/$groupId/$notices/$favorites").buildUpon()
                    .apply { if (noteTarget != null) appendQueryParameter("note", note?.id ?: noteTarget.slot.toString()) }
                    .build())
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_group_id", groupId).putExtra("show_notices", notices)
                .putExtra("show_favorites", favorites)
            if (noteTarget != null) NotesNavigation.fill(intent, noteTarget, note)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE))
        }
        private fun renderPicker(context: Context, widgetId: Int, height: Int): RemoteViews {
            val colors = WidgetTheme.palette(context)
            val v = RemoteViews(context.packageName, R.layout.widget_groups)
            v.setInt(R.id.widgetRoot, "setBackgroundResource", colors.panel)
            v.setTextColor(R.id.groupsTitle, colors.ink)
            v.setTextColor(R.id.groupsClose, colors.accent)
            v.setTextColor(R.id.manageFavorites, colors.accent)
            if (height < 200) v.setViewVisibility(R.id.manageFavorites, View.GONE)
            v.setOnClickPendingIntent(R.id.groupsClose, action(context, widgetId, "close"))
            v.setOnClickPendingIntent(R.id.manageFavorites,
                open(context, widgetId, WidgetSettings.groupId(context, widgetId), favorites = true))
            v.setRemoteAdapter(R.id.favoriteList, Intent(context, FavoriteListService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("nspuwidget://favorites/$widgetId")))
            v.setPendingIntentTemplate(R.id.favoriteList, action(context, widgetId, "select", mutable = true))
            return v
        }
        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            WidgetDiagnostics.record(context, "render_start", widgetId)
            try {
                renderContent(context, manager, widgetId)
                WidgetDiagnostics.record(context, "render_ok", widgetId)
            } catch (e: Exception) {
                WidgetDiagnostics.record(context, "render_failed", widgetId, e)
                android.util.Log.e("NSPUWidget", "Cannot render widget $widgetId", e)
                runCatching { recovery(context, manager, widgetId) }
            }
        }
        private fun renderContent(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val options = manager.getAppWidgetOptions(widgetId)
            val views = if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    .orEmpty().filter { it.width > 0 && it.height > 0 }.take(16)
                // If the host has not supplied sizes, let it choose a responsive layout itself.
                val supported = sizes.ifEmpty { listOf(SizeF(155f, 95f), SizeF(185f, 170f),
                    SizeF(185f, 210f), SizeF(185f, 345f)) }
                RemoteViews(supported.associateWith {
                    createViews(context, widgetId, it.width.toInt(), it.height.toInt())
                })
            } else {
                fun size(landscape: Boolean) = WidgetLayoutSize.fallback(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT), landscape)
                val portrait = size(false); val landscape = size(true)
                RemoteViews(createViews(context, widgetId, landscape.width, landscape.height),
                    createViews(context, widgetId, portrait.width, portrait.height))
            }
            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId,
                if (WidgetSettings.pickerOpen(context, widgetId)) R.id.favoriteList else R.id.lessonList)
        }
        private fun createViews(context: Context, widgetId: Int, widthDp: Int, heightDp: Int): RemoteViews {
            if (WidgetSettings.pickerOpen(context, widgetId)) return renderPicker(context, widgetId, heightDp)
            val view = WidgetSettings.view(context, widgetId)
            val data = view.data
            val favorite = Favorites.find(context, data.id)
            val compact = heightDp < 210
            val tiny = heightDp < 170 || widthDp < 185
            val medium = heightDp in 210..344
            val widgetPalette = WidgetTheme.palette(context)
            val widgetMode = WidgetTheme.mode(context)
            if (compact) {
                val layout = if (tiny) R.layout.widget_compact_tiny else R.layout.widget_compact
                val v = RemoteViews(context.packageName, layout)
                v.setInt(R.id.compactRoot, "setBackgroundResource", widgetPalette.panel)
                v.setInt(R.id.compactKind, "setBackgroundResource", widgetPalette.badge)
                v.setTextColor(R.id.compactHeader, widgetPalette.muted)
                v.setTextColor(R.id.compactSubject, widgetPalette.ink)
                v.setTextColor(R.id.compactTeacher, widgetPalette.body)
                v.setTextColor(R.id.compactTime, widgetPalette.accent)
                v.setTextColor(R.id.compactRoom, widgetPalette.room)
                val focus = data.focus()
                v.setTextViewText(R.id.compactHeader,
                    favorite?.displayName ?: data.scheduleGroupCode())
                if (focus != null) {
                    val d = SiteParser.details(focus.lesson, focus.date, data.termStart())
                    v.setTextViewText(R.id.compactSubject, d.subject)
                    v.setTextViewText(R.id.compactTeacher, d.teacher)
                    v.setTextViewText(R.id.compactWeeks, LessonWeeks.label(focus.lesson, data.termStart()))
                    v.setTextColor(R.id.compactWeeks, widgetPalette.muted)
                    val time = if (focus.ongoing) "До " + focus.lesson.time.substringAfter('-')
                        else if (tiny && focus.date == java.time.LocalDate.now(ZONE))
                            "В " + focus.lesson.time.substringBefore('-')
                        else focus.date.format(DateTimeFormatter.ofPattern(
                            if (tiny) "dd.MM" else "EE dd.MM", Locale.forLanguageTag("ru-RU"))) +
                            " · " + focus.lesson.time.substringBefore('-')
                    v.setTextViewText(R.id.compactTime,
                        time + if (tiny && d.room != "—") " · ауд. " + d.room else "")
                    v.setTextViewText(R.id.compactKind, SiteParser.eventLabel(d.kind, focus.lesson.raw))
                    v.setTextViewText(R.id.compactRoom,
                        if (d.room == "—") "Аудитория не указана" else "ауд. " + d.room)
                    v.setTextColor(R.id.compactKind, WidgetTheme.lessonAccent(d.kind, widgetMode))
                    val notes = runCatching { Notes.forDay(context, data.id, focus.date) }
                        .onFailure { android.util.Log.e("NSPUWidget", "Cannot read compact notes", it) }
                        .getOrDefault(emptyList())
                    val cards = prepareLessonCards(data.id, focus.date, data.forDate(focus.date),
                        data.termStart(), ScheduleLinks.Index(data.currentLinks()), notes)
                    val card = cards[focus.lesson]
                    val today = java.time.LocalDate.now(ZONE)
                    val todayNotes = if (focus.date == today) notes else runCatching {
                        Notes.forDay(context, data.id, today)
                    }.getOrDefault(emptyList())
                    val todayEvent = todayNotes.firstOrNull { it.target.isDay }
                    val lessonNote = card?.note?.takeIf { it.options.visibleOn(focus.date) }
                    val note = if (focus.date == today) lessonNote ?: todayEvent else todayEvent ?: lessonNote
                    if (note != null) {
                        v.setTextViewText(R.id.compactTeacher, "Заметка: " + note.preview)
                        v.setTextColor(R.id.compactTeacher, widgetPalette.accent)
                        v.setInt(R.id.compactTeacher, "setMaxLines", if (tiny) 1 else 2)
                    }
                    v.setOnClickPendingIntent(R.id.compactTeacher,
                        if (note != null) open(context, widgetId, data.id, noteTarget = note.target, note = note)
                        else open(context, widgetId, data.id))
                } else {
                    val dayNote = runCatching { Notes.forDay(context, data.id, java.time.LocalDate.now(ZONE)) }
                        .getOrDefault(emptyList()).firstOrNull { it.target.isDay }
                    v.setTextViewText(R.id.compactSubject, dayNote?.target?.subject ?: "Расписание пока не загружено")
                    v.setTextViewText(R.id.compactTeacher, dayNote?.preview ?: "Нажми, чтобы открыть приложение")
                    v.setTextViewText(R.id.compactTime, if (dayNote != null) "Заметка на день" else "")
                    if (dayNote != null) v.setOnClickPendingIntent(R.id.compactTeacher,
                        open(context, widgetId, data.id, noteTarget = dayNote.target, note = dayNote))
                    v.setTextViewText(R.id.compactKind, "")
                    v.setTextViewText(R.id.compactRoom, "")
                }
                v.setOnClickPendingIntent(R.id.compactRoot, open(context, widgetId, data.id))
                v.setTextColor(R.id.groups, widgetPalette.accent)
                v.setOnClickPendingIntent(R.id.groups, action(context, widgetId, "groups"))
                return v
            }
            val v = RemoteViews(context.packageName, R.layout.widget)
            v.setInt(R.id.widgetRoot, "setBackgroundResource", widgetPalette.panel)
            v.setInt(R.id.noticeBox, "setBackgroundResource", widgetPalette.notice)
            v.setTextColor(R.id.header, widgetPalette.ink)
            v.setTextColor(R.id.prev, widgetPalette.accent)
            v.setTextColor(R.id.next, widgetPalette.accent)
            v.setTextColor(R.id.subtitle, widgetPalette.muted)
            v.setTextColor(R.id.noticeLabel, widgetPalette.noticeTitle)
            v.setTextColor(R.id.noticeText, widgetPalette.noticeText)
            v.setTextColor(R.id.noticeMeta, widgetPalette.muted)
            v.setTextColor(R.id.noticeDismiss, widgetPalette.accent)
            v.setTextColor(R.id.lessonCount, widgetPalette.body)
            v.setTextColor(R.id.emptyState, widgetPalette.muted)
            v.setTextColor(R.id.status, widgetPalette.muted)
            v.setTextColor(R.id.today, widgetPalette.accent)
            v.setTextColor(R.id.refresh, widgetPalette.accent)
            val date = view.date()
            val name = if (date.dayOfWeek.value in 1..6) DAYS[date.dayOfWeek.value - 1] else "Воскресенье"
            v.setTextViewText(R.id.header,
                "$name, " + date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru-RU"))))
            v.setTextViewText(R.id.subtitle,
                (favorite?.displayName ?: data.scheduleGroupCode()) + " · неделя " + data.week(date) + " · " + data.group())
            val notices = data.widgetNotices()
            val newest = notices.firstOrNull { it.isNew } ?: notices.firstOrNull { ImportantInfo.isEvent(it) }
                ?: notices.firstOrNull()
            if (newest != null && !medium) {
                v.setViewVisibility(R.id.noticeBox, View.VISIBLE)
                v.setTextViewText(R.id.noticeLabel, if (newest.isNew) "● НОВОЕ ОБЪЯВЛЕНИЕ" else "ОБЪЯВЛЕНИЕ НГПУ")
                v.setTextViewText(R.id.noticeText, newest.text)
                v.setOnClickPendingIntent(R.id.noticeDismiss,
                    action(context, widgetId, "dismiss") { putExtra("notice_id", newest.id) })
                v.setTextViewText(R.id.noticeMeta,
                    if (notices.size > 1) "Ещё " + (notices.size - 1) + " · открыть" else "Нажми, чтобы прочитать")
            } else v.setViewVisibility(R.id.noticeBox, View.GONE)

            val lessons = data.forDate(date)
            val n = lessons.size
            val pairWord = when {
                n % 100 in 11..14 -> "пар"
                n % 10 == 1 -> "пара"
                n % 10 in 2..4 -> "пары"
                else -> "пар"
            }
            v.setTextViewText(R.id.lessonCount, "$n $pairWord")
            val service = Intent(context, LessonListService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("nspuwidget://lessons/" + widgetId))
            v.setRemoteAdapter(R.id.lessonList, service)
            v.setEmptyView(R.id.lessonList, R.id.emptyState)
            v.setTextViewText(R.id.emptyState,
                if (data.cached().isEmpty()) {
                    if (data.error().isNotBlank()) "Не удалось загрузить расписание. Открой приложение"
                    else "Расписание загружается…"
                } else "Пар на этот день нет")
            val status = if (data.error().isNotBlank() && data.cached().isEmpty()) "Ошибка загрузки · нажми ↻"
                else if (data.error().isNotBlank()) "Офлайн · сохранённая версия"
                else "Обновлено " + data.status()
            v.setTextViewText(R.id.status, status)
            v.setOnClickPendingIntent(R.id.prev, action(context, widgetId, "prev"))
            v.setOnClickPendingIntent(R.id.next, action(context, widgetId, "next"))
            v.setOnClickPendingIntent(R.id.today, action(context, widgetId, "today"))
            v.setOnClickPendingIntent(R.id.refresh, action(context, widgetId, "refresh"))
            v.setTextColor(R.id.groups, widgetPalette.accent)
            v.setOnClickPendingIntent(R.id.groups, action(context, widgetId, "groups"))
            v.setOnClickPendingIntent(R.id.header, open(context, widgetId, data.id))
            v.setPendingIntentTemplate(R.id.lessonList, open(context, widgetId, data.id, mutable = true))
            v.setOnClickPendingIntent(R.id.noticeBox, open(context, widgetId, data.id, notices = true))
            return v
        }
    }
}
