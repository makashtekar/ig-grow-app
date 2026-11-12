package com.iggrow.app.cloud

import com.iggrow.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

data class RegisterAccountRequest(
    val dsUserId: String,
    val username: String,
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val postsCount: Int = 0,
    val accountAgeDays: Int = 0,
    val goal: String,
    val niche: String,
    val competitorHandles: List<String>,
    val targetHashtags: List<String>,
    val commentTone: String = "friendly, authentic, concise",
    val activeHoursStart: Int = 9,
    val activeHoursEnd: Int = 22,
    val timezone: String = "UTC"
)

data class AccountDto(
    val dsUserId: String,
    val username: String,
    val tier: String,
    val status: String,
    val activePlanId: String?
)

data class RegisterAccountResponse(val account: AccountDto)

data class GeneratePlanRequest(val dsUserId: String)

data class ActionBudgetDto(
    val storyViews: Int,
    val storyLikes: Int,
    val postLikes: Int,
    val comments: Int,
    val follows: Int
)

data class DiscoverySourceDto(
    val type: String,
    val target: String,
    val priority: Int
)

data class DayPlanDto(
    val dayNumber: Int,
    val focus: String,
    val discoverySources: List<DiscoverySourceDto>,
    val actionBudgets: ActionBudgetDto
)

data class GrowthPlanDto(
    val planId: String,
    val dsUserId: String,
    val status: String,
    val strategySummary: String,
    val dailySchedules: List<DayPlanDto>,
    val commentTemplates: List<String>
)

data class PlanResponse(val plan: GrowthPlanDto)

data class ApprovePlanRequest(
    val dsUserId: String,
    val planId: String,
    val commentTemplates: List<String>? = null,
    val dailySchedules: List<DayPlanDto>? = null
)

data class ActiveHoursDto(val start: Int, val end: Int, val timezone: String)

data class DailyTasksResponse(
    val dsUserId: String,
    val accountStatus: String,
    val tier: String,
    val pausedUntil: String?,
    val activeHours: ActiveHoursDto,
    val dayNumber: Int,
    val focus: String,
    val discoverySources: List<DiscoverySourceDto>,
    val actionBudgets: ActionBudgetDto,
    val commentTemplates: List<String>
)

data class TelemetryEventDto(
    val actionType: String,
    val outcome: String,
    val targetId: String? = null,
    val httpStatus: Int? = null,
    val errorMessage: String? = null,
    val executedAt: String
)

data class TelemetryBatchRequest(
    val dsUserId: String,
    val followersCount: Int? = null,
    val events: List<TelemetryEventDto>
)

data class TelemetryBatchResponse(
    val accepted: Int,
    val accountStatus: String,
    val tier: String,
    val pausedUntil: String?,
    val safetyReason: String?
)

interface GrowthApiService {
    @POST("v1/account/register")
    suspend fun registerAccount(
        @Header("x-dev-uid") devUid: String,
        @Body body: RegisterAccountRequest
    ): RegisterAccountResponse

    @POST("v1/plan/generate")
    suspend fun generatePlan(
        @Header("x-dev-uid") devUid: String,
        @Body body: GeneratePlanRequest
    ): PlanResponse

    @POST("v1/plan/approve")
    suspend fun approvePlan(
        @Header("x-dev-uid") devUid: String,
        @Body body: ApprovePlanRequest
    ): PlanResponse

    @GET("v1/tasks/daily")
    suspend fun getDailyTasks(
        @Header("x-dev-uid") devUid: String,
        @Query("dsUserId") dsUserId: String
    ): DailyTasksResponse

    @POST("v1/telemetry/batch")
    suspend fun sendTelemetry(
        @Header("x-dev-uid") devUid: String,
        @Body body: TelemetryBatchRequest
    ): TelemetryBatchResponse
}

object GrowthApiClient {
    fun create(baseUrl: String = BuildConfig.API_BASE_URL): GrowthApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GrowthApiService::class.java)
    }
}
