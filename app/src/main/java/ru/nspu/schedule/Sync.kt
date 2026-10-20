package ru.nspu.schedule

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class Sync(ctx: Context, params: WorkerParameters): CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Store.initialize(applicationContext)
        val requestedId = inputData.getInt("group_id", -1)
        val ids = if (requestedId > 0) listOf(requestedId)
            else (listOf(Store.scheduleGroupId(applicationContext)) + Favorites.list(applicationContext).map { it.id }).distinct()
        val requestedAt = inputData.getLong("requested_at", System.currentTimeMillis())
        var failed = false
        for (id in ids) {
            // A removed shortcut must not keep waking the network on a queued retry.
            if (Favorites.find(applicationContext, id) == null) continue
            val data = Store.data(applicationContext, id)
            try {
                val outcome = data.fetchAndSave(requestedAt)
                ScheduleWidget.renderGroup(applicationContext, id)
                if (id == Favorites.primaryId(applicationContext)) {
                    AnnouncementNotifier.show(applicationContext, outcome.newNotices)
                    AnnouncementNotifier.showChanges(applicationContext, outcome.changes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                data.saveError(e)
                ScheduleWidget.renderGroup(applicationContext, id)
            }
        }
        if (failed) Result.retry() else Result.success()
    }
    companion object {
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(c: Context) {
            val periodic = PeriodicWorkRequestBuilder<Sync>(3, TimeUnit.HOURS)
                .setConstraints(constraints()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("nspu-sync", ExistingPeriodicWorkPolicy.KEEP, periodic)
        }
        fun now(c: Context, groupId: Int = Store.scheduleGroupId(c)) {
            val request = OneTimeWorkRequestBuilder<Sync>()
                .setInputData(workDataOf("group_id" to groupId, "requested_at" to System.currentTimeMillis()))
                .setConstraints(constraints()).build()
            WorkManager.getInstance(c).enqueueUniqueWork("nspu-group-$groupId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
