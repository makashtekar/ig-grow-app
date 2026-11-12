package com.iggrow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iggrow.app.cloud.DailyTasksResponse
import com.iggrow.app.session.IgSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrowthDashboardScreen(
    session: IgSession,
    dailyTasks: DailyTasksResponse?,
    isRunning: Boolean,
    onToggleRunning: () -> Unit,
    onRefresh: () -> Unit,
    onReconnectAccount: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("IG Grow Dashboard") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("@${session.username}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Status: ${dailyTasks?.accountStatus ?: if (isRunning) "ACTIVE" else "PAUSED"} • " +
                            "Tier: ${dailyTasks?.tier ?: "TIER_1_COLD"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (dailyTasks != null) {
                        Text(
                            "Day ${dailyTasks.dayNumber}: ${dailyTasks.focus}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (dailyTasks != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Today's Safety-Capped Budget", style = MaterialTheme.typography.titleMedium)
                        Text("Story Views: ${dailyTasks.actionBudgets.storyViews}")
                        Text("Story Likes: ${dailyTasks.actionBudgets.storyLikes}")
                        Text("Post Likes: ${dailyTasks.actionBudgets.postLikes}")
                        Text("Comments: ${dailyTasks.actionBudgets.comments}")
                        Text("Follows: ${dailyTasks.actionBudgets.follows}")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggleRunning, modifier = Modifier.weight(1f)) {
                    Text(if (isRunning) "Pause Autopilot" else "Start Autopilot")
                }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                    Text("Sync Plan")
                }
            }

            OutlinedButton(
                onClick = onReconnectAccount,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Re-authenticate WebView Session")
            }
        }
    }
}
