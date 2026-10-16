package ru.nspu.schedule

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class Sync(ctx: Context, params: WorkerParameters): CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val outcome = Store.fetchAndSave(applicationContext)
        ScheduleWidget.renderAll(applicationContext)
        AnnouncementNotifier.show(applicationContext, outcome.newNotices)
        Result.success()
    } catch (e: Exception) {
        Store.saveError(applicationContext, e)
        ScheduleWidget.renderAll(applicationContext)
        Result.retry()
    }

    companion object {
        fun schedule(c: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val periodic = PeriodicWorkRequestBuilder<Sync>(3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("nspu-sync", ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
        fun now(c: Context) {
            val request = OneTimeWorkRequestBuilder<Sync>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork("nspu-manual", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
