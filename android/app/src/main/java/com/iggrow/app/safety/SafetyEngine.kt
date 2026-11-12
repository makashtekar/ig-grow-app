package com.iggrow.app.safety

import java.time.LocalTime
import kotlin.random.Random

enum class WarmupTier {
    TIER_1_COLD,
    TIER_2_WARMING,
    TIER_3_ESTABLISHED
}

enum class IgExecutionOutcome {
    SUCCESS,
    RATE_LIMITED_429,
    CHALLENGE_REQUIRED,
    LOGIN_REQUIRED,
    FAILED
}

data class ActionBudget(
    val storyViews: Int,
    val storyLikes: Int,
    val postLikes: Int,
    val comments: Int,
    val follows: Int
)

/**
 * On-device Strict Safety Engine.
 * Enforces hard Tier 1-3 daily ceilings, active hours, randomized jitter, and circuit breakers.
 */
object SafetyEngine {
    val tierCeilings: Map<WarmupTier, ActionBudget> = mapOf(
        WarmupTier.TIER_1_COLD to ActionBudget(
            storyViews = 20,
            storyLikes = 5,
            postLikes = 15,
            comments = 3,
            follows = 5
        ),
        WarmupTier.TIER_2_WARMING to ActionBudget(
            storyViews = 40,
            storyLikes = 12,
            postLikes = 35,
            comments = 8,
            follows = 15
        ),
        WarmupTier.TIER_3_ESTABLISHED to ActionBudget(
            storyViews = 70,
            storyLikes = 20,
            postLikes = 60,
            comments = 15,
            follows = 25
        )
    )

    fun clampBudget(budget: ActionBudget, tier: WarmupTier): ActionBudget {
        val ceiling = tierCeilings.getValue(tier)
        return ActionBudget(
            storyViews = budget.storyViews.coerceIn(0, ceiling.storyViews),
            storyLikes = budget.storyLikes.coerceIn(0, ceiling.storyLikes),
            postLikes = budget.postLikes.coerceIn(0, ceiling.postLikes),
            comments = budget.comments.coerceIn(0, ceiling.comments),
            follows = budget.follows.coerceIn(0, ceiling.follows)
        )
    }

    fun isWithinActiveHours(
        now: LocalTime,
        startHour: Int = 9,
        endHour: Int = 22
    ): Boolean {
        val hour = now.hour
        return if (startHour <= endHour) {
            hour in startHour until endHour
        } else {
            hour >= startHour || hour < endHour
        }
    }

    /**
     * Randomized human-like delay between actions (45s - 180s).
     */
    fun nextJitterDelayMs(random: Random = Random.Default): Long =
        random.nextLong(45_000L, 180_000L)

    fun isCircuitBreakerOutcome(outcome: IgExecutionOutcome): Boolean =
        outcome == IgExecutionOutcome.RATE_LIMITED_429 ||
            outcome == IgExecutionOutcome.CHALLENGE_REQUIRED ||
            outcome == IgExecutionOutcome.LOGIN_REQUIRED
}
