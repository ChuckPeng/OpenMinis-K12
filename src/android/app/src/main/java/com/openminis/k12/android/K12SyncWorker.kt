package com.openminis.k12.android

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openminis.k12.device.SyncRunResult

/** One periodic or one-off sync. Does not log request or response bodies. */
class K12SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return when (K12SyncRuntime.runOnce()) {
            is SyncRunResult.Retry -> Result.retry()
            is SyncRunResult.Stop -> Result.failure()
            else -> Result.success()
        }
    }
}
