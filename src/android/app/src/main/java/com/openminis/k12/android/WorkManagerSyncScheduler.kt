package com.openminis.k12.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.openminis.k12.device.SyncSchedule
import com.openminis.k12.device.SyncScheduler
import com.openminis.k12.device.SyncTrigger
import java.util.concurrent.TimeUnit

/**
 * Unique WorkManager jobs. A second enqueue while one is pending is kept,
 * so foreground and pairing do not stack overlapping one-off runs.
 */
class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val appContext = context.applicationContext

    override fun ensurePeriodic(intervalMinutes: Long) {
        val minutes = intervalMinutes.coerceAtLeast(SyncSchedule.PERIODIC_MINUTES)
        val request = PeriodicWorkRequestBuilder<K12SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncSchedule.BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            SyncSchedule.PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun enqueueSoon(trigger: SyncTrigger) {
        val request = OneTimeWorkRequestBuilder<K12SyncWorker>()
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SyncSchedule.BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            SyncSchedule.SOON_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel() {
        val manager = WorkManager.getInstance(appContext)
        manager.cancelUniqueWork(SyncSchedule.PERIODIC_WORK)
        manager.cancelUniqueWork(SyncSchedule.SOON_WORK)
    }

    private fun connected() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private companion object {
        const val KEY_TRIGGER = "trigger"
    }
}
