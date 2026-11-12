package com.iggrow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.iggrow.app.cloud.ApprovePlanRequest
import com.iggrow.app.cloud.DailyTasksResponse
import com.iggrow.app.cloud.GeneratePlanRequest
import com.iggrow.app.cloud.GrowthApiClient
import com.iggrow.app.cloud.GrowthPlanDto
import com.iggrow.app.session.IgSession
import com.iggrow.app.session.SessionVault
import com.iggrow.app.ui.GoalOnboardingScreen
import com.iggrow.app.ui.GrowthDashboardScreen
import com.iggrow.app.ui.IgWebViewLoginScreen
import com.iggrow.app.ui.PlanReviewScreen
import com.iggrow.app.worker.GrowthForegroundService
import com.iggrow.app.worker.GrowthSyncWorker
import kotlinx.coroutines.launch

private enum class Screen {
    LOGIN,
    GOAL_SETUP,
    PLAN_REVIEW,
    DASHBOARD
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = SessionVault(applicationContext)
        val api = GrowthApiClient.create()

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var session by remember { mutableStateOf<IgSession?>(vault.getSession()) }
                var draftPlan by remember { mutableStateOf<GrowthPlanDto?>(null) }
                var dailyTasks by remember { mutableStateOf<DailyTasksResponse?>(null) }
                var isBusy by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var isRunning by remember { mutableStateOf(false) }

                var currentScreen by remember {
                    mutableStateOf(
                        when {
                            session == null || !session!!.isComplete() -> Screen.LOGIN
                            vault.getActivePlanId().isNullOrBlank() -> Screen.GOAL_SETUP
                            else -> Screen.DASHBOARD
                        }
                    )
                }

                LaunchedEffect(currentScreen, session) {
                    val activeSession = session
                    if (currentScreen == Screen.DASHBOARD && activeSession != null) {
                        runCatching {
                            api.getDailyTasks(activeSession.dsUserId, activeSession.dsUserId)
                        }.onSuccess { dailyTasks = it }
                    }
                }

                when (currentScreen) {
                    Screen.LOGIN -> IgWebViewLoginScreen(
                        onSessionCaptured = { captured ->
                            vault.saveSession(captured)
                            session = captured
                            currentScreen = Screen.GOAL_SETUP
                        }
                    )

                    Screen.GOAL_SETUP -> {
                        val activeSession = session ?: return@MaterialTheme
                        GoalOnboardingScreen(
                            session = activeSession,
                            isSubmitting = isBusy,
                            errorMessage = errorMessage,
                            onSubmitGoal = { request ->
                                scope.launch {
                                    isBusy = true
                                    errorMessage = null
                                    runCatching {
                                        api.registerAccount(activeSession.dsUserId, request)
                                        api.generatePlan(
                                            activeSession.dsUserId,
                                            GeneratePlanRequest(activeSession.dsUserId)
                                        )
                                    }.onSuccess { response ->
                                        draftPlan = response.plan
                                        currentScreen = Screen.PLAN_REVIEW
                                    }.onFailure { err ->
                                        errorMessage = err.message ?: "Failed to generate plan"
                                    }
                                    isBusy = false
                                }
                            }
                        )
                    }

                    Screen.PLAN_REVIEW -> {
                        val activeSession = session ?: return@MaterialTheme
                        val plan = draftPlan ?: return@MaterialTheme
                        PlanReviewScreen(
                            plan = plan,
                            isApproving = isBusy,
                            onApprove = { approvedComments ->
                                scope.launch {
                                    isBusy = true
                                    runCatching {
                                        api.approvePlan(
                                            activeSession.dsUserId,
                                            ApprovePlanRequest(
                                                dsUserId = activeSession.dsUserId,
                                                planId = plan.planId,
                                                commentTemplates = approvedComments
                                            )
                                        )
                                    }.onSuccess { res ->
                                        vault.saveActivePlanId(res.plan.planId)
                                        GrowthSyncWorker.schedule(applicationContext)
                                        ContextCompat.startForegroundService(
                                            applicationContext,
                                            Intent(applicationContext, GrowthForegroundService::class.java).apply {
                                                action = GrowthForegroundService.ACTION_START
                                            }
                                        )
                                        isRunning = true
                                        currentScreen = Screen.DASHBOARD
                                    }
                                    isBusy = false
                                }
                            }
                        )
                    }

                    Screen.DASHBOARD -> {
                        val activeSession = session ?: return@MaterialTheme
                        GrowthDashboardScreen(
                            session = activeSession,
                            dailyTasks = dailyTasks,
                            isRunning = isRunning,
                            onToggleRunning = {
                                val nextAction = if (isRunning) {
                                    GrowthForegroundService.ACTION_STOP
                                } else {
                                    GrowthForegroundService.ACTION_START
                                }
                                ContextCompat.startForegroundService(
                                    applicationContext,
                                    Intent(applicationContext, GrowthForegroundService::class.java).apply {
                                        action = nextAction
                                    }
                                )
                                isRunning = !isRunning
                            },
                            onRefresh = {
                                scope.launch {
                                    runCatching {
                                        api.getDailyTasks(activeSession.dsUserId, activeSession.dsUserId)
                                    }.onSuccess { dailyTasks = it }
                                }
                            },
                            onReconnectAccount = {
                                vault.clearSession()
                                session = null
                                currentScreen = Screen.LOGIN
                            }
                        )
                    }
                }
            }
        }
    }
}
