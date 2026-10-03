package com.myra.assistant.data.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager is deliberately a wake mechanism only.  It never writes Room
 * memory directly: every durable operation returns to MemoryBrainCoordinator.
 */
class AiriMemoryWakeWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = try {
        MemoryBrainCoordinator.get(applicationContext).runDurableBackgroundWork()
        Result.success()
    } catch (_: Throwable) {
        Result.retry()
    }
}

interface AiriMemoryWakeScheduler { fun wake(delayMs: Long = 0L) }

object NoopAiriMemoryWakeScheduler : AiriMemoryWakeScheduler { override fun wake(delayMs: Long) = Unit }

class WorkManagerAiriMemoryWakeScheduler(private val context: Context) : AiriMemoryWakeScheduler {
    override fun wake(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<AiriMemoryWakeWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        // APPEND_OR_REPLACE guarantees that a delayed retry requested by the
        // currently RUNNING worker remains queued behind it. Room claims still
        // serialize execution through the one coordinator/store owner.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    companion object { private const val WORK_NAME = "lyra-airi-memory-wake" }
}

object AiriMemoryWakePlanner {
    fun delayUntil(now: Long, earliestEligibleAt: Long): Long =
        (earliestEligibleAt - now).coerceAtLeast(0L)
}
