package ru.nspu.schedule

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object AnnouncementNotifier {
    private const val CHANNEL_ID = "nspu-announcements"

    fun showChanges(c: Context, changes: List<ScheduleChange>) {
        if (changes.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("nspu-changes",
            "Изменения расписания", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(c, 15,
            Intent(c, MainActivity::class.java).putExtra("show_changes", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = changes.take(3).joinToString("\n") { it.message }
        manager.notify(3329, NotificationCompat.Builder(c, "nspu-changes")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (changes.size == 1) "Изменение расписания НГПУ"
                else "Изменений расписания: " + changes.size)
            .setContentText(changes.first().message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).build())
    }

    fun show(c: Context, fresh: List<Notice>) {
        if (fresh.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val assessments = fresh.filter { ImportantInfo.isAssessment(it) }
        val messages = fresh.filterNot { ImportantInfo.isAssessment(it) }
        if (assessments.isNotEmpty()) showBatch(c, assessments, true)
        if (messages.isNotEmpty()) showBatch(c, messages, false)
    }

    private fun showBatch(c: Context, fresh: List<Notice>, assessments: Boolean) {
        val channel = if (assessments) "nspu-assessments" else CHANNEL_ID
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channel,
                if (assessments) "Зачёты и экзамены" else "Объявления НГПУ", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = if (assessments) "Одно сводное уведомление об обновлении списка" else "Новые сообщения над расписанием группы"
            })
        }
        val pendingIntent = PendingIntent.getActivity(c, if (assessments) 16 else 12,
            Intent(c, MainActivity::class.java).putExtra(if (assessments) "show_assessments" else "show_notices", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val heading = if (assessments) "Зачёты и экзамены · обновлений: ${fresh.size}"
            else if (fresh.size == 1) "Новое объявление НГПУ" else "Новых объявлений: ${fresh.size}"
        val body = fresh.take(3).joinToString("\n\n") { it.text.take(250) }
        val notification = NotificationCompat.Builder(c, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(if (assessments) 3330 else 3328, notification)
    }
}
