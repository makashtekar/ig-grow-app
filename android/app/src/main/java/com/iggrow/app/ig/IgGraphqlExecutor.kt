package com.iggrow.app.ig

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
    val errorMessage: String? = null
)

/**
 * Executes Instagram Web GraphQL mutations over the device's residential IP
 * using the doc_ids and headers documented in docs/03-IG-ENGAGEMENT-API-SPEC.md.
 */
class IgGraphqlExecutor(
    private val client: OkHttpClient = OkHttpClient()
) {
    private val gson = Gson()

    fun followUser(session: IgSession, targetUserId: String): IgActionResult {
        val variables = gson.toJson(
            mapOf(
                "target_user_id" to targetUserId,
                "container_module" to "profile"
            )
        )
        return executeMutation(
            session = session,
            friendlyName = "usePolarisFollowMutation",
            docId = DOC_ID_FOLLOW,
            variablesJson = variables,
            referer = "https://www.instagram.com/"
        )
    }

    fun likeMedia(session: IgSession, mediaId: String, trackingToken: String = ""): IgActionResult {
        val variables = gson.toJson(
            mapOf(
                "input" to mapOf(
                    "actor_id" to session.dsUserId,
                    "client_mutation_id" to UUID.randomUUID().toString(),
                    "container_module" to "feed_timeline",
                    "media_id" to mediaId,
                    "tracking_token" to trackingToken
                )
            )
        )
        return executeMutation(
            session = session,
            friendlyName = "usePolarisLikeMediaXIGLikeMutation",
            docId = DOC_ID_LIKE_MEDIA,
            variablesJson = variables,
            referer = "https://www.instagram.com/"
        )
    }

    fun commentMedia(session: IgSession, mediaId: String, commentText: String): IgActionResult {
        val variables = gson.toJson(
            mapOf(
                "data" to mapOf(
                    "comment_text" to commentText,
                    "media_id" to mediaId
                )
            )
        )
        return executeMutation(
            session = session,
            friendlyName = "PolarisPostCommentInputRevampedMutation",
            docId = DOC_ID_COMMENT_MEDIA,
            variablesJson = variables,
            referer = "https://www.instagram.com/"
        )
    }

    fun viewStory(
        session: IgSession,
        reelId: String,
        reelMediaId: String,
        reelMediaOwnerId: String,
        reelMediaTakenAt: Long,
        viewSeenAt: Long = System.currentTimeMillis() / 1000L
    ): IgActionResult {
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
            session = session,
            friendlyName = "PolarisStoriesV3SeenMutation",
            docId = DOC_ID_VIEW_STORY,
            variablesJson = variables,
            referer = "https://www.instagram.com/stories/"
        )
    }

    fun likeStory(session: IgSession, mediaId: String): IgActionResult {
        val variables = gson.toJson(
            mapOf(
                "input" to mapOf(
                    "actor_id" to session.dsUserId,
                    "client_mutation_id" to UUID.randomUUID().toString(),
                    "media_id" to mediaId
                )
            )
        )
        return executeMutation(
            session = session,
            friendlyName = "usePolarisStoriesV4LikeMutationLikeMutation",
            docId = DOC_ID_LIKE_STORY,
            variablesJson = variables,
            referer = "https://www.instagram.com/stories/"
        )
    }

    private fun executeMutation(
        session: IgSession,
        friendlyName: String,
        docId: String,
        variablesJson: String,
        referer: String
    ): IgActionResult {
        val body = FormBody.Builder()
            .add("av", session.dsUserId)
            .add("__d", "www")
            .add("__user", "0")
            .add("__a", "1")
            .add("fb_dtsg", session.fbDtsg)
            .add("lsd", session.lsd)
            .add("fb_api_caller_class", "RelayModern")
            .add("fb_api_req_friendly_name", friendlyName)
            .add("server_timestamps", "true")
            .add("variables", variablesJson)
            .add("doc_id", docId)
            .build()

        val request = Request.Builder()
            .url(IG_GRAPHQL_URL)
            .post(body)
            .header("accept", "*/*")
            .header("content-type", "application/x-www-form-urlencoded")
            .header("cookie", session.cookieHeader)
            .header("origin", "https://www.instagram.com")
            .header("referer", referer)
            .header("user-agent", session.userAgent)
            .header("x-asbd-id", session.asbdId)
            .header("x-csrftoken", session.csrfToken)
            .header("x-fb-friendly-name", friendlyName)
            .header("x-fb-lsd", session.lsd)
            .header("x-ig-app-id", session.igAppId)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                classifyResponse(response.code, rawBody)
            }
        } catch (ex: Exception) {
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
        return IgActionResult(IgExecutionOutcome.SUCCESS, statusCode)
    }

    companion object {
        private const val IG_GRAPHQL_URL = "https://www.instagram.com/api/graphql"
        const val DOC_ID_FOLLOW = "26508036048874888"
        const val DOC_ID_LIKE_MEDIA = "27182485238052618"
        const val DOC_ID_COMMENT_MEDIA = "29086430320952860"
        const val DOC_ID_VIEW_STORY = "26234228992942885"
        const val DOC_ID_LIKE_STORY = "26938887309082050"
    }
}
