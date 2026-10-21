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
                WidgetDiagnostics.record(applicationContext, "sync_start_group_$id")
                val outcome = data.fetchAndSave(requestedAt)
                WidgetDiagnostics.record(applicationContext, "sync_ok_group_$id")
                ScheduleWidget.renderGroup(applicationContext, id)
                if (id == Favorites.primaryId(applicationContext)) {
                    AnnouncementNotifier.show(applicationContext, outcome.newNotices)
                    val eventIds = outcome.newNotices.filter { ImportantInfo.isEvent(it) }.map { it.id }.toSet()
                    val separateChanges = outcome.changes.filter { change ->
                        val lesson = change.lesson
                        lesson == null || ImportantInfo.notices(listOf(lesson), java.time.LocalDate.now(ZONE), data.termStart())
                            .none { it.id in eventIds }
                    }
                    AnnouncementNotifier.showChanges(applicationContext, separateChanges)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                WidgetDiagnostics.record(applicationContext, "sync_failed_group_$id", error = e)
                data.saveError(e)
                ScheduleWidget.renderGroup(applicationContext, id)
            }
        }
        if (failed) Result.retry() else Result.success()
    }
    companion object {
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(c: Context) {
            runCatching {
                val periodic = PeriodicWorkRequestBuilder<Sync>(3, TimeUnit.HOURS)
                    .setConstraints(constraints()).build()
                WorkManager.getInstance(c).enqueueUniquePeriodicWork("nspu-sync", ExistingPeriodicWorkPolicy.KEEP, periodic)
            }.onFailure { WidgetDiagnostics.record(c, "periodic_enqueue_failed", error = it) }
        }
        fun now(c: Context, groupId: Int = Store.scheduleGroupId(c)) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<Sync>()
                    .setInputData(workDataOf("group_id" to groupId, "requested_at" to System.currentTimeMillis()))
                    .setConstraints(constraints()).build()
                WorkManager.getInstance(c).enqueueUniqueWork("nspu-group-$groupId", ExistingWorkPolicy.KEEP, request)
            }.onFailure { WidgetDiagnostics.record(c, "sync_enqueue_failed_group_$groupId", error = it) }
        }
    }
}
