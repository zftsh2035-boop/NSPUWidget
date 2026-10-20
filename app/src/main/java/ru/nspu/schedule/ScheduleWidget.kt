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
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScheduleWidget : AppWidgetProvider() {
    private fun background(block: () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try { block() }
            catch (e: Exception) { android.util.Log.e("NSPUWidget", "Widget action failed", e) }
            finally { pending.finish() }
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = background {
        Store.initialize(context)
        Sync.schedule(context)
        ids.forEach { id ->
            val data = Store.data(context, WidgetSettings.groupId(context, id))
            if (data.cached().isEmpty()) Sync.now(context, data.id)
            render(context, manager, id)
        }
        FocusClock.schedule(context)
    }
    override fun onDeleted(context: Context, ids: IntArray) = background {
        ids.forEach { WidgetSettings.delete(context, it) }
        FocusClock.schedule(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle) = background { render(context, manager, appWidgetId) }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
            background { renderAll(context) }
            return
        }
        if (intent.action == "ru.nspu.schedule.NAV") {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (id < 0) return
            background {
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
                        if (selected.cached().isEmpty()) Sync.now(context, selected.id)
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
            notices: Boolean = false, favorites: Boolean = false, mutable: Boolean = false): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setData(Uri.parse("nspuwidget://open/$widgetId/$groupId/$notices/$favorites"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_group_id", groupId).putExtra("show_notices", notices)
                .putExtra("show_favorites", favorites)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE))
        }
        private fun renderPicker(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val colors = WidgetTheme.palette(context)
            val v = RemoteViews(context.packageName, R.layout.widget_groups)
            v.setInt(R.id.widgetRoot, "setBackgroundResource", colors.panel)
            v.setTextColor(R.id.groupsTitle, colors.ink)
            v.setTextColor(R.id.groupsClose, colors.accent)
            v.setTextColor(R.id.manageFavorites, colors.accent)
            val height = manager.getAppWidgetOptions(widgetId)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            if (height < 200) v.setViewVisibility(R.id.manageFavorites, View.GONE)
            v.setOnClickPendingIntent(R.id.groupsClose, action(context, widgetId, "close"))
            v.setOnClickPendingIntent(R.id.manageFavorites,
                open(context, widgetId, WidgetSettings.groupId(context, widgetId), favorites = true))
            v.setRemoteAdapter(R.id.favoriteList, Intent(context, FavoriteListService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("nspuwidget://favorites/$widgetId")))
            v.setPendingIntentTemplate(R.id.favoriteList, action(context, widgetId, "select", mutable = true))
            manager.updateAppWidget(widgetId, v)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.favoriteList)
        }
        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            if (WidgetSettings.pickerOpen(context, widgetId)) {
                renderPicker(context, manager, widgetId)
                return
            }
            val view = WidgetSettings.view(context, widgetId)
            val data = view.data
            val favorite = Favorites.find(context, data.id)
            val options = manager.getAppWidgetOptions(widgetId)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
                .takeIf { it > 0 } ?: 300
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220)
                .takeIf { it > 0 } ?: 220
            val compact = heightDp < 210
            val tiny = heightDp < 145 || widthDp < 185
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
                    v.setTextViewText(R.id.compactTime, if (focus.ongoing)
                        "До " + focus.lesson.time.substringAfter('-') else
                        focus.date.format(DateTimeFormatter.ofPattern(
                            if (tiny) "dd.MM" else "EE dd.MM", Locale.forLanguageTag("ru-RU"))) +
                            " · " + focus.lesson.time.substringBefore('-'))
                    v.setTextViewText(R.id.compactKind, SiteParser.shortKind(d.kind))
                    v.setTextViewText(R.id.compactRoom,
                        if (d.room == "—") "Аудитория не указана" else "ауд. " + d.room)
                    v.setTextColor(R.id.compactKind, WidgetTheme.lessonAccent(d.kind, widgetMode))
                } else {
                    v.setTextViewText(R.id.compactSubject, "Расписание пока не загружено")
                    v.setTextViewText(R.id.compactTeacher, "Нажми, чтобы открыть приложение")
                    v.setTextViewText(R.id.compactTime, "")
                    v.setTextViewText(R.id.compactKind, "")
                    v.setTextViewText(R.id.compactRoom, "")
                }
                v.setOnClickPendingIntent(R.id.compactRoot, open(context, widgetId, data.id))
                v.setTextColor(R.id.groups, widgetPalette.accent)
                v.setOnClickPendingIntent(R.id.groups, action(context, widgetId, "groups"))
                manager.updateAppWidget(widgetId, v)
                return
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
            val newest = notices.firstOrNull { it.isNew } ?: notices.firstOrNull()
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
                if (data.cached().isEmpty()) "Расписание загружается…" else "Пар на этот день нет")
            val status = if (data.error().isNotBlank()) "Офлайн · сохранённая версия"
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
            manager.updateAppWidget(widgetId, v)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.lessonList)
        }
    }
}
