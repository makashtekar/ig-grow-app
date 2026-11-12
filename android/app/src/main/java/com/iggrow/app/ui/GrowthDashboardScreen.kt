package com.iggrow.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iggrow.app.cloud.DailyTasksResponse
import com.iggrow.app.ig.SingleStepReport
import com.iggrow.app.safety.DailyCounters
import com.iggrow.app.safety.IgExecutionOutcome
import com.iggrow.app.safety.QueuedActionType
import com.iggrow.app.session.IgSession
import com.iggrow.app.ui.theme.GradientButton
import com.iggrow.app.ui.theme.StatusPill
import com.iggrow.app.ui.theme.StudioCard
import com.iggrow.app.ui.theme.StudioColors

@Composable
fun GrowthDashboardScreen(
    session: IgSession,
    dailyTasks: DailyTasksResponse?,
    counters: DailyCounters,
    isRunning: Boolean,
    isTestingStep: Boolean,
    lastReport: SingleStepReport?,
    onToggleRunning: () -> Unit,
    onRunTestStep: () -> Unit,
    onRunSpecificAction: (QueuedActionType) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = dailyTasks?.accountStatus ?: if (isRunning) "ACTIVE" else "PAUSED"
    val statusColor = when {
        statusText.startsWith("PAUSED") -> StudioColors.ErrorRose
        statusText == "WARMING_UP" -> StudioColors.WarningAmber
        else -> StudioColors.SuccessEmerald
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StudioColors.BgObsidian)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Gradient Hero Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(StudioColors.BrandGradient)
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "@${session.username}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    StatusPill(
                        text = if (isRunning) "● AUTOPILOT ON" else "○ STANDBY",
                        color = Color.White
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(text = statusText, color = statusColor)
                    StatusPill(
                        text = dailyTasks?.tier ?: "TIER_1_COLD",
                        color = Color.White
                    )
                }

                if (dailyTasks != null) {
                    Text(
                        text = "Day ${dailyTasks.dayNumber} • ${dailyTasks.focus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        }

        // Autopilot Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GradientButton(
                text = if (isRunning) "Pause Autopilot" else "Start Autopilot",
                modifier = Modifier.weight(1.4f),
                onClick = onToggleRunning
            )
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("Sync Plan")
            }
        }

        // Today's Progress with per-action Run triggers
        if (dailyTasks != null) {
            StudioCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Today's Progress",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${dailyTasks.activeHours.start}:00–${dailyTasks.activeHours.end}:00",
                        style = MaterialTheme.typography.labelSmall,
                        color = StudioColors.TextSecondary
                    )
                }

                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ActionProgressRow(
                        icon = Icons.Default.Visibility,
                        title = "Story Views",
                        executed = counters.storyViews,
                        budget = dailyTasks.actionBudgets.storyViews,
                        enabled = !isTestingStep,
                        onRun = { onRunSpecificAction(QueuedActionType.STORY_VIEW) }
                    )
                    ActionProgressRow(
                        icon = Icons.Default.Favorite,
                        title = "Story Likes",
                        executed = counters.storyLikes,
                        budget = dailyTasks.actionBudgets.storyLikes,
                        enabled = !isTestingStep,
                        onRun = { onRunSpecificAction(QueuedActionType.STORY_LIKE) }
                    )
                    ActionProgressRow(
                        icon = Icons.Default.Favorite,
                        title = "Post Likes",
                        executed = counters.postLikes,
                        budget = dailyTasks.actionBudgets.postLikes,
                        enabled = !isTestingStep,
                        onRun = { onRunSpecificAction(QueuedActionType.POST_LIKE) }
                    )
                    ActionProgressRow(
                        icon = Icons.Default.Comment,
                        title = "Comments",
                        executed = counters.comments,
                        budget = dailyTasks.actionBudgets.comments,
                        enabled = !isTestingStep,
                        onRun = { onRunSpecificAction(QueuedActionType.COMMENT) }
                    )
                    ActionProgressRow(
                        icon = Icons.Default.PersonAdd,
                        title = "Follows",
                        executed = counters.follows,
                        budget = dailyTasks.actionBudgets.follows,
                        enabled = !isTestingStep,
                        onRun = { onRunSpecificAction(QueuedActionType.FOLLOW) }
                    )
                }
            }
        }

        // Quick Auto Step
        OutlinedButton(
            onClick = onRunTestStep,
            enabled = !isTestingStep && dailyTasks != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isTestingStep) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Run Next Auto Action", modifier = Modifier.padding(start = 6.dp))
            }
        }

        // Last Execution Banner
        if (lastReport != null) {
            val reportColor = when (lastReport.outcome) {
                IgExecutionOutcome.SUCCESS -> StudioColors.SuccessEmerald
                IgExecutionOutcome.RATE_LIMITED_429,
                IgExecutionOutcome.CHALLENGE_REQUIRED,
                IgExecutionOutcome.LOGIN_REQUIRED -> StudioColors.WarningAmber
                IgExecutionOutcome.FAILED -> StudioColors.ErrorRose
            }
            StudioCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Last Action: ${lastReport.actionType}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    StatusPill(text = lastReport.outcome.name, color = reportColor)
                }
                Text(
                    lastReport.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionProgressRow(
    icon: ImageVector,
    title: String,
    executed: Int,
    budget: Int,
    enabled: Boolean,
    onRun: () -> Unit
) {
    val targetProgress = if (budget > 0) (executed.toFloat() / budget.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(targetValue = targetProgress, label = title)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(StudioColors.SurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = StudioColors.Amber, modifier = Modifier.size(18.dp))
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "$executed / $budget",
                    style = MaterialTheme.typography.labelMedium,
                    color = StudioColors.TextSecondary
                )
            }
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = StudioColors.Magenta,
                trackColor = StudioColors.SurfaceElevated
            )
        }

        OutlinedButton(
            onClick = onRun,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text("Run", style = MaterialTheme.typography.labelMedium)
        }
    }
}
