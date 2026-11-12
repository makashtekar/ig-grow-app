package com.iggrow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iggrow.app.cloud.GrowthPlanDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanReviewScreen(
    plan: GrowthPlanDto,
    isApproving: Boolean,
    onApprove: (List<String>) -> Unit
) {
    var commentsText by remember(plan.planId) {
        mutableStateOf(plan.commentTemplates.joinToString("\n"))
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Review AI 7-Day Plan") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Strategy Summary", style = MaterialTheme.typography.titleMedium)
                        Text(plan.strategySummary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            items(plan.dailySchedules) { day ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Day ${day.dayNumber}: ${day.focus}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Stories: ${day.actionBudgets.storyViews} views / ${day.actionBudgets.storyLikes} likes • " +
                                "Likes: ${day.actionBudgets.postLikes} • " +
                                "Comments: ${day.actionBudgets.comments} • " +
                                "Follows: ${day.actionBudgets.follows}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = commentsText,
                    onValueChange = { commentsText = it },
                    label = { Text("Approved Comment Templates (one per line)") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(
                    onClick = {
                        val cleaned = commentsText.lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        onApprove(cleaned)
                    },
                    enabled = !isApproving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isApproving) "Activating..." else "Approve & Activate Autopilot")
                }
            }
        }
    }
}
