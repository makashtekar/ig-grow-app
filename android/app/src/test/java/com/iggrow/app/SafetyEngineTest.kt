package com.iggrow.app

import com.iggrow.app.ig.IgGraphqlExecutor
import com.iggrow.app.safety.ActionBudget
import com.iggrow.app.safety.DeduplicationTracker
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

    @Test
    fun deduplicationTracker_expiresAfterWindow() {
        val tracker = DeduplicationTracker(windowMs = 1_000L)
        tracker.markInteracted("like:123", nowMs = 10_000L)
        assertTrue(tracker.hasInteractedRecently("like:123", nowMs = 10_500L))
        assertFalse(tracker.hasInteractedRecently("like:123", nowMs = 11_500L))
    }

    @Test
    fun graphqlExecutor_classifies429AndChallengeAndLogin() {
        val executor = IgGraphqlExecutor()
        assertEquals(
            IgExecutionOutcome.RATE_LIMITED_429,
            executor.classifyResponse(429, "").outcome
        )
        assertEquals(
            IgExecutionOutcome.CHALLENGE_REQUIRED,
            executor.classifyResponse(400, """{"message":"challenge_required"}""").outcome
        )
        assertEquals(
            IgExecutionOutcome.LOGIN_REQUIRED,
            executor.classifyResponse(200, """{"require_login":true}""").outcome
        )
        assertEquals(
            IgExecutionOutcome.SUCCESS,
            executor.classifyResponse(200, """{"data":{"xig_media_like":{}}}""").outcome
        )
    }

    @Test
    fun deduplicationTracker_handlesEmptyStoriesCooldown() {
        val tracker = DeduplicationTracker(windowMs = 1_000L)
        assertFalse(tracker.hasRecentEmptyStories("testuser", nowMs = 1_000L, cooldownMs = 500L))
        tracker.markNoStories("testuser", nowMs = 1_000L)
        assertTrue(tracker.hasRecentEmptyStories("testuser", nowMs = 1_200L, cooldownMs = 500L))
        assertFalse(tracker.hasRecentEmptyStories("testuser", nowMs = 1_600L, cooldownMs = 500L))
    }

    @Test
    fun graphqlExecutor_parsesMultipleMediaFormats() {
        val executor = IgGraphqlExecutor()
        // Format 1: GraphQL timeline connection
        val gqlBody = """
            {
              "data": {
                "xdt_api__v1__feed__user_timeline_graphql_connection": {
                  "edges": [
                    {
                      "node": {
                        "pk": "33445566",
                        "user": { "pk": "998877", "username": "creator1" }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        val media1 = executor.parseDiscoveredMedia(gqlBody, "creator1")
        assertEquals(1, media1.size)
        assertEquals("33445566", media1[0].mediaId)
        assertEquals("998877", media1[0].ownerUserId)

        // Format 2: web_profile_info format
        val webBody = """
            {
              "data": {
                "user": {
                  "edge_owner_to_timeline_media": {
                    "edges": [
                      {
                        "node": {
                          "id": "11223344",
                          "owner": { "id": "556677", "username": "creator2" }
                        }
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()
        val media2 = executor.parseDiscoveredMedia(webBody, "creator2")
        assertEquals(1, media2.size)
        assertEquals("11223344", media2[0].mediaId)
        assertEquals("556677", media2[0].ownerUserId)
    }

    @Test
    fun graphqlExecutor_parsesEmptyStoriesWithoutCrashing() {
        val executor = IgGraphqlExecutor()
        val emptyStoriesBody = """
            {
              "data": {
                "xdt_api__v1__feed__reels_media": {
                  "reels_media": []
                }
              }
            }
        """.trimIndent()
        val stories = executor.parseDiscoveredStories(emptyStoriesBody, "12345")
        assertTrue(stories.isEmpty())
    }
}
