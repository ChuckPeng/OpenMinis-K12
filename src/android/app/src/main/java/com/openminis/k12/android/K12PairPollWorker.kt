package com.openminis.k12.android

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * While a pairing is waiting for the parent, ask for status about every
 * 15 minutes. The screen also long-polls; this covers a tablet that is
 * not showing the pairing form.
 */
class K12PairPollWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        K12SyncRuntime.pollPending(waitSeconds = 20)
        return Result.success()
    }
}
