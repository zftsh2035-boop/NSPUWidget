package ru.nspu.schedule

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import kotlinx.coroutines.launch

/** The schedule is network-refreshed every three hours, but focus redraws at class boundaries. */
object FocusClock {
    private const val ACTION = "ru.nspu.schedule.FOCUS_TICK"
    fun schedule(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val broadcast = Intent(context, FocusClockReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(context, 833, broadcast,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.cancel(pending)
        val now = LocalDateTime.now(ZONE)
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ScheduleWidget::class.java))
        val transition = ids.map { WidgetSettings.groupId(context, it) }.distinct().mapNotNull { id ->
            LessonFocus.transitionAt(now, Store.data(context, id).focus(now))
        }.minOrNull() ?: return
        val epochMillis = transition.atZone(ZONE).toInstant().toEpochMilli() + 2000
        // Inexact while-idle alarm; battery optimizations may postpone redraw.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMillis, pending)
    }
}

class FocusClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "ru.nspu.schedule.FOCUS_TICK") {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { ScheduleWidget.renderAll(context) }
                finally { pending.finish() }
            }
        }
    }
}
