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
 *
 * Implements smart fallback and multi-target cascading: if target accounts have no active
 * stories, it marks them, skips story actions for this cycle, and smoothly advances to
 * Post Likes, Comments, or Follows without halting or getting stuck.
 */
class GrowthActionRunner(
    private val executor: IgGraphqlExecutor = IgGraphqlExecutor()
) {
    suspend fun executeOneStep(
        session: IgSession,
        tasks: DailyTasksResponse,
        actionStore: ActionQueueStore,
        api: GrowthApiService,
        skippedTypes: Set<QueuedActionType> = emptySet()
    ): SingleStepReport {
        if (tasks.accountStatus.startsWith("PAUSED")) {
            return SingleStepReport(
                actionType = "PAUSED",
                targetId = null,
                targetHandle = null,
                outcome = IgExecutionOutcome.FAILED,
                httpStatus = 0,
                message = "Account is paused: ${tasks.accountStatus}"
            )
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

        // Gather candidate action types in priority order
        val candidateTypes = mutableListOf<QueuedActionType>()
        if (counters.postLikes < safeBudget.postLikes) candidateTypes.add(QueuedActionType.POST_LIKE)
        if (counters.storyViews < safeBudget.storyViews) candidateTypes.add(QueuedActionType.STORY_VIEW)
        if (counters.storyLikes < safeBudget.storyLikes) candidateTypes.add(QueuedActionType.STORY_LIKE)
        if (counters.comments < safeBudget.comments && tasks.commentTemplates.isNotEmpty()) candidateTypes.add(QueuedActionType.COMMENT)
        if (counters.follows < safeBudget.follows) candidateTypes.add(QueuedActionType.FOLLOW)

        val eligibleTypes = candidateTypes.filter { it !in skippedTypes }
        if (eligibleTypes.isEmpty()) {
            val message = if (skippedTypes.isNotEmpty()) {
                "All executable budgets completed (${skippedTypes.joinToString()} skipped due to empty stories/targets)."
            } else {
                "All today's safety-capped budgets are completed."
            }
            return SingleStepReport(
                actionType = "NONE",
                targetId = null,
                targetHandle = null,
                outcome = IgExecutionOutcome.SUCCESS,
                httpStatus = 200,
                message = message
            )
        }

        // Get candidate competitor handles from discovery sources
        val competitorHandles = extractCompetitorHandles(tasks)

        // If next candidate is STORY_VIEW or STORY_LIKE, check if all targets have recent empty stories
        var nextType = eligibleTypes.first()
        if (nextType == QueuedActionType.STORY_VIEW || nextType == QueuedActionType.STORY_LIKE) {
            val allEmpty = competitorHandles.isNotEmpty() && competitorHandles.all { actionStore.hasRecentEmptyStories(it) }
            if (allEmpty) {
                // All targets checked recently and have no active stories:
                // Smoothly cascade to other action types without failing or blocking!
                val nonStoryTypes = eligibleTypes.filter { it != QueuedActionType.STORY_VIEW && it != QueuedActionType.STORY_LIKE }
                if (nonStoryTypes.isNotEmpty()) {
                    nextType = nonStoryTypes.first()
                } else {
                    return SingleStepReport(
                        actionType = "NONE",
                        targetId = null,
                        targetHandle = null,
                        outcome = IgExecutionOutcome.SUCCESS,
                        httpStatus = 200,
                        message = "No active stories currently available across targets (${competitorHandles.joinToString()}). Resuming on next schedule."
                    )
                }
            }
        }

        val report = executeSpecificAction(nextType, session, tasks, actionStore, api)

        // SMART RECOVERY:
        // If the action was STORY_VIEW or STORY_LIKE and it found no active stories,
        // don't get stuck! Gracefully advance to the next action type.
        if ((nextType == QueuedActionType.STORY_VIEW || nextType == QueuedActionType.STORY_LIKE) &&
            report.message.contains("No active stories", ignoreCase = true)
        ) {
            val fallbackReport = executeOneStep(
                session = session,
                tasks = tasks,
                actionStore = actionStore,
                api = api,
                skippedTypes = skippedTypes + setOf(QueuedActionType.STORY_VIEW, QueuedActionType.STORY_LIKE)
            )
            if (fallbackReport.actionType != "NONE") {
                return fallbackReport.copy(
                    message = "No active stories found for targets (smoothly skipped) -> ${fallbackReport.message}"
                )
            }
        }

        return report
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

        val competitorHandles = extractCompetitorHandles(tasks)
        if (competitorHandles.isEmpty()) {
            return SingleStepReport(
                actionType = actionType.name,
                targetId = null,
                targetHandle = null,
                outcome = IgExecutionOutcome.FAILED,
                httpStatus = 0,
                message = "No competitor target configured in discoverySources."
            )
        }

        return when (actionType) {
            QueuedActionType.STORY_VIEW, QueuedActionType.STORY_LIKE -> {
                executeStoryAction(actionType, session, competitorHandles, tasks, actionStore, api)
            }
            QueuedActionType.POST_LIKE -> {
                executePostLikeAction(session, competitorHandles, tasks, actionStore, api)
            }
            QueuedActionType.COMMENT -> {
                executeCommentAction(session, competitorHandles, tasks, actionStore, api)
            }
            QueuedActionType.FOLLOW -> {
                executeFollowAction(session, competitorHandles, tasks, actionStore, api)
            }
        }
    }

    private suspend fun executeStoryAction(
        actionType: QueuedActionType,
        session: IgSession,
        handles: List<String>,
        tasks: DailyTasksResponse,
        actionStore: ActionQueueStore,
        api: GrowthApiService
    ): SingleStepReport {
        var lastCheckedHandle: String? = null
        for (handle in handles) {
            lastCheckedHandle = handle
            // Step 1: Resolve owner ID for this handle
            val ownerId = executor.resolveUserId(session, handle)
                ?: run {
                    val (disc, mediaList) = executor.fetchUserPosts(session, handle, 3)
                    mediaList.firstOrNull { it.ownerUserId.isNotBlank() }?.ownerUserId
                }

            if (ownerId.isNullOrBlank()) {
                continue
            }

            // Step 2: Fetch active stories
            val (storyRes, stories) = executor.fetchUserStories(session, ownerId)
            if (SafetyEngine.isCircuitBreakerOutcome(storyRes.outcome)) {
                reportTelemetry(api, session.dsUserId, actionType.name, storyRes, ownerId)
                return SingleStepReport(
                    actionType = actionType.name,
                    targetId = ownerId,
                    targetHandle = handle,
                    outcome = storyRes.outcome,
                    httpStatus = storyRes.httpStatus,
                    message = "Story query triggered safety breaker: ${storyRes.outcome}",
                    circuitBreakerTriggered = true
                )
            }

            if (stories.isEmpty()) {
                // Mark this handle as having no active stories
                actionStore.markNoStories(handle)
                // Continue to next competitor handle to see if they have stories
                continue
            }

            // Found active stories! Execute view or like
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
            return recordReport(
                actionStore,
                SingleStepReport(
                    actionType = actionType.name,
                    targetId = story.mediaId,
                    targetHandle = handle,
                    outcome = res.outcome,
                    httpStatus = res.httpStatus,
                    message = "${actionType.name} story ${story.mediaId} (@$handle) -> ${res.outcome} (HTTP ${res.httpStatus})",
                    circuitBreakerTriggered = breaker
                )
            )
        }

        // None of the competitor targets had active stories
        val allChecked = handles.joinToString { "@$it" }
        return recordReport(
            actionStore,
            SingleStepReport(
                actionType = actionType.name,
                targetId = null,
                targetHandle = lastCheckedHandle,
                outcome = IgExecutionOutcome.SUCCESS,
                httpStatus = 200,
                message = "No active stories found for $allChecked. Stories may be expired or not yet posted."
            )
        )
    }

    private suspend fun executePostLikeAction(
        session: IgSession,
        handles: List<String>,
        tasks: DailyTasksResponse,
        actionStore: ActionQueueStore,
        api: GrowthApiService
    ): SingleStepReport {
        var lastDiscoveryResult: IgActionResult? = null
        for (handle in handles) {
            val (discoverRes, mediaList) = executor.fetchUserPosts(session, handle)
            lastDiscoveryResult = discoverRes
            if (SafetyEngine.isCircuitBreakerOutcome(discoverRes.outcome)) {
                reportTelemetry(api, session.dsUserId, "POST_LIKE", discoverRes, null)
                return SingleStepReport(
                    actionType = "DISCOVERY",
                    targetId = null,
                    targetHandle = handle,
                    outcome = discoverRes.outcome,
                    httpStatus = discoverRes.httpStatus,
                    message = "Discovery @$handle failed: ${discoverRes.errorMessage ?: discoverRes.outcome}",
                    circuitBreakerTriggered = true
                )
            }
            if (mediaList.isEmpty()) continue

            val candidate = mediaList.firstOrNull { !actionStore.hasInteractedRecently("like:${it.mediaId}") }
                ?: mediaList.first()
            val res = executor.likeMedia(session, candidate.mediaId)
            if (res.outcome == IgExecutionOutcome.SUCCESS) {
                actionStore.markInteracted("like:${candidate.mediaId}")
                actionStore.incrementCounter(QueuedActionType.POST_LIKE)
            }
            val breaker = SafetyEngine.isCircuitBreakerOutcome(res.outcome)
            reportTelemetry(api, session.dsUserId, "POST_LIKE", res, candidate.mediaId)
            return recordReport(
                actionStore,
                SingleStepReport(
                    actionType = "POST_LIKE",
                    targetId = candidate.mediaId,
                    targetHandle = handle,
                    outcome = res.outcome,
                    httpStatus = res.httpStatus,
                    message = "Liked post ${candidate.mediaId} (@$handle) -> ${res.outcome} (HTTP ${res.httpStatus})",
                    circuitBreakerTriggered = breaker
                )
            )
        }

        return SingleStepReport(
            actionType = "DISCOVERY",
            targetId = null,
            targetHandle = handles.firstOrNull(),
            outcome = lastDiscoveryResult?.outcome ?: IgExecutionOutcome.FAILED,
            httpStatus = lastDiscoveryResult?.httpStatus ?: 200,
            message = "Discovery across targets (${handles.joinToString { "@$it" }}) returned 0 posts."
        )
    }

    private suspend fun executeCommentAction(
        session: IgSession,
        handles: List<String>,
        tasks: DailyTasksResponse,
        actionStore: ActionQueueStore,
        api: GrowthApiService
    ): SingleStepReport {
        var lastDiscoveryResult: IgActionResult? = null
        for (handle in handles) {
            val (discoverRes, mediaList) = executor.fetchUserPosts(session, handle)
            lastDiscoveryResult = discoverRes
            if (SafetyEngine.isCircuitBreakerOutcome(discoverRes.outcome)) {
                reportTelemetry(api, session.dsUserId, "COMMENT", discoverRes, null)
                return SingleStepReport(
                    actionType = "DISCOVERY",
                    targetId = null,
                    targetHandle = handle,
                    outcome = discoverRes.outcome,
                    httpStatus = discoverRes.httpStatus,
                    message = "Discovery @$handle failed: ${discoverRes.errorMessage ?: discoverRes.outcome}",
                    circuitBreakerTriggered = true
                )
            }
            if (mediaList.isEmpty()) continue

            val candidate = mediaList.firstOrNull { !actionStore.hasInteractedRecently("comment:${it.mediaId}") }
                ?: mediaList.first()
            val comment = tasks.commentTemplates.randomOrNull() ?: "Love this!"
            val res = executor.commentMedia(session, candidate.mediaId, comment)
            if (res.outcome == IgExecutionOutcome.SUCCESS) {
                actionStore.markInteracted("comment:${candidate.mediaId}")
                actionStore.incrementCounter(QueuedActionType.COMMENT)
            }
            val breaker = SafetyEngine.isCircuitBreakerOutcome(res.outcome)
            reportTelemetry(api, session.dsUserId, "COMMENT", res, candidate.mediaId)
            return recordReport(
                actionStore,
                SingleStepReport(
                    actionType = "COMMENT",
                    targetId = candidate.mediaId,
                    targetHandle = handle,
                    outcome = res.outcome,
                    httpStatus = res.httpStatus,
                    message = "Commented \"$comment\" on ${candidate.mediaId} (@$handle) -> ${res.outcome} (HTTP ${res.httpStatus})",
                    circuitBreakerTriggered = breaker
                )
            )
        }

        return SingleStepReport(
            actionType = "DISCOVERY",
            targetId = null,
            targetHandle = handles.firstOrNull(),
            outcome = lastDiscoveryResult?.outcome ?: IgExecutionOutcome.FAILED,
            httpStatus = lastDiscoveryResult?.httpStatus ?: 200,
            message = "Discovery across targets (${handles.joinToString { "@$it" }}) returned 0 posts for comments."
        )
    }

    private suspend fun executeFollowAction(
        session: IgSession,
        handles: List<String>,
        tasks: DailyTasksResponse,
        actionStore: ActionQueueStore,
        api: GrowthApiService
    ): SingleStepReport {
        for (handle in handles) {
            val ownerId = executor.resolveUserId(session, handle)
                ?: run {
                    val (disc, mediaList) = executor.fetchUserPosts(session, handle, 3)
                    mediaList.firstOrNull { it.ownerUserId.isNotBlank() }?.ownerUserId
                }

            if (ownerId.isNullOrBlank()) continue
            if (actionStore.hasInteractedRecently("follow:$ownerId")) continue

            val res = executor.followUser(session, ownerId)
            if (res.outcome == IgExecutionOutcome.SUCCESS) {
                actionStore.markInteracted("follow:$ownerId")
                actionStore.incrementCounter(QueuedActionType.FOLLOW)
            }
            val breaker = SafetyEngine.isCircuitBreakerOutcome(res.outcome)
            reportTelemetry(api, session.dsUserId, "FOLLOW", res, ownerId)
            return recordReport(
                actionStore,
                SingleStepReport(
                    actionType = "FOLLOW",
                    targetId = ownerId,
                    targetHandle = handle,
                    outcome = res.outcome,
                    httpStatus = res.httpStatus,
                    message = "Follow @$handle ($ownerId) -> ${res.outcome} (HTTP ${res.httpStatus})",
                    circuitBreakerTriggered = breaker
                )
            )
        }

        return recordReport(
            actionStore,
            SingleStepReport(
                actionType = "FOLLOW",
                targetId = null,
                targetHandle = handles.firstOrNull(),
                outcome = IgExecutionOutcome.FAILED,
                httpStatus = 200,
                message = "Could not resolve ownerUserId for targets: ${handles.joinToString { "@$it" }}"
            )
        )
    }

    private fun extractCompetitorHandles(tasks: DailyTasksResponse): List<String> {
        val creatorTargets = tasks.discoverySources
            .filter { it.type != "HASHTAG" && !it.target.startsWith("#") }
            .sortedByDescending { it.priority }
            .map { it.target.trim().removePrefix("@") }
            .filter { it.isNotBlank() }
            .distinct()

        if (creatorTargets.isNotEmpty()) {
            return creatorTargets
        }

        // Fallback: strip # or @ and use available targets
        return tasks.discoverySources
            .sortedByDescending { it.priority }
            .map { it.target.trim().removePrefix("@").removePrefix("#") }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("instagram") }
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
