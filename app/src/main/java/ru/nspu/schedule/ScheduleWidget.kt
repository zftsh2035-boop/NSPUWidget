package ru.nspu.schedule

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class ScheduleWidget: AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        Sync.schedule(c)
        if (Store.cached(c).isEmpty()) Sync.now(c)
        ids.forEach { render(c, manager, it) }
    }
    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        if (intent.action == "ru.nspu.schedule.NAV") {
            when (intent.getStringExtra("cmd")) {
                "prev" -> Store.offset(c, -1)
                "next" -> Store.offset(c, 1)
                "today" -> Store.resetDate(c)
                "refresh" -> Sync.now(c)
            }
            renderAll(c)
        }
    }
    companion object {
        fun renderAll(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            manager.getAppWidgetIds(ComponentName(c, ScheduleWidget::class.java)).forEach { render(c, manager, it) }
        }
        private fun action(c: Context, code: Int, cmd: String): PendingIntent {
            val intent = Intent(c, ScheduleWidget::class.java).setAction("ru.nspu.schedule.NAV").putExtra("cmd", cmd)
            return PendingIntent.getBroadcast(c, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        private fun render(c: Context, m: AppWidgetManager, id: Int) {
            val v = RemoteViews(c.packageName, R.layout.widget)
            val date = Store.date(c)
            val day = date.dayOfWeek.value - 1
            val fmt = date.format(DateTimeFormatter.ofPattern("d MMMM", Locale("ru")))
            v.setTextViewText(R.id.header, "${if (day in 0..5) DAYS[day] else "Воскресенье"}, $fmt")
            v.setTextViewText(R.id.subtitle, "НГПУ · 3.008.2.26 · ${Store.week(date)}-я нед. · ${Store.group(c)}")

            val notices = Store.notices(c)
            val unread = notices.count { it.isNew }
            val newest = notices.firstOrNull { it.isNew } ?: notices.firstOrNull()
            if (newest != null) {
                val outdated = SiteParser.noticeIsOutdated(newest, LocalDate.now(ZONE))
                val tag = when { newest.isNew -> "● НОВОЕ ОБЪЯВЛЕНИЕ"; outdated -> "АРХИВНОЕ ОБЪЯВЛЕНИЕ"; else -> "ОБЪЯВЛЕНИЕ НГПУ" }
                v.setViewVisibility(R.id.noticeBox, View.VISIBLE)
                v.setTextViewText(R.id.noticeLabel, tag)
                v.setTextColor(R.id.noticeLabel, if (newest.isNew) 0xFFFFD27A.toInt() else 0xFFB8C9D9.toInt())
                v.setTextViewText(R.id.noticeText, newest.text)
                v.setTextViewText(R.id.noticeMeta, "${SiteParser.formattedNoticeDate(newest)}${if (notices.size > 1) " · ещё ${notices.size - 1}" else ""}")
            } else v.setViewVisibility(R.id.noticeBox, View.GONE)

            val entries = Store.forDate(c, date)
            val ids = intArrayOf(R.id.lesson1, R.id.lesson2, R.id.lesson3, R.id.lesson4)
            ids.forEachIndexed { i, view ->
                val lesson = entries.getOrNull(i)
                val label = if (lesson == null) {
                    if (i == 0) "Пар нет (или ещё нет данных)" else ""
                } else {
                    "${if (lesson.changed) "● ИЗМЕНЕНО · " else ""}${lesson.time}  ${lesson.raw.take(115)}"
                }
                v.setTextViewText(view, label)
                v.setViewVisibility(view, if (label.isBlank()) View.GONE else View.VISIBLE)
                v.setTextColor(view, if (lesson?.changed == true) 0xFFFFD27A.toInt() else 0xFFECF4FF.toInt())
            }
            val remainder = (entries.size - ids.size).coerceAtLeast(0)
            val status = if (Store.cached(c).isEmpty()) "Открой приложение для загрузки" else
                "Обновлено ${Store.status(c)}${if (remainder > 0) " · ещё $remainder" else ""}"
            v.setTextViewText(R.id.status, if (Store.error(c).isNotEmpty()) "Офлайн · сохранённая версия" else status)
            v.setOnClickPendingIntent(R.id.prev, action(c, 1, "prev"))
            v.setOnClickPendingIntent(R.id.next, action(c, 2, "next"))
            v.setOnClickPendingIntent(R.id.today, action(c, 4, "today"))
            v.setOnClickPendingIntent(R.id.refresh, action(c, 3, "refresh"))
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.header, open)
            v.setOnClickPendingIntent(R.id.noticeBox, PendingIntent.getActivity(c, 11,
                Intent(c, MainActivity::class.java).putExtra("show_notices", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            m.updateAppWidget(id, v)
        }
    }
}
