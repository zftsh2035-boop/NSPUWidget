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

    fun show(c: Context, fresh: List<Notice>) {
        if (fresh.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Объявления НГПУ", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Новые сообщения над расписанием группы 3.008.2.26"
            })
        }
        val pendingIntent = PendingIntent.getActivity(c, 12,
            Intent(c, MainActivity::class.java).putExtra("show_notices", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val heading = if (fresh.size == 1) "Новое объявление НГПУ" else "Новых объявлений: ${fresh.size}"
        val body = fresh.first().text.take(400)
        val notification = NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(3328, notification)
    }
}
