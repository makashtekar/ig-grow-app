package com.iggrow.app.ig

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.iggrow.app.safety.IgExecutionOutcome
import com.iggrow.app.session.IgSession
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

data class IgActionResult(
    val outcome: IgExecutionOutcome,
    val httpStatus: Int,
    val errorMessage: String? = null,
    val rawBody: String = ""
)

data class DiscoveredMedia(
    val mediaId: String,
    val ownerUserId: String,
    val ownerUsername: String,
    val caption: String = ""
)

data class DiscoveredStory(
    val reelId: String,
    val mediaId: String,
    val ownerUserId: String,
    val takenAt: Long
)

/**
 * Executes Instagram Web GraphQL queries & mutations over the device's residential IP,
 * mirroring earn-share-mobile-app's InstagramService + InstagramClient request preparation
 * and SessionSyncManager token extraction.
 */
class IgGraphqlExecutor(
    private val client: OkHttpClient = OkHttpClient(),
    private val logger: (String) -> Unit = { msg -> runCatching { Log.d(TAG, msg) } }
) {
    private val gson = Gson()

    /**
     * Refresh fb_dtsg, lsd, and jazoest from https://www.instagram.com/ if missing,
     * matching earn-share-mobile-app/SessionSyncManager.kt.
     */
    fun ensureWebTokens(session: IgSession): IgSession {
        val curDtsg = (session.fbDtsg as String?).orEmpty()
        val curLsd = (session.lsd as String?).orEmpty()
        val curJazoest = (session.jazoest as String?).orEmpty()
        val curClaim = (session.wwwClaim as String?).orEmpty()
        if (curDtsg.isNotBlank() && curLsd.isNotBlank() && curJazoest.isNotBlank()) {
            return session
        }
        logger("Refreshing web tokens (fb_dtsg/lsd/jazoest) from https://www.instagram.com/")
        val req = Request.Builder()
            .url("https://www.instagram.com/")
            .get()
            .header("cookie", session.cookieHeader)
            .header("user-agent", session.userAgent)
            .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("accept-language", "en-US,en;q=0.9")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                logger("Token refresh GET / -> HTTP ${resp.code}, htmlLen=${html.length}")
                if (!resp.isSuccessful || html.isBlank()) return session

                val fbDtsgRaw = "\"fb_dtsg\":\"([^\"]+)\"".toRegex().find(html)?.groupValues?.get(1)
                    ?: "\"DTSGInitialData\",\\[\\],\\{\"token\":\"([^\"]+)\"\\}".toRegex().find(html)?.groupValues?.get(1)
                    ?: session.fbDtsg
                val fbDtsg = fbDtsgRaw.replace("\\\"", "\"").replace("\\/", "/")

                val lsdRaw = "\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"\\}".toRegex().find(html)?.groupValues?.get(1)
                    ?: "\"lsd\":\"([^\"]+)\"".toRegex().find(html)?.groupValues?.get(1)
                    ?: session.lsd

                var jazoest = "jazoest=(\\d+)".toRegex().find(html)?.groupValues?.get(1).orEmpty()
                if (jazoest.isBlank() && fbDtsg.isNotBlank()) {
                    jazoest = computeJazoest(fbDtsg)
                }

                logger("Extracted tokens: fbDtsg=${fbDtsg.isNotBlank()}, lsd=${lsdRaw.isNotBlank()}, jazoest=${jazoest.isNotBlank()}")
                session.copy(
                    fbDtsg = fbDtsg,
                    lsd = lsdRaw,
                    jazoest = jazoest.ifBlank { session.jazoest }
                )
            }
        } catch (ex: Exception) {
            logger("Token refresh failed: ${ex.message}")
            session
        }
    }

    fun followUser(session: IgSession, targetUserId: String): IgActionResult {
        val ready = ensureWebTokens(session)
        val variables = gson.toJson(
            mapOf(
                "target_user_id" to targetUserId,
                "container_module" to "profile"
            )
        )
        return executeMutation(
            session = ready,
            friendlyName = "usePolarisFollowMutation",
            docId = DOC_ID_FOLLOW,
            variablesJson = variables,
            referer = "https://www.instagram.com/"
        )
    }

    fun likeMedia(session: IgSession, mediaId: String, trackingToken: String = ""): IgActionResult {
        val ready = ensureWebTokens(session)
        val cleanMediaId = mediaId.split("_").first()
        val variables = gson.toJson(
            mapOf(
                "input" to mapOf(
                    "actor_id" to ready.dsUserId,
                    "client_mutation_id" to UUID.randomUUID().toString(),
                    "container_module" to "feed_timeline",
                    "media_id" to cleanMediaId,
                    "tracking_token" to trackingToken
                )
            )
        )
        val res = executeMutation(
            session = ready,
            friendlyName = "usePolarisLikeMediaXIGLikeMutation",
            docId = DOC_ID_LIKE_MEDIA,
            variablesJson = variables,
            referer = "https://www.instagram.com/"
        )
        if (res.outcome == IgExecutionOutcome.SUCCESS) return res

        // REST fallback: POST https://www.instagram.com/api/v1/web/likes/<mediaId>/like/
        logger("GraphQL like failed; trying REST endpoint https://www.instagram.com/api/v1/web/likes/$cleanMediaId/like/")
        return executeRestLike(ready, cleanMediaId)
    }

    private fun executeRestLike(session: IgSession, mediaId: String): IgActionResult {
        val url = "https://www.instagram.com/api/v1/web/likes/$mediaId/like/"
        val req = Request.Builder()
            .url(url)
            .post(FormBody.Builder().build())
            .header("cookie", session.cookieHeader)
            .header("user-agent", session.userAgent)
            .header("x-csrftoken", session.csrfToken)
            .header("x-ig-app-id", (session.igAppId as String?).orEmpty().ifBlank { "936619743392459" })
            .header("x-asbd-id", (session.asbdId as String?).orEmpty().ifBlank { "359341" })
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", "https://www.instagram.com/")
            .header("origin", "https://www.instagram.com")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                logger("<- HTTP ${resp.code} | rest_like | body=${body.take(200)}")
                classifyResponse(resp.code, body)
            }
        } catch (ex: Exception) {
            logger("!! Exception | rest_like | err=${ex.message}")
            IgActionResult(IgExecutionOutcome.FAILED, 0, ex.message)
        }
    }

    fun commentMedia(session: IgSession, mediaId: String, commentText: String): IgActionResult {
        val ready = ensureWebTokens(session)
        val cleanMediaId = mediaId.split("_").first()
        val variables = gson.toJson(
            mapOf(
                "data" to mapOf(
                    "client_mutation_id" to UUID.randomUUID().toString(),
                    "comment_text" to commentText,
                    "media_id" to cleanMediaId
                )
            )
        )
        val res = executeMutation(
            session = ready,
            friendlyName = "PolarisPostCommentInputRevampedMutation",
            docId = DOC_ID_COMMENT_MEDIA,
            variablesJson = variables,
            referer = "https://www.instagram.com/"
        )
        if (res.outcome == IgExecutionOutcome.SUCCESS) return res

        // REST fallback: POST https://www.instagram.com/api/v1/web/comments/<mediaId>/add/
        logger("GraphQL comment failed; trying REST endpoint https://www.instagram.com/api/v1/web/comments/$cleanMediaId/add/")
        return executeRestComment(ready, cleanMediaId, commentText)
    }

    private fun executeRestComment(session: IgSession, mediaId: String, commentText: String): IgActionResult {
        val url = "https://www.instagram.com/api/v1/web/comments/$mediaId/add/"
        val form = FormBody.Builder()
            .add("comment_text", commentText)
            .build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("cookie", session.cookieHeader)
            .header("user-agent", session.userAgent)
            .header("x-csrftoken", session.csrfToken)
            .header("x-ig-app-id", (session.igAppId as String?).orEmpty().ifBlank { "936619743392459" })
            .header("x-asbd-id", (session.asbdId as String?).orEmpty().ifBlank { "359341" })
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", "https://www.instagram.com/")
            .header("origin", "https://www.instagram.com")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                logger("<- HTTP ${resp.code} | rest_comment | body=${body.take(200)}")
                classifyResponse(resp.code, body)
            }
        } catch (ex: Exception) {
            logger("!! Exception | rest_comment | err=${ex.message}")
            IgActionResult(IgExecutionOutcome.FAILED, 0, ex.message)
        }
    }

    fun viewStory(
        session: IgSession,
        reelId: String,
        reelMediaId: String,
        reelMediaOwnerId: String,
        reelMediaTakenAt: Long,
        viewSeenAt: Long = System.currentTimeMillis() / 1000L
    ): IgActionResult {
        val ready = ensureWebTokens(session)
        val variables = gson.toJson(
            mapOf(
                "reelId" to reelId,
                "reelMediaId" to reelMediaId,
                "reelMediaOwnerId" to reelMediaOwnerId,
                "reelMediaTakenAt" to reelMediaTakenAt,
                "viewSeenAt" to viewSeenAt
            )
        )
        return executeMutation(
            session = ready,
            friendlyName = "PolarisStoriesV3SeenMutation",
            docId = DOC_ID_VIEW_STORY,
            variablesJson = variables,
            referer = "https://www.instagram.com/stories/"
        )
    }

    fun likeStory(session: IgSession, mediaId: String): IgActionResult {
        val ready = ensureWebTokens(session)
        val variables = gson.toJson(
            mapOf(
                "input" to mapOf(
                    "actor_id" to ready.dsUserId,
                    "client_mutation_id" to UUID.randomUUID().toString(),
                    "media_id" to mediaId
                )
            )
        )
        return executeMutation(
            session = ready,
            friendlyName = "usePolarisStoriesV4LikeMutationLikeMutation",
            docId = DOC_ID_LIKE_STORY,
            variablesJson = variables,
            referer = "https://www.instagram.com/stories/"
        )
    }

    fun fetchUserPosts(session: IgSession, username: String, count: Int = 12): Pair<IgActionResult, List<DiscoveredMedia>> {
        val ready = ensureWebTokens(session)
        val cleanName = username.trim().removePrefix("@")
        val variables = gson.toJson(
            mapOf(
                "data" to mapOf(
                    "count" to count,
                    "include_reel_media_seen_timestamp" to true,
                    "include_relationship_info" to true,
                    "latest_besties_reel_media" to true,
                    "latest_reel_media" to true
                ),
                "username" to cleanName,
                "__relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider" to true,
                "__relay_internal__pv__PolarisShortDramaEnabledrelayprovider" to true,
                "__relay_internal__pv__PolarisThreadsStyleCommentsEnabledrelayprovider" to false,
                "__relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider" to false
            )
        )
        val result = executeMutation(
            session = ready,
            friendlyName = "PolarisProfilePostsQuery",
            docId = DOC_ID_PROFILE_POSTS,
            variablesJson = variables,
            referer = "https://www.instagram.com/$cleanName/"
        )
        if (result.outcome == IgExecutionOutcome.SUCCESS) {
            val media = parseDiscoveredMedia(result.rawBody, cleanName)
            if (media.isNotEmpty()) return result to media
        }

        // Secondary fallback to web_profile_info (mirrors earn-share-mobile-app InstagramRepository)
        logger("GraphQL returned 0 posts or non-success for @$cleanName; falling back to web_profile_info")
        val (fallbackResult, fallbackMedia) = fetchUserPostsViaWebProfile(ready, cleanName)
        if (fallbackMedia.isNotEmpty()) {
            return fallbackResult to fallbackMedia
        }
        return if (result.outcome != IgExecutionOutcome.SUCCESS) result to emptyList() else fallbackResult to emptyList()
    }

    fun fetchUserPostsViaWebProfile(session: IgSession, username: String): Pair<IgActionResult, List<DiscoveredMedia>> {
        val cleanName = username.trim().removePrefix("@")
        val ready = ensureWebTokens(session)
        val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$cleanName"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("cookie", ready.cookieHeader)
            .header("user-agent", ready.userAgent)
            .header("x-ig-app-id", (ready.igAppId as String?).orEmpty().ifBlank { "936619743392459" })
            .header("x-asbd-id", (ready.asbdId as String?).orEmpty().ifBlank { "359341" })
            .header("x-csrftoken", ready.csrfToken)
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", "https://www.instagram.com/$cleanName/")
            .header("accept", "*/*")
            .header("accept-language", "en-US,en;q=0.9")
            .build()

        return try {
            client.newCall(req).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                logger("<- HTTP ${response.code} | web_profile_info | body=${rawBody.take(200)}")
                val classification = classifyResponse(response.code, rawBody)
                if (classification.outcome != IgExecutionOutcome.SUCCESS) {
                    return classification to emptyList()
                }
                val media = parseDiscoveredMedia(rawBody, cleanName)
                classification to media
            }
        } catch (ex: Exception) {
            logger("!! Exception | web_profile_info | err=${ex.message}")
            IgActionResult(IgExecutionOutcome.FAILED, 0, ex.message) to emptyList()
        }
    }

    fun resolveUserId(session: IgSession, username: String): String? {
        val cleanName = username.trim().removePrefix("@")
        if (cleanName.all { it.isDigit() } && cleanName.isNotEmpty()) return cleanName
        val ready = ensureWebTokens(session)

        // Step 1: web_profile_info
        val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$cleanName"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("cookie", ready.cookieHeader)
            .header("user-agent", ready.userAgent)
            .header("x-ig-app-id", (ready.igAppId as String?).orEmpty().ifBlank { "936619743392459" })
            .header("x-asbd-id", (ready.asbdId as String?).orEmpty().ifBlank { "359341" })
            .header("x-csrftoken", ready.csrfToken)
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", "https://www.instagram.com/$cleanName/")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                val user = root?.getAsJsonObject("data")?.getAsJsonObject("user")
                    ?: root?.getAsJsonObject("user")
                val uid = user?.get("id")?.asString ?: user?.get("pk")?.asString
                if (!uid.isNullOrBlank()) return uid
            }
        } catch (_: Exception) {}

        // Step 2: Fallback regex on HTML
        try {
            val htmlReq = Request.Builder()
                .url("https://www.instagram.com/$cleanName/")
                .get()
                .header("cookie", ready.cookieHeader)
                .header("user-agent", ready.userAgent)
                .build()
            client.newCall(htmlReq).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val match = "\"user_id\":\"(\\d+)\"".toRegex().find(html)?.groupValues?.get(1)
                    ?: "\"profilePage_(\\d+)\"".toRegex().find(html)?.groupValues?.get(1)
                if (!match.isNullOrBlank()) return match
            }
        } catch (_: Exception) {}

        return null
    }

    fun fetchUserStories(session: IgSession, reelId: String): Pair<IgActionResult, List<DiscoveredStory>> {
        val ready = ensureWebTokens(session)
        val variables = gson.toJson(
            mapOf(
                "reel_ids_arr" to listOf(reelId),
                "__relay_internal__pv__PolarisCommunityNoteStoriesLabelEnabledrelayprovider" to true
            )
        )
        val result = executeMutation(
            session = ready,
            friendlyName = "PolarisStoriesV3ReelPageStandaloneQuery",
            docId = DOC_ID_STORY_REEL,
            variablesJson = variables,
            referer = "https://www.instagram.com/"
        )
        if (result.outcome != IgExecutionOutcome.SUCCESS) return result to emptyList()
        val stories = parseDiscoveredStories(result.rawBody, reelId)
        return result to stories
    }

    internal fun parseDiscoveredMedia(rawBody: String, fallbackUsername: String): List<DiscoveredMedia> {
        val root = runCatching { gson.fromJson(rawBody, JsonObject::class.java) }.getOrNull() ?: return emptyList()
        val dataObj = root.getAsJsonObject("data") ?: root

        // Search in possible connection locations
        val connection = dataObj.getAsJsonObject("xdt_api__v1__feed__user_timeline_graphql_connection")
            ?: dataObj.getAsJsonObject("user")?.getAsJsonObject("edge_owner_to_timeline_media")
            ?: root.getAsJsonObject("user")?.getAsJsonObject("edge_owner_to_timeline_media")

        val edges = connection?.getAsJsonArray("edges")
            ?: dataObj.getAsJsonArray("items")
            ?: root.getAsJsonArray("items")
            ?: return emptyList()

        return edges.mapNotNull { el ->
            val obj = el.asJsonObject
            val node = obj.getAsJsonObject("node") ?: obj
            val mediaId = node.get("pk")?.asString ?: node.get("id")?.asString ?: return@mapNotNull null
            val user = node.getAsJsonObject("user") ?: node.getAsJsonObject("owner")
            val ownerId = user?.get("pk")?.asString ?: user?.get("id")?.asString ?: user?.get("fbid")?.asString ?: ""
            val ownerName = user?.get("username")?.asString ?: fallbackUsername
            val captionText = node.getAsJsonObject("caption")?.get("text")?.asString
                ?: node.getAsJsonObject("edge_media_to_caption")
                    ?.getAsJsonArray("edges")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("node")?.get("text")?.asString
                ?: ""
            DiscoveredMedia(
                mediaId = mediaId,
                ownerUserId = ownerId,
                ownerUsername = ownerName,
                caption = captionText
            )
        }
    }

    internal fun parseDiscoveredStories(rawBody: String, fallbackReelId: String): List<DiscoveredStory> {
        val root = runCatching { gson.fromJson(rawBody, JsonObject::class.java) }.getOrNull() ?: return emptyList()
        val dataObj = root.getAsJsonObject("data") ?: root
        val reels = dataObj.getAsJsonObject("xdt_api__v1__feed__reels_media")
            ?.getAsJsonArray("reels_media")
            ?: root.getAsJsonObject("xdt_api__v1__feed__reels_media")?.getAsJsonArray("reels_media")
            ?: return emptyList()
        val out = mutableListOf<DiscoveredStory>()
        for (reelEl in reels) {
            val reelObj = reelEl.asJsonObject
            val reelId = reelObj.get("id")?.asString ?: fallbackReelId
            val items = reelObj.getAsJsonArray("items") ?: continue
            for (itemEl in items) {
                val item = itemEl.asJsonObject
                val mediaId = item.get("pk")?.asString ?: item.get("id")?.asString ?: continue
                val takenAt = item.get("taken_at")?.asLong ?: (System.currentTimeMillis() / 1000L)
                out.add(
                    DiscoveredStory(
                        reelId = reelId,
                        mediaId = mediaId,
                        ownerUserId = reelId,
                        takenAt = takenAt
                    )
                )
            }
        }
        return out
    }

    private fun executeMutation(
        session: IgSession,
        friendlyName: String,
        docId: String,
        variablesJson: String,
        referer: String
    ): IgActionResult {
        val fbDtsg = (session.fbDtsg as String?).orEmpty()
        val lsd = (session.lsd as String?).orEmpty()
        val jazoest = (session.jazoest as String?).orEmpty().ifBlank {
            if (fbDtsg.isNotBlank()) computeJazoest(fbDtsg) else ""
        }
        val wwwClaim = (session.wwwClaim as String?).orEmpty()
        logger("-> POST $IG_GRAPHQL_URL | op=$friendlyName | doc_id=$docId | hasDtsg=${fbDtsg.isNotBlank()} | hasLsd=${lsd.isNotBlank()} | hasJazoest=${jazoest.isNotBlank()}")

        val bodyBuilder = FormBody.Builder()
            .add("av", session.dsUserId)
            .add("__a", "1")
            .add("__req", "6")
            .add("__comet_req", "7")
            .add("__d", "www")
            .add("__user", "0")
            .add("fb_dtsg", fbDtsg)
            .add("jazoest", jazoest)
            .add("lsd", lsd)
            .add("fb_api_caller_class", "RelayModern")
            .add("fb_api_req_friendly_name", friendlyName)
            .add("server_timestamps", "true")
            .add("doc_id", docId)
            .add("variables", variablesJson)

        val requestBuilder = Request.Builder()
            .url(IG_GRAPHQL_URL)
            .post(bodyBuilder.build())
            .header("accept", "*/*")
            .header("accept-language", "en-US,en;q=0.9")
            .header("content-type", "application/x-www-form-urlencoded")
            .header("cookie", session.cookieHeader)
            .header("origin", "https://www.instagram.com")
            .header("referer", referer)
            .header("user-agent", session.userAgent)
            .header("x-asbd-id", (session.asbdId as String?).orEmpty().ifBlank { "359341" })
            .header("x-csrftoken", session.csrfToken)
            .header("x-fb-friendly-name", friendlyName)
            .header("x-fb-lsd", lsd)
            .header("x-ig-app-id", (session.igAppId as String?).orEmpty().ifBlank { "936619743392459" })
            .header("x-requested-with", "XMLHttpRequest")
            .header("sec-fetch-site", "same-origin")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-dest", "empty")
            .header("priority", "u=1, i")

        if (wwwClaim.isNotBlank() && wwwClaim != "0") {
            requestBuilder.header("x-ig-www-claim", wwwClaim)
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                logger("<- HTTP ${response.code} | op=$friendlyName | body=${rawBody.take(300)}")
                classifyResponse(response.code, rawBody)
            }
        } catch (ex: Exception) {
            logger("!! Exception | op=$friendlyName | err=${ex.message}")
            IgActionResult(
                outcome = IgExecutionOutcome.FAILED,
                httpStatus = 0,
                errorMessage = ex.message
            )
        }
    }

    internal fun classifyResponse(statusCode: Int, rawBody: String): IgActionResult {
        if (statusCode == 429) {
            return IgActionResult(IgExecutionOutcome.RATE_LIMITED_429, statusCode, "HTTP 429")
        }
        if (statusCode == 401 || rawBody.contains("/accounts/login")) {
            return IgActionResult(IgExecutionOutcome.LOGIN_REQUIRED, statusCode, "Login required")
        }
        if (rawBody.contains("challenge_required") || rawBody.contains("checkpoint_required")) {
            return IgActionResult(IgExecutionOutcome.CHALLENGE_REQUIRED, statusCode, "Challenge required")
        }
        if (statusCode !in 200..299) {
            return IgActionResult(IgExecutionOutcome.FAILED, statusCode, "HTTP $statusCode")
        }

        val json = runCatching { gson.fromJson(rawBody, JsonObject::class.java) }.getOrNull()
        if (json != null) {
            if (json.get("require_login")?.asBoolean == true) {
                return IgActionResult(IgExecutionOutcome.LOGIN_REQUIRED, statusCode, "require_login=true")
            }
            if (json.has("errors")) {
                val errText = json.get("errors").toString()
                if (errText.contains("challenge", ignoreCase = true)) {
                    return IgActionResult(IgExecutionOutcome.CHALLENGE_REQUIRED, statusCode, errText)
                }
                if (errText.contains("rate", ignoreCase = true) || errText.contains("limit", ignoreCase = true)) {
                    return IgActionResult(IgExecutionOutcome.RATE_LIMITED_429, statusCode, errText)
                }
                return IgActionResult(IgExecutionOutcome.FAILED, statusCode, errText)
            }
        }
        return IgActionResult(IgExecutionOutcome.SUCCESS, statusCode, rawBody = rawBody)
    }

    private fun computeJazoest(fbDtsg: String): String {
        var sum = 0
        for (c in fbDtsg) sum += c.code
        return "2$sum"
    }

    companion object {
        private const val TAG = "IG_GROW_API"
        private const val IG_GRAPHQL_URL = "https://www.instagram.com/api/graphql"
        const val DOC_ID_FOLLOW = "26508036048874888"
        const val DOC_ID_LIKE_MEDIA = "27182485238052618"
        const val DOC_ID_COMMENT_MEDIA = "29086430320952860"
        const val DOC_ID_VIEW_STORY = "26234228992942885"
        const val DOC_ID_LIKE_STORY = "26938887309082050"
        const val DOC_ID_PROFILE_POSTS = "28834890909485062"
        const val DOC_ID_STORY_REEL = "29184890191114309"
    }
}
