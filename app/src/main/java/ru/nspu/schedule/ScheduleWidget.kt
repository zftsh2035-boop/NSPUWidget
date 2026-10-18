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

class ScheduleWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Sync.schedule(context)
        if (Store.cached(context).isEmpty()) Sync.now(context)
        ids.forEach { render(context, manager, it) }
        if (ids.isNotEmpty()) FocusClock.schedule(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        render(context, manager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "ru.nspu.schedule.NAV") {
            when (intent.getStringExtra("cmd")) {
                "prev" -> Store.offset(context, -1)
                "next" -> Store.offset(context, 1)
                "today" -> Store.resetDate(context)
                "refresh" -> Sync.now(context)
                "dismiss" -> Store.dismissWidgetNotice(context, intent.getStringExtra("notice_id") ?: "")
            }
            renderAll(context)
        }
    }
    companion object {
        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ScheduleWidget::class.java))
            ids.forEach { render(context, manager, it) }
            if (ids.isNotEmpty()) FocusClock.schedule(context)
        }
        private fun action(context: Context, code: Int, cmd: String): PendingIntent {
            val intent = Intent(context, ScheduleWidget::class.java)
                .setAction("ru.nspu.schedule.NAV").putExtra("cmd", cmd)
            return PendingIntent.getBroadcast(context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val options = manager.getAppWidgetOptions(widgetId)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)
            val compact = heightDp in 1..209
            val medium = heightDp in 210..344
            if (compact) {
                val v = RemoteViews(context.packageName, R.layout.widget_compact)
                val focus = Store.focus(context)
                v.setTextViewText(R.id.compactHeader, "НГПУ · " + Store.scheduleGroupCode(context))
                if (focus != null) {
                    val d = SiteParser.details(focus.lesson, focus.date, Store.termStart(context))
                    v.setTextViewText(R.id.compactSubject, d.subject)
                    v.setTextViewText(R.id.compactTeacher, d.teacher)
                    v.setTextViewText(R.id.compactTime, if (focus.ongoing)
                        "До " + focus.lesson.time.substringAfter('-') else
                        focus.date.format(DateTimeFormatter.ofPattern("EE dd.MM", Locale.forLanguageTag("ru-RU"))) +
                            " · " + focus.lesson.time.substringBefore('-'))
                    v.setTextViewText(R.id.compactKind, SiteParser.shortKind(d.kind))
                    v.setTextViewText(R.id.compactRoom, "ауд. " + d.room)
                    v.setTextColor(R.id.compactKind, SiteParser.lessonColor(d.kind))
                } else {
                    v.setTextViewText(R.id.compactSubject, "Расписание пока не загружено")
                    v.setTextViewText(R.id.compactTeacher, "Нажми, чтобы открыть приложение")
                    v.setTextViewText(R.id.compactTime, "")
                    v.setTextViewText(R.id.compactKind, "")
                    v.setTextViewText(R.id.compactRoom, "")
                }
                val open = PendingIntent.getActivity(context, 10,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                v.setOnClickPendingIntent(R.id.compactHeader, open)
                v.setOnClickPendingIntent(R.id.compactSubject, open)
                manager.updateAppWidget(widgetId, v)
                return
            }
            val v = RemoteViews(context.packageName, R.layout.widget)
            val date = Store.date(context)
            val name = if (date.dayOfWeek.value in 1..6) DAYS[date.dayOfWeek.value - 1] else "Воскресенье"
            v.setTextViewText(R.id.header,
                "$name, " + date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru-RU"))))
            v.setTextViewText(R.id.subtitle,
                "Группа " + Store.scheduleGroupCode(context) + " · неделя " + Store.week(context, date) + " · " + Store.group(context))
            val notices = Store.widgetNotices(context)
            val newest = notices.firstOrNull { it.isNew } ?: notices.firstOrNull()
            if (newest != null && !medium) {
                v.setViewVisibility(R.id.noticeBox, View.VISIBLE)
                v.setTextViewText(R.id.noticeLabel, if (newest.isNew) "● НОВОЕ ОБЪЯВЛЕНИЕ" else "ОБЪЯВЛЕНИЕ НГПУ")
                v.setTextViewText(R.id.noticeText, newest.text)
                val dismiss = Intent(context, ScheduleWidget::class.java)
                    .setAction("ru.nspu.schedule.NAV")
                    .putExtra("cmd", "dismiss").putExtra("notice_id", newest.id)
                v.setOnClickPendingIntent(R.id.noticeDismiss, PendingIntent.getBroadcast(context,
                    100 + widgetId, dismiss,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                v.setTextViewText(R.id.noticeMeta,
                    if (notices.size > 1) "Ещё " + (notices.size - 1) + " · открыть" else "Нажми, чтобы прочитать")
            } else v.setViewVisibility(R.id.noticeBox, View.GONE)

            val lessons = Store.forDate(context, date)
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
                if (Store.cached(context).isEmpty()) "Расписание загружается…" else "Пар на этот день нет")
            val status = if (Store.error(context).isNotBlank()) "Офлайн · сохранённая версия"
                else "Обновлено " + Store.status(context)
            v.setTextViewText(R.id.status, status)
            v.setOnClickPendingIntent(R.id.prev, action(context, 1, "prev"))
            v.setOnClickPendingIntent(R.id.next, action(context, 2, "next"))
            v.setOnClickPendingIntent(R.id.today, action(context, 3, "today"))
            v.setOnClickPendingIntent(R.id.refresh, action(context, 4, "refresh"))
            val open = PendingIntent.getActivity(context, 10,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            v.setOnClickPendingIntent(R.id.header, open)
            v.setPendingIntentTemplate(R.id.lessonList, open)
            v.setOnClickPendingIntent(R.id.noticeBox, PendingIntent.getActivity(context, 11,
                Intent(context, MainActivity::class.java).putExtra("show_notices", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(widgetId, v)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.lessonList)
        }
    }
}
