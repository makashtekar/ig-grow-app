package com.iggrow.app.safety

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate

enum class QueuedActionType {
    STORY_VIEW,
    STORY_LIKE,
    POST_LIKE,
    COMMENT,
    FOLLOW
}

data class DailyCounters(
    val date: String = LocalDate.now().toString(),
    val storyViews: Int = 0,
    val storyLikes: Int = 0,
    val postLikes: Int = 0,
    val comments: Int = 0,
    val follows: Int = 0
)

data class ActivityLogEntry(
    val actionType: String,
    val targetHandle: String?,
    val targetId: String?,
    val outcome: String,
    val httpStatus: Int,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Tracks 30-day target deduplication and daily executed action counts.
 * Pure in-memory logic is separated in [DeduplicationTracker] for JVM unit testing.
 */
class DeduplicationTracker(
    private val windowMs: Long = 30L * 24 * 60 * 60 * 1000
) {
    private val seen = mutableMapOf<String, Long>()

    fun load(entries: Map<String, Long>) {
        seen.clear()
        seen.putAll(entries)
    }

    fun snapshot(): Map<String, Long> = seen.toMap()

    fun hasInteractedRecently(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val ts = seen[key] ?: return false
        if (nowMs - ts > windowMs) {
            seen.remove(key)
            return false
        }
        return true
    }

    fun markInteracted(key: String, nowMs: Long = System.currentTimeMillis()) {
        seen[key] = nowMs
    }

    fun hasRecentEmptyStories(target: String, nowMs: Long = System.currentTimeMillis(), cooldownMs: Long = 2 * 60 * 60 * 1000L): Boolean {
        val key = "empty_stories:${target.removePrefix("@").lowercase()}"
        val ts = seen[key] ?: return false
        if (nowMs - ts > cooldownMs) {
            seen.remove(key)
            return false
        }
        return true
    }

    fun markNoStories(target: String, nowMs: Long = System.currentTimeMillis()) {
        val key = "empty_stories:${target.removePrefix("@").lowercase()}"
        seen[key] = nowMs
    }
}

class ActionQueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("ig_grow_action_store", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val dedup = DeduplicationTracker()

    init {
        val raw = prefs.getString(KEY_DEDUP_MAP, null)
        if (!raw.isNullOrBlank()) {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            runCatching { gson.fromJson<Map<String, Long>>(raw, type) }
                .getOrNull()
                ?.let { dedup.load(it) }
        }
    }

    fun hasInteractedRecently(targetKey: String): Boolean =
        dedup.hasInteractedRecently(targetKey)

    fun markInteracted(targetKey: String) {
        dedup.markInteracted(targetKey)
        prefs.edit().putString(KEY_DEDUP_MAP, gson.toJson(dedup.snapshot())).apply()
    }

    fun hasRecentEmptyStories(target: String): Boolean =
        dedup.hasRecentEmptyStories(target)

    fun markNoStories(target: String) {
        dedup.markNoStories(target)
        prefs.edit().putString(KEY_DEDUP_MAP, gson.toJson(dedup.snapshot())).apply()
    }

    fun getTodayCounters(): DailyCounters {
        val today = LocalDate.now().toString()
        val raw = prefs.getString(KEY_DAILY_COUNTERS, null)
        val parsed = raw?.let { runCatching { gson.fromJson(it, DailyCounters::class.java) }.getOrNull() }
        return if (parsed != null && parsed.date == today) parsed else DailyCounters(date = today)
    }

    fun incrementCounter(type: QueuedActionType): DailyCounters {
        val current = getTodayCounters()
        val updated = when (type) {
            QueuedActionType.STORY_VIEW -> current.copy(storyViews = current.storyViews + 1)
            QueuedActionType.STORY_LIKE -> current.copy(storyLikes = current.storyLikes + 1)
            QueuedActionType.POST_LIKE -> current.copy(postLikes = current.postLikes + 1)
            QueuedActionType.COMMENT -> current.copy(comments = current.comments + 1)
            QueuedActionType.FOLLOW -> current.copy(follows = current.follows + 1)
        }
        prefs.edit().putString(KEY_DAILY_COUNTERS, gson.toJson(updated)).apply()
        return updated
    }

    fun getActivityLog(): List<ActivityLogEntry> {
        val raw = prefs.getString(KEY_ACTIVITY_LOG, null) ?: return emptyList()
        val type = object : TypeToken<List<ActivityLogEntry>>() {}.type
        return runCatching { gson.fromJson<List<ActivityLogEntry>>(raw, type) }.getOrNull().orEmpty()
    }

    fun appendActivity(entry: ActivityLogEntry) {
        val updated = (listOf(entry) + getActivityLog()).take(50)
        prefs.edit().putString(KEY_ACTIVITY_LOG, gson.toJson(updated)).apply()
    }

    companion object {
        private const val KEY_DEDUP_MAP = "dedup_map_json"
        private const val KEY_DAILY_COUNTERS = "daily_counters_json"
        private const val KEY_ACTIVITY_LOG = "activity_log_json"
    }
}
