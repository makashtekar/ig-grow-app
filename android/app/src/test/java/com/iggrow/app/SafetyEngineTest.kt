package com.iggrow.app

import com.iggrow.app.safety.ActionBudget
import com.iggrow.app.safety.IgExecutionOutcome
import com.iggrow.app.safety.SafetyEngine
import com.iggrow.app.safety.WarmupTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.random.Random

class SafetyEngineTest {

    @Test
    fun clampBudget_enforcesTier1Ceilings() {
        val excessive = ActionBudget(
            storyViews = 500,
            storyLikes = 99,
            postLikes = 200,
            comments = 50,
            follows = 100
        )
        val clamped = SafetyEngine.clampBudget(excessive, WarmupTier.TIER_1_COLD)
        assertEquals(SafetyEngine.tierCeilings.getValue(WarmupTier.TIER_1_COLD), clamped)
    }

    @Test
    fun isWithinActiveHours_respectsWindow() {
        assertTrue(SafetyEngine.isWithinActiveHours(LocalTime.of(9, 0), 9, 22))
        assertTrue(SafetyEngine.isWithinActiveHours(LocalTime.of(21, 59), 9, 22))
        assertFalse(SafetyEngine.isWithinActiveHours(LocalTime.of(22, 0), 9, 22))
        assertFalse(SafetyEngine.isWithinActiveHours(LocalTime.of(3, 30), 9, 22))
    }

    @Test
    fun nextJitterDelayMs_staysWithinHumanBounds() {
        val delay = SafetyEngine.nextJitterDelayMs(Random(42))
        assertTrue(delay in 45_000L until 180_000L)
    }

    @Test
    fun isCircuitBreakerOutcome_detectsCriticalErrors() {
        assertTrue(SafetyEngine.isCircuitBreakerOutcome(IgExecutionOutcome.RATE_LIMITED_429))
        assertTrue(SafetyEngine.isCircuitBreakerOutcome(IgExecutionOutcome.CHALLENGE_REQUIRED))
        assertTrue(SafetyEngine.isCircuitBreakerOutcome(IgExecutionOutcome.LOGIN_REQUIRED))
        assertFalse(SafetyEngine.isCircuitBreakerOutcome(IgExecutionOutcome.SUCCESS))
        assertFalse(SafetyEngine.isCircuitBreakerOutcome(IgExecutionOutcome.FAILED))
    }
}
