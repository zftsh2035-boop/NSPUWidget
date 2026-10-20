package ru.nspu.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** One next reminder, plus one display boundary; no polling or permanent service. */
internal object NoteReminders {
    const val ACTION = "ru.nspu.schedule.NOTE_TICK"
    private const val CHANNEL = "personal-note-reminders"
    private fun alarms(c: Context) = c.getSystemService(AlarmManager::class.java)
    fun exactAllowed(c: Context) = Build.VERSION.SDK_INT < 31 || alarms(c).canScheduleExactAlarms()
    fun notificationsAllowed(c: Context): Boolean {
        val manager = c.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun pending(c: Context, code: Int) = PendingIntent.getBroadcast(c, code,
        Intent(c, NoteReminderReceiver::class.java).setAction(ACTION)
            .setData(Uri.parse("nspuwidget://note-clock/$code")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized fun reschedule(c: Context) {
        val now = System.currentTimeMillis()
        val notes = Notes.all(c)
        val plan = NoteAlarmPlan.of(notes, Notes.delivered(c), now)
        for ((note, at) in plan.due) {
            if (Notes.markDelivered(c, note.id, at)) {
                if (at >= now - 24 * 60 * 60 * 1000L) runCatching { show(c, note) }
                    .onFailure { android.util.Log.e("NSPUWidget", "Cannot show note notification", it) }
            }
        }
        val manager = alarms(c)
        val reminder = pending(c, 411)
        manager.cancel(reminder)
        plan.nextReminder?.let { at ->
            try {
                if (exactAllowed(c)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reminder)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reminder)
            } catch (_: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reminder)
            }
        }
        val boundary = pending(c, 412)
        manager.cancel(boundary)
        plan.nextDisplayBoundary?.let {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it, boundary)
        }
    }

    fun dismiss(c: Context, id: String) = c.getSystemService(NotificationManager::class.java).cancel("note:$id", 0)

    private fun show(c: Context, note: PersonalNote) {
        if (!notificationsAllowed(c)) return
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Личные напоминания",
            NotificationManager.IMPORTANCE_HIGH))
        val intent = NotesNavigation.fill(Intent(c, MainActivity::class.java)
            .setData(Uri.parse("nspuwidget://note/${note.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_group_id", note.target.groupId), note.target, note)
        val content = PendingIntent.getActivity(c, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify("note:${note.id}", 0, NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_note).setContentTitle(note.target.subject)
            .setContentText(note.preview).setStyle(NotificationCompat.BigTextStyle().bigText(note.text))
            .setContentIntent(content).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}

class NoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(NoteReminders.ACTION, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NoteReminders.reschedule(context)
                Notes.all(context).map { it.target.groupId }.distinct().forEach { Store.changed(context, it) }
                ScheduleWidget.renderAll(context)
            } catch (e: Exception) { android.util.Log.e("NSPUWidget", "Note alarm failed", e) }
            finally { pending.finish() }
        }
    }
}
