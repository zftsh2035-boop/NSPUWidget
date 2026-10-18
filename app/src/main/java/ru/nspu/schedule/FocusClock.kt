package ru.nspu.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime

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
        val transition = LessonFocus.transitionAt(now, Store.focus(context, now)) ?: return
        val epochMillis = transition.atZone(ZONE).toInstant().toEpochMilli() + 2000
        // Inexact while-idle alarm; battery optimizations may postpone redraw.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMillis, pending)
    }
}

class FocusClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "ru.nspu.schedule.FOCUS_TICK") {
            ScheduleWidget.renderAll(context)
        }
    }
}
