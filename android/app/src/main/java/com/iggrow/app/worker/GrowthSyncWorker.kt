package com.iggrow.app.worker

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iggrow.app.safety.SafetyEngine
import com.iggrow.app.session.SessionVault
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager wakeup that ensures GrowthForegroundService runs
 * during configured active hours when a valid IG session and plan exist.
 */
class GrowthSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val vault = SessionVault(applicationContext)
        val session = vault.getSession() ?: return Result.success()
        if (!session.isComplete() || vault.getActivePlanId().isNullOrBlank()) {
            return Result.success()
        }

        if (!SafetyEngine.isWithinActiveHours(LocalTime.now())) {
            return Result.success()
        }

        val intent = Intent(applicationContext, GrowthForegroundService::class.java).apply {
            action = GrowthForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(applicationContext, intent)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ig_grow_periodic_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GrowthSyncWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
