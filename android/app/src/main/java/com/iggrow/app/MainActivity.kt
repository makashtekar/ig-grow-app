package com.iggrow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.iggrow.app.cloud.ApprovePlanRequest
import com.iggrow.app.cloud.DailyTasksResponse
import com.iggrow.app.cloud.GeneratePlanRequest
import com.iggrow.app.cloud.GrowthApiClient
import com.iggrow.app.cloud.GrowthPlanDto
import com.iggrow.app.ig.GrowthActionRunner
import com.iggrow.app.ig.SingleStepReport
import com.iggrow.app.safety.ActionQueueStore
import com.iggrow.app.safety.ActivityLogEntry
import com.iggrow.app.safety.DailyCounters
import com.iggrow.app.session.IgSession
import com.iggrow.app.session.SessionVault
import com.iggrow.app.ui.ActivityScreen
import com.iggrow.app.ui.ExploreIgScreen
import com.iggrow.app.ui.GoalOnboardingScreen
import com.iggrow.app.ui.GrowthDashboardScreen
import com.iggrow.app.ui.IgWebViewLoginScreen
import com.iggrow.app.ui.PlanReviewScreen
import com.iggrow.app.ui.theme.IgGrowStudioTheme
import com.iggrow.app.ui.theme.StudioColors
import com.iggrow.app.worker.GrowthForegroundService
import com.iggrow.app.worker.GrowthSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FlowRoute {
    LOGIN,
    GOAL_SETUP,
    PLAN_REVIEW,
    STUDIO_TABS
}

