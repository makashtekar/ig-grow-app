package com.iggrow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.iggrow.app.cloud.DayPlanDto
import com.iggrow.app.cloud.GrowthPlanDto
import com.iggrow.app.ui.theme.GradientButton
import com.iggrow.app.ui.theme.StudioCard
import com.iggrow.app.ui.theme.StudioColors

@Composable
fun PlanReviewScreen(
    plan: GrowthPlanDto,
    isApproving: Boolean,
    onApprove: (List<String>, List<DayPlanDto>) -> Unit,
    onEditGoalSetup: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var commentsText by remember(plan.planId) {
        mutableStateOf(plan.commentTemplates.joinToString("\n"))
    }
    val editableDays = remember(plan.planId) {
        mutableStateListOf<DayPlanDto>().apply { addAll(plan.dailySchedules) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(StudioColors.BgObsidian)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioCard(elevated = true) {
                Text(
                    "7-Day Growth Strategy",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    plan.strategySummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StudioColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (onEditGoalSetup != null) {
                    OutlinedButton(
                        onClick = onEditGoalSetup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Text("Edit Niche, Competitors & Goal")
                    }
                }
            }
        }

        itemsIndexed(editableDays) { index, day ->
            StudioCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Day ${day.dayNumber}: ${day.focus}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BudgetNumberField(
                            label = "Views",
                            value = day.actionBudgets.storyViews,
                            modifier = Modifier.weight(1f)
                        ) { v ->
                            editableDays[index] = day.copy(
                                actionBudgets = day.actionBudgets.copy(storyViews = v)
                            )
                        }
                        BudgetNumberField(
                            label = "Likes",
                            value = day.actionBudgets.postLikes,
                            modifier = Modifier.weight(1f)
                        ) { v ->
                            editableDays[index] = day.copy(
                                actionBudgets = day.actionBudgets.copy(postLikes = v)
                            )
                        }
                        BudgetNumberField(
                            label = "Cmt",
                            value = day.actionBudgets.comments,
                            modifier = Modifier.weight(1f)
                        ) { v ->
                            editableDays[index] = day.copy(
                                actionBudgets = day.actionBudgets.copy(comments = v)
                            )
                        }
                        BudgetNumberField(
                            label = "Follow",
                            value = day.actionBudgets.follows,
                            modifier = Modifier.weight(1f)
                        ) { v ->
                            editableDays[index] = day.copy(
                                actionBudgets = day.actionBudgets.copy(follows = v)
                            )
                        }
                    }
                }
            }
        }

        item {
            StudioCard {
                OutlinedTextField(
                    value = commentsText,
                    onValueChange = { commentsText = it },
                    label = { Text("Approved Comment Templates (one per line)") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            GradientButton(
                text = if (isApproving) "Activating..." else "Save & Activate Autopilot",
                enabled = !isApproving,
                modifier = Modifier.fillMaxWidth()
            ) {
                val cleaned = commentsText.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                onApprove(cleaned, editableDays.toList())
            }
        }
    }
}

@Composable
private fun BudgetNumberField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { raw ->
            val parsed = raw.filter { it.isDigit() }.toIntOrNull() ?: 0
            onValueChange(parsed)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
