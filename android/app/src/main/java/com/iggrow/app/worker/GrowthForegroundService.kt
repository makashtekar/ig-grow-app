package com.iggrow.app.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.iggrow.app.cloud.GrowthApiClient
import com.iggrow.app.cloud.TelemetryBatchRequest
import com.iggrow.app.cloud.TelemetryEventDto
import com.iggrow.app.safety.IgExecutionOutcome
import com.iggrow.app.safety.SafetyEngine
import com.iggrow.app.session.SessionVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime

/**
 * Foreground service that fetches daily AI tasks from ig-grow-api and paces
 * execution on-device with randomized jitter and automatic circuit breaking.
 */
class GrowthForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            loopJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("IG Grow Autopilot active"))
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runGrowthLoop() }
        }
        return START_STICKY
    }

    private suspend fun runGrowthLoop() {
        val vault = SessionVault(applicationContext)
        val api = GrowthApiClient.create()
        val session = vault.getSession() ?: return

        while (scope.isActive) {
            if (!SafetyEngine.isWithinActiveHours(LocalTime.now())) {
                updateNotification("Sleeping (outside active hours)")
                delay(15 * 60_000L)
                continue
            }

            val tasks = runCatching {
                api.getDailyTasks(devUid = session.dsUserId, dsUserId = session.dsUserId)
            }.getOrNull()

            if (tasks == null) {
                delay(5 * 60_000L)
                continue
            }

            if (tasks.accountStatus.startsWith("PAUSED")) {
                updateNotification("Paused: ${tasks.accountStatus}")
                stopSelf()
                return
            }

            updateNotification("Running Day ${tasks.dayNumber}: ${tasks.focus}")

            // Heartbeat telemetry; individual discovery + engagement actions will be
            // dispatched from the Room action queue in the next iteration.
            runCatching {
                api.sendTelemetry(
                    devUid = session.dsUserId,
                    body = TelemetryBatchRequest(
                        dsUserId = session.dsUserId,
                        events = listOf(
                            TelemetryEventDto(
                                actionType = "DISCOVERY",
                                outcome = IgExecutionOutcome.SUCCESS.name,
                                executedAt = Instant.now().toString()
                            )
                        )
                    )
                )
            }

            delay(SafetyEngine.nextJitterDelayMs())
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "IG Grow Autopilot",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IG Grow")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.iggrow.app.action.START"
        const val ACTION_STOP = "com.iggrow.app.action.STOP"
        private const val CHANNEL_ID = "ig_grow_autopilot"
        private const val NOTIFICATION_ID = 1001
    }
}
