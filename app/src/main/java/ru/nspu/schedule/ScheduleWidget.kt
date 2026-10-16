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
            v.setTextViewText(R.id.subtitle, "НГПУ · ${Store.scheduleGroupCode(c)} · ${Store.week(date)}-я нед. · ${Store.group(c)}")

            val notices = Store.notices(c)
            val unread = notices.count { it.isNew }
            val newest = notices.firstOrNull { it.isNew } ?: notices.firstOrNull()
            if (newest != null) {
                val tag = if (newest.isNew) "● НОВОЕ ОБЪЯВЛЕНИЕ" else "ОБЪЯВЛЕНИЕ НГПУ"
                v.setViewVisibility(R.id.noticeBox, View.VISIBLE)
                v.setTextViewText(R.id.noticeLabel, tag)
                v.setTextColor(R.id.noticeLabel, if (newest.isNew) 0xFFFFD27A.toInt() else 0xFFB8C9D9.toInt())
                v.setTextViewText(R.id.noticeText, newest.text)
                v.setTextViewText(R.id.noticeMeta, "${SiteParser.formattedNoticeDate(newest)}${if (notices.size > 1) " · ещё ${notices.size - 1}" else ""}")
            } else v.setViewVisibility(R.id.noticeBox, View.GONE)

            val entries = Store.forDate(c, date)
            val rows = arrayOf(
                intArrayOf(R.id.lesson1Box, R.id.lesson1Time, R.id.lesson1Subject, R.id.lesson1Teacher, R.id.lesson1Room, R.id.lesson1Stripe),
                intArrayOf(R.id.lesson2Box, R.id.lesson2Time, R.id.lesson2Subject, R.id.lesson2Teacher, R.id.lesson2Room, R.id.lesson2Stripe),
                intArrayOf(R.id.lesson3Box, R.id.lesson3Time, R.id.lesson3Subject, R.id.lesson3Teacher, R.id.lesson3Room, R.id.lesson3Stripe),
                intArrayOf(R.id.lesson4Box, R.id.lesson4Time, R.id.lesson4Subject, R.id.lesson4Teacher, R.id.lesson4Room, R.id.lesson4Stripe)
            )
            v.setViewVisibility(R.id.emptyState, if (entries.isEmpty()) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.emptyState,
                if (Store.cached(c).isEmpty()) "Загрузи расписание в приложении"
                else "Занятий на этот день нет")
            rows.forEachIndexed { index, ids ->
                val lesson = entries.getOrNull(index)
                v.setViewVisibility(ids[0], if (lesson == null) View.GONE else View.VISIBLE)
                if (lesson != null) {
                    val info = SiteParser.details(lesson)
                    v.setTextViewText(ids[1], lesson.time.substringBefore("-") + "\n" + lesson.time.substringAfter("-"))
                    v.setTextViewText(ids[2], info.subject)
                    v.setTextViewText(ids[3], info.teacher)
                    v.setTextViewText(ids[4], if (info.room == "—") "Не указана" else "ауд. " + info.room)
                    v.setInt(ids[5], "setBackgroundColor", if (lesson.changed) 0xFFFFC470.toInt() else SiteParser.lessonColor(info.kind))
                }
            }
            val remainder = (entries.size - rows.size).coerceAtLeast(0)
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
