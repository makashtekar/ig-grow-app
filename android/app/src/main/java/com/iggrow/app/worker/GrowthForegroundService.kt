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
import com.iggrow.app.ig.IgActionResult
import com.iggrow.app.ig.IgGraphqlExecutor
import com.iggrow.app.safety.ActionBudget
import com.iggrow.app.safety.ActionQueueStore
import com.iggrow.app.safety.IgExecutionOutcome
import com.iggrow.app.safety.QueuedActionType
import com.iggrow.app.safety.SafetyEngine
import com.iggrow.app.safety.WarmupTier
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
 * Foreground service that fetches daily AI tasks from ig-grow-api, discovers targets
 * via IgGraphqlExecutor, enforces SafetyEngine budgets & 30-day deduplication, and
 * paces one engagement action per randomized jitter interval.
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
        val actionStore = ActionQueueStore(applicationContext)
        val executor = IgGraphqlExecutor()
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

            val tier = runCatching { WarmupTier.valueOf(tasks.tier) }.getOrDefault(WarmupTier.TIER_1_COLD)
            val safeBudget = SafetyEngine.clampBudget(
                ActionBudget(
                    storyViews = tasks.actionBudgets.storyViews,
                    storyLikes = tasks.actionBudgets.storyLikes,
                    postLikes = tasks.actionBudgets.postLikes,
                    comments = tasks.actionBudgets.comments,
                    follows = tasks.actionBudgets.follows
                ),
                tier
            )
            val counters = actionStore.getTodayCounters()

            val targetHandle = tasks.discoverySources
                .sortedByDescending { it.priority }
                .firstOrNull { it.type == "COMPETITOR_POSTS" || it.type == "COMPETITOR_LIKERS" }
                ?.target

            var actionTypePerformed: QueuedActionType? = null
            var actionResult: IgActionResult? = null
            var targetIdUsed: String? = null

            if (targetHandle != null && counters.postLikes < safeBudget.postLikes) {
                val (discoverRes, mediaList) = executor.fetchUserPosts(session, targetHandle)
                if (SafetyEngine.isCircuitBreakerOutcome(discoverRes.outcome)) {
                    reportAndHalt(api, session.dsUserId, "DISCOVERY", discoverRes, null)
                    return
                }
                val candidate = mediaList.firstOrNull { !actionStore.hasInteractedRecently("like:${it.mediaId}") }
                if (candidate != null) {
                    actionTypePerformed = QueuedActionType.POST_LIKE
                    targetIdUsed = candidate.mediaId
                    actionResult = executor.likeMedia(session, candidate.mediaId)
                    if (actionResult.outcome == IgExecutionOutcome.SUCCESS) {
                        actionStore.markInteracted("like:${candidate.mediaId}")
                        actionStore.incrementCounter(QueuedActionType.POST_LIKE)
                    }
                }
            } else if (targetHandle != null && counters.comments < safeBudget.comments && tasks.commentTemplates.isNotEmpty()) {
                val (discoverRes, mediaList) = executor.fetchUserPosts(session, targetHandle)
                if (SafetyEngine.isCircuitBreakerOutcome(discoverRes.outcome)) {
                    reportAndHalt(api, session.dsUserId, "DISCOVERY", discoverRes, null)
                    return
                }
                val candidate = mediaList.firstOrNull { !actionStore.hasInteractedRecently("comment:${it.mediaId}") }
                if (candidate != null) {
                    val comment = tasks.commentTemplates.random()
                    actionTypePerformed = QueuedActionType.COMMENT
                    targetIdUsed = candidate.mediaId
                    actionResult = executor.commentMedia(session, candidate.mediaId, comment)
                    if (actionResult.outcome == IgExecutionOutcome.SUCCESS) {
                        actionStore.markInteracted("comment:${candidate.mediaId}")
                        actionStore.incrementCounter(QueuedActionType.COMMENT)
                    }
                }
            }

            if (actionResult != null && actionTypePerformed != null) {
                if (SafetyEngine.isCircuitBreakerOutcome(actionResult.outcome)) {
                    reportAndHalt(api, session.dsUserId, actionTypePerformed.name, actionResult, targetIdUsed)
                    return
                }
                updateNotification("Day ${tasks.dayNumber}: ${actionTypePerformed.name} -> ${actionResult.outcome}")
                runCatching {
                    api.sendTelemetry(
                        devUid = session.dsUserId,
                        body = TelemetryBatchRequest(
                            dsUserId = session.dsUserId,
                            events = listOf(
                                TelemetryEventDto(
                                    actionType = actionTypePerformed.name,
                                    outcome = actionResult.outcome.name,
                                    targetId = targetIdUsed,
                                    httpStatus = actionResult.httpStatus,
                                    errorMessage = actionResult.errorMessage,
                                    executedAt = Instant.now().toString()
                                )
                            )
                        )
                    )
                }
            } else {
                updateNotification("Day ${tasks.dayNumber}: Waiting for next window")
            }

            delay(SafetyEngine.nextJitterDelayMs())
        }
    }

    private suspend fun reportAndHalt(
        api: com.iggrow.app.cloud.GrowthApiService,
        dsUserId: String,
        actionType: String,
        result: IgActionResult,
        targetId: String?
    ) {
        updateNotification("Safety Pause: ${result.outcome}")
        runCatching {
            api.sendTelemetry(
                devUid = dsUserId,
                body = TelemetryBatchRequest(
                    dsUserId = dsUserId,
                    events = listOf(
                        TelemetryEventDto(
                            actionType = actionType,
                            outcome = result.outcome.name,
                            targetId = targetId,
                            httpStatus = result.httpStatus,
                            errorMessage = result.errorMessage,
                            executedAt = Instant.now().toString()
                        )
                    )
                )
            )
        }
        stopSelf()
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