private enum class StudioTab(val label: String) {
    DASHBOARD("Dashboard"),
    PLAN("Plan"),
    EXPLORE_IG("Explore IG"),
    ACTIVITY("Activity")
}

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = SessionVault(applicationContext)
        val actionStore = ActionQueueStore(applicationContext)
        val runner = GrowthActionRunner()
        val api = GrowthApiClient.create()

        setContent {
            IgGrowStudioTheme {
                val scope = rememberCoroutineScope()
                var session by remember { mutableStateOf<IgSession?>(vault.getSession()) }
                var draftPlan by remember { mutableStateOf<GrowthPlanDto?>(null) }
                var dailyTasks by remember { mutableStateOf<DailyTasksResponse?>(null) }
                var counters by remember { mutableStateOf<DailyCounters>(actionStore.getTodayCounters()) }
                var activityLogs by remember { mutableStateOf<List<ActivityLogEntry>>(actionStore.getActivityLog()) }
                var isBusy by remember { mutableStateOf(false) }
                var isTestingStep by remember { mutableStateOf(false) }
                var lastReport by remember { mutableStateOf<SingleStepReport?>(null) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var isRunning by remember { mutableStateOf(false) }
                var selectedTab by remember { mutableStateOf(StudioTab.DASHBOARD) }

                var currentRoute by remember {
                    mutableStateOf(
                        when {
                            session == null || !session!!.isComplete() -> FlowRoute.LOGIN
                            vault.getActivePlanId().isNullOrBlank() -> FlowRoute.GOAL_SETUP
                            else -> FlowRoute.STUDIO_TABS
                        }
                    )
                }

                LaunchedEffect(currentRoute, session) {
                    val activeSession = session
                    if (currentRoute == FlowRoute.STUDIO_TABS && activeSession != null) {
                        runCatching {
                            api.getDailyTasks(activeSession.dsUserId, activeSession.dsUserId)
                        }.onSuccess { dailyTasks = it }
                        runCatching {
                            api.getActivePlan(activeSession.dsUserId, activeSession.dsUserId)
                        }.onSuccess { draftPlan = it.plan }
                    }
                }

                LaunchedEffect(selectedTab) {
                    val activeSession = session
                    if (selectedTab == StudioTab.PLAN && draftPlan == null && activeSession != null) {
                        runCatching {
                            api.getActivePlan(activeSession.dsUserId, activeSession.dsUserId)
                        }.onSuccess { draftPlan = it.plan }
                    }
                }

                when (currentRoute) {
                    FlowRoute.LOGIN -> IgWebViewLoginScreen(
                        onSessionCaptured = { captured ->
                            vault.saveSession(captured)
                            session = captured
                            currentRoute = if (vault.getActivePlanId().isNullOrBlank()) {
                                FlowRoute.GOAL_SETUP
                            } else {
                                FlowRoute.STUDIO_TABS
                            }
                        }
                    )

                    FlowRoute.GOAL_SETUP -> {
                        val activeSession = session ?: return@IgGrowStudioTheme
                        GoalOnboardingScreen(
                            session = activeSession,
                            isSubmitting = isBusy,
                            errorMessage = errorMessage,
                            onSuggestTargets = { niche, goal ->
                                api.suggestTargets(activeSession.dsUserId, com.iggrow.app.cloud.SuggestTargetsRequest(niche, goal))
                            },
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
                                        currentRoute = FlowRoute.PLAN_REVIEW
                                    }.onFailure { err ->
                                        errorMessage = err.message ?: "Failed to generate plan"
                                    }
                                    isBusy = false
                                }
                            }
                        )
                    }

                    FlowRoute.PLAN_REVIEW -> {
                        val activeSession = session ?: return@IgGrowStudioTheme
                        val plan = draftPlan ?: return@IgGrowStudioTheme
                        Scaffold(
                            containerColor = StudioColors.BgObsidian,
                            topBar = {
                                TopAppBar(
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = StudioColors.SurfaceCard,
                                        titleContentColor = StudioColors.TextPrimary
                                    ),
                                    title = { Text("Review & Customize Plan", fontWeight = FontWeight.Bold) }
                                )
                            }
                        ) { padding ->
                            PlanReviewScreen(
                                plan = plan,
                                isApproving = isBusy,
                                modifier = Modifier.padding(padding),
                                onApprove = { approvedComments, editedSchedules ->
                                    scope.launch {
                                        isBusy = true
                                        runCatching {
                                            api.approvePlan(
                                                activeSession.dsUserId,
                                                ApprovePlanRequest(
                                                    dsUserId = activeSession.dsUserId,
                                                    planId = plan.planId,
                                                    commentTemplates = approvedComments,
                                                    dailySchedules = editedSchedules
                                                )
                                            )
                                        }.onSuccess { res ->
                                            draftPlan = res.plan
                                            vault.saveActivePlanId(res.plan.planId)
                                            GrowthSyncWorker.schedule(applicationContext)
                                            ContextCompat.startForegroundService(
                                                applicationContext,
                                                Intent(applicationContext, GrowthForegroundService::class.java).apply {
                                                    action = GrowthForegroundService.ACTION_START
                                                }
                                            )
                                            isRunning = true
                                            selectedTab = StudioTab.DASHBOARD
                                            currentRoute = FlowRoute.STUDIO_TABS
                                        }
                                        isBusy = false
                                    }
                                }
                            )
                        }
                    }

                    FlowRoute.STUDIO_TABS -> {
                        val activeSession = session ?: return@IgGrowStudioTheme
                        Scaffold(
                            containerColor = StudioColors.BgObsidian,
                            topBar = {
                                TopAppBar(
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = StudioColors.SurfaceCard,
                                        titleContentColor = StudioColors.TextPrimary
                                    ),
                                    title = {
                                        Column {
                                            Text("IG Grow Studio", fontWeight = FontWeight.Bold)
                                            Text(
                                                selectedTab.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StudioColors.TextSecondary
                                            )
                                        }
                                    }
                                )
                            },
                            bottomBar = {
                                NavigationBar(containerColor = StudioColors.SurfaceCard) {
                                    StudioTab.entries.forEach { tab ->
                                        val icon = when (tab) {
                                            StudioTab.DASHBOARD -> Icons.Default.Dashboard
                                            StudioTab.PLAN -> Icons.Default.CalendarMonth
                                            StudioTab.EXPLORE_IG -> Icons.Default.Public
                                            StudioTab.ACTIVITY -> Icons.Default.History
                                        }
                                        NavigationBarItem(
                                            selected = selectedTab == tab,
                                            onClick = { selectedTab = tab },
                                            icon = { Icon(icon, contentDescription = tab.label) },
                                            label = { Text(tab.label) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = StudioColors.Amber,
                                                selectedTextColor = StudioColors.Amber,
                                                indicatorColor = StudioColors.SurfaceElevated,
                                                unselectedIconColor = StudioColors.TextSecondary,
                                                unselectedTextColor = StudioColors.TextSecondary
                                            )
                                        )
                                    }
                                }
                            }
                        ) { padding ->
                            when (selectedTab) {
                                StudioTab.DASHBOARD -> GrowthDashboardScreen(
                                    session = activeSession,
                                    dailyTasks = dailyTasks,
                                    counters = counters,
                                    isRunning = isRunning,
                                    isTestingStep = isTestingStep,
                                    lastReport = lastReport,
                                    modifier = Modifier.padding(padding),
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
                                    onRunTestStep = {
                                        val currentTasks = dailyTasks ?: return@GrowthDashboardScreen
                                        scope.launch {
                                            isTestingStep = true
                                            val report = withContext(Dispatchers.IO) {
                                                runner.executeOneStep(activeSession, currentTasks, actionStore, api)
                                            }
                                            lastReport = report
                                            counters = actionStore.getTodayCounters()
                                            activityLogs = actionStore.getActivityLog()
                                            if (report.circuitBreakerTriggered) {
                                                isRunning = false
                                            }
                                            runCatching {
                                                api.getDailyTasks(activeSession.dsUserId, activeSession.dsUserId)
                                            }.onSuccess { dailyTasks = it }
                                            isTestingStep = false
                                        }
                                    },
                                    onRunSpecificAction = { actionType ->
                                        val currentTasks = dailyTasks ?: return@GrowthDashboardScreen
                                        scope.launch {
                                            isTestingStep = true
                                            val report = withContext(Dispatchers.IO) {
                                                runner.executeSpecificAction(actionType, activeSession, currentTasks, actionStore, api)
                                            }
                                            lastReport = report
                                            counters = actionStore.getTodayCounters()
                                            activityLogs = actionStore.getActivityLog()
                                            if (report.circuitBreakerTriggered) {
                                                isRunning = false
                                            }
                                            runCatching {
                                                api.getDailyTasks(activeSession.dsUserId, activeSession.dsUserId)
                                            }.onSuccess { dailyTasks = it }
                                            isTestingStep = false
                                        }
                                    },
                                    onRefresh = {
                                        scope.launch {
                                            counters = actionStore.getTodayCounters()
                                            activityLogs = actionStore.getActivityLog()
                                            runCatching {
                                                api.getDailyTasks(activeSession.dsUserId, activeSession.dsUserId)
                                            }.onSuccess { dailyTasks = it }
                                        }
                                    }
                                )

                                StudioTab.PLAN -> {
                                    val activePlan = draftPlan
                                    if (activePlan != null) {
                                        PlanReviewScreen(
                                            plan = activePlan,
                                            isApproving = isBusy,
                                            modifier = Modifier.padding(padding),
                                            onEditGoalSetup = { draftPlan = null },
                                            onApprove = { approvedComments, editedSchedules ->
                                                scope.launch {
                                                    isBusy = true
                                                    runCatching {
                                                        api.approvePlan(
                                                            activeSession.dsUserId,
                                                            ApprovePlanRequest(
                                                                dsUserId = activeSession.dsUserId,
                                                                planId = activePlan.planId,
                                                                commentTemplates = approvedComments,
                                                                dailySchedules = editedSchedules
                                                            )
                                                        )
                                                    }.onSuccess { res ->
                                                        draftPlan = res.plan
                                                        selectedTab = StudioTab.DASHBOARD
                                                    }
                                                    isBusy = false
                                                }
                                            }
                                        )
                                    } else {
                                        GoalOnboardingScreen(
                                            session = activeSession,
                                            isSubmitting = isBusy,
                                            errorMessage = errorMessage,
                                            onSuggestTargets = { niche, goal ->
                                                api.suggestTargets(activeSession.dsUserId, com.iggrow.app.cloud.SuggestTargetsRequest(niche, goal))
                                            },
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
                                                        currentRoute = FlowRoute.PLAN_REVIEW
                                                    }.onFailure { err ->
                                                        errorMessage = err.message ?: "Failed to generate plan"
                                                    }
                                                    isBusy = false
                                                }
                                            }
                                        )
                                    }
                                }

                                StudioTab.EXPLORE_IG -> ExploreIgScreen(
                                    session = activeSession,
                                    competitorHandle = dailyTasks?.discoverySources?.firstOrNull()?.target,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(padding),
                                    onSessionRefreshed = { updated ->
                                        vault.saveSession(updated)
                                        session = updated
                                    }
                                )

                                StudioTab.ACTIVITY -> ActivityScreen(
                                    session = activeSession,
                                    logs = activityLogs,
                                    modifier = Modifier.padding(padding),
                                    onReconnectAccount = {
                                        vault.clearSession()
                                        session = null
                                        currentRoute = FlowRoute.LOGIN
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
