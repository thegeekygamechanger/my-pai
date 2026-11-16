package app.pocketagent.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pocketagent.graph
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Wakes the app for a scheduled task when it is not open. Android runs this at about the requested time
 * (it may be some minutes late to save battery), with a network connection, for up to ten minutes.
 */
class ScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val started = g.scheduler.runDue()
        // Stay alive while the tasks that were just started are working (the system allows about ten minutes).
        val until = System.currentTimeMillis() + 9 * 60_000
        while (started.any { g.runs.isRunning(it) } && System.currentTimeMillis() < until) delay(1000)
        g.scheduler.arm()
        return Result.success()
    }

    companion object {
        private const val NAME = "scheduled-tasks"

        fun arm(ctx: Context, atMillis: Long?) {
            val wm = WorkManager.getInstance(ctx)
            if (atMillis == null) { wm.cancelUniqueWork(NAME); return }
            val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
                .setInitialDelay((atMillis - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
