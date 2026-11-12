package com.iggrow.app.ig

import com.iggrow.app.cloud.DailyTasksResponse
import com.iggrow.app.cloud.GrowthApiService
import com.iggrow.app.cloud.TelemetryBatchRequest
import com.iggrow.app.cloud.TelemetryEventDto
import com.iggrow.app.safety.ActionBudget
import com.iggrow.app.safety.ActionQueueStore
import com.iggrow.app.safety.ActivityLogEntry
import com.iggrow.app.safety.IgExecutionOutcome
import com.iggrow.app.safety.QueuedActionType
import com.iggrow.app.safety.SafetyEngine
import com.iggrow.app.safety.WarmupTier
import com.iggrow.app.session.IgSession
import java.time.Instant

data class SingleStepReport(
    val actionType: String,
    val targetId: String?,
    val targetHandle: String?,
    val outcome: IgExecutionOutcome,
    val httpStatus: Int,
    val message: String,
    val circuitBreakerTriggered: Boolean = false
)

/**
 * Shared single-action runner used by both GrowthForegroundService and the
 * Dashboard's per-action manual "Run" buttons.
 */
class GrowthActionRunner(
    private val executor: IgGraphqlExecutor = IgGraphqlExecutor()
) {
    suspend fun executeOneStep(
        session: IgSession,
        tasks: DailyTasksResponse,
        actionStore: ActionQueueStore,
        api: GrowthApiService
    ): SingleStepReport {
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

        val nextType = when {
            counters.postLikes < safeBudget.postLikes -> QueuedActionType.POST_LIKE
            counters.storyViews < safeBudget.storyViews -> QueuedActionType.STORY_VIEW
            counters.storyLikes < safeBudget.storyLikes -> QueuedActionType.STORY_LIKE
            counters.comments < safeBudget.comments && tasks.commentTemplates.isNotEmpty() -> QueuedActionType.COMMENT
            counters.follows < safeBudget.follows -> QueuedActionType.FOLLOW
            else -> null
        } ?: return SingleStepReport(
            actionType = "NONE",
            targetId = null,
            targetHandle = null,
            outcome = IgExecutionOutcome.SUCCESS,
            httpStatus = 200,
            message = "All today's safety-capped budgets are completed."
        )

        return executeSpecificAction(nextType, session, tasks, actionStore, api)
    }

    suspend fun executeSpecificAction(
        actionType: QueuedActionType,
        session: IgSession,
        tasks: DailyTasksResponse,
        actionStore: ActionQueueStore,
        api: GrowthApiService
    ): SingleStepReport {
        if (tasks.accountStatus.startsWith("PAUSED")) {
            return SingleStepReport(
                actionType = actionType.name,
                targetId = null,
                targetHandle = null,
                outcome = IgExecutionOutcome.FAILED,
                httpStatus = 0,
                message = "Account is paused: ${tasks.accountStatus}"
            )
        }

        val targetHandle = tasks.discoverySources
            .sortedByDescending { it.priority }
            .firstOrNull { it.type == "COMPETITOR_POSTS" || it.type == "COMPETITOR_LIKERS" }
            ?.target
            ?: return SingleStepReport(
                actionType = actionType.name,
                targetId = null,
                targetHandle = null,
                outcome = IgExecutionOutcome.FAILED,
                httpStatus = 0,
                message = "No competitor target configured in discoverySources."
            )

        val (discoverRes, mediaList) = executor.fetchUserPosts(session, targetHandle)
        if (discoverRes.outcome != IgExecutionOutcome.SUCCESS) {
            val breaker = SafetyEngine.isCircuitBreakerOutcome(discoverRes.outcome)
            reportTelemetry(api, session.dsUserId, "DISCOVERY", discoverRes, null)
            return SingleStepReport(
                actionType = "DISCOVERY",
                targetId = null,
                targetHandle = targetHandle,
                outcome = discoverRes.outcome,
                httpStatus = discoverRes.httpStatus,
                message = "Discovery @$targetHandle failed: ${discoverRes.errorMessage ?: discoverRes.outcome}",
                circuitBreakerTriggered = breaker
            )
        }

        if (mediaList.isEmpty()) {
            return SingleStepReport(
                actionType = "DISCOVERY",
                targetId = null,
                targetHandle = targetHandle,
                outcome = IgExecutionOutcome.FAILED,
                httpStatus = discoverRes.httpStatus,
                message = "Discovery @$targetHandle returned 0 posts (check Logcat tag IG_GROW_API)."
            )
        }

        return when (actionType) {
            QueuedActionType.POST_LIKE -> {
                val candidate = mediaList.firstOrNull { !actionStore.hasInteractedRecently("like:${it.mediaId}") }
                    ?: mediaList.first()
                val res = executor.likeMedia(session, candidate.mediaId)
                if (res.outcome == IgExecutionOutcome.SUCCESS) {
                    actionStore.markInteracted("like:${candidate.mediaId}")
                    actionStore.incrementCounter(QueuedActionType.POST_LIKE)
                }
                val breaker = SafetyEngine.isCircuitBreakerOutcome(res.outcome)
                reportTelemetry(api, session.dsUserId, actionType.name, res, candidate.mediaId)
                recordReport(
                    actionStore,
                    SingleStepReport(
                        actionType = actionType.name,
                        targetId = candidate.mediaId,
                        targetHandle = targetHandle,
                        outcome = res.outcome,
                        httpStatus = res.httpStatus,
                        message = "Liked post ${candidate.mediaId} (@$targetHandle) -> ${res.outcome} (HTTP ${res.httpStatus})",
                        circuitBreakerTriggered = breaker
                    )
                )
            }

            QueuedActionType.COMMENT -> {
                val candidate = mediaList.firstOrNull { !actionStore.hasInteractedRecently("comment:${it.mediaId}") }
                    ?: mediaList.first()
                val comment = tasks.commentTemplates.randomOrNull() ?: "Love this!"
                val res = executor.commentMedia(session, candidate.mediaId, comment)
                if (res.outcome == IgExecutionOutcome.SUCCESS) {
                    actionStore.markInteracted("comment:${candidate.mediaId}")
                    actionStore.incrementCounter(QueuedActionType.COMMENT)
                }
                val breaker = SafetyEngine.isCircuitBreakerOutcome(res.outcome)
                reportTelemetry(api, session.dsUserId, actionType.name, res, candidate.mediaId)
                recordReport(
                    actionStore,
                    SingleStepReport(
                        actionType = actionType.name,
                        targetId = candidate.mediaId,
                        targetHandle = targetHandle,
                        outcome = res.outcome,
                        httpStatus = res.httpStatus,
                        message = "Commented \"$comment\" on ${candidate.mediaId} -> ${res.outcome} (HTTP ${res.httpStatus})",
                        circuitBreakerTriggered = breaker
                    )
                )
            }

            QueuedActionType.FOLLOW -> {
                val ownerId = mediaList.firstOrNull { it.ownerUserId.isNotBlank() }?.ownerUserId
                    ?: return recordReport(
                        actionStore,
                        SingleStepReport(
                            actionType = actionType.name,
                            targetId = null,
                            targetHandle = targetHandle,
                            outcome = IgExecutionOutcome.FAILED,
                            httpStatus = 200,
                            message = "Could not resolve ownerUserId for @$targetHandle"
                        )
                    )
                val res = executor.followUser(session, ownerId)
                if (res.outcome == IgExecutionOutcome.SUCCESS) {
                    actionStore.markInteracted("follow:$ownerId")
                    actionStore.incrementCounter(QueuedActionType.FOLLOW)
                }
                val breaker = SafetyEngine.isCircuitBreakerOutcome(res.outcome)
                reportTelemetry(api, session.dsUserId, actionType.name, res, ownerId)
                recordReport(
                    actionStore,
                    SingleStepReport(
                        actionType = actionType.name,
                        targetId = ownerId,
                        targetHandle = targetHandle,
                        outcome = res.outcome,
                        httpStatus = res.httpStatus,
                        message = "Follow @$targetHandle ($ownerId) -> ${res.outcome} (HTTP ${res.httpStatus})",
                        circuitBreakerTriggered = breaker
                    )
                )
            }

            QueuedActionType.STORY_VIEW, QueuedActionType.STORY_LIKE -> {
                val ownerId = mediaList.firstOrNull { it.ownerUserId.isNotBlank() }?.ownerUserId
                    ?: return recordReport(
                        actionStore,
                        SingleStepReport(
                            actionType = actionType.name,
                            targetId = null,
                            targetHandle = targetHandle,
                            outcome = IgExecutionOutcome.FAILED,
                            httpStatus = 200,
                            message = "Could not resolve ownerUserId for @$targetHandle stories"
                        )
                    )
                val (storyRes, stories) = executor.fetchUserStories(session, ownerId)
                if (storyRes.outcome != IgExecutionOutcome.SUCCESS || stories.isEmpty()) {
                    return recordReport(
                        actionStore,
                        SingleStepReport(
                            actionType = actionType.name,
                            targetId = ownerId,
                            targetHandle = targetHandle,
                            outcome = storyRes.outcome,
                            httpStatus = storyRes.httpStatus,
                            message = "No active stories found for @$targetHandle (HTTP ${storyRes.httpStatus})",
                            circuitBreakerTriggered = SafetyEngine.isCircuitBreakerOutcome(storyRes.outcome)
                        )
                    )
                }
                val story = stories.first()
                val res = if (actionType == QueuedActionType.STORY_VIEW) {
                    executor.viewStory(
                        session = session,
                        reelId = story.reelId,
                        reelMediaId = story.mediaId,
                        reelMediaOwnerId = story.ownerUserId,
                        reelMediaTakenAt = story.takenAt
                    )
                } else {
                    executor.likeStory(session, story.mediaId)
                }
                if (res.outcome == IgExecutionOutcome.SUCCESS) {
                    actionStore.markInteracted("${actionType.name.lowercase()}:${story.mediaId}")
                    actionStore.incrementCounter(actionType)
                }
                val breaker = SafetyEngine.isCircuitBreakerOutcome(res.outcome)
                reportTelemetry(api, session.dsUserId, actionType.name, res, story.mediaId)
                recordReport(
                    actionStore,
                    SingleStepReport(
                        actionType = actionType.name,
                        targetId = story.mediaId,
                        targetHandle = targetHandle,
                        outcome = res.outcome,
                        httpStatus = res.httpStatus,
                        message = "${actionType.name} story ${story.mediaId} (@$targetHandle) -> ${res.outcome} (HTTP ${res.httpStatus})",
                        circuitBreakerTriggered = breaker
                    )
                )
            }
        }
    }

    private fun recordReport(actionStore: ActionQueueStore, report: SingleStepReport): SingleStepReport {
        actionStore.appendActivity(
            ActivityLogEntry(
                actionType = report.actionType,
                targetHandle = report.targetHandle,
                targetId = report.targetId,
                outcome = report.outcome.name,
                httpStatus = report.httpStatus,
                message = report.message
            )
        )
        return report
    }

    private suspend fun reportTelemetry(
        api: GrowthApiService,
        dsUserId: String,
        actionType: String,
        result: IgActionResult,
        targetId: String?
    ) {
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
    }
}
