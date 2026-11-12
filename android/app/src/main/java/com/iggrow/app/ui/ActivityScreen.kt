package com.iggrow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iggrow.app.safety.ActivityLogEntry
import com.iggrow.app.session.IgSession
import com.iggrow.app.ui.theme.StatusPill
import com.iggrow.app.ui.theme.StudioCard
import com.iggrow.app.ui.theme.StudioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityScreen(
    session: IgSession,
    logs: List<ActivityLogEntry>,
    onReconnectAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(StudioColors.BgObsidian)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudioCard(elevated = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Session Health",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "@${session.username} • ID ${session.dsUserId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.TextSecondary
                        )
                    }
                    StatusPill(
                        text = if (session.isComplete()) "CONNECTED" else "INCOMPLETE",
                        color = if (session.isComplete()) StudioColors.SuccessEmerald else StudioColors.ErrorRose
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        text = if (session.fbDtsg.isNotBlank()) "fb_dtsg ✓" else "fb_dtsg auto",
                        color = StudioColors.SuccessEmerald
                    )
                    StatusPill(
                        text = if (session.lsd.isNotBlank()) "lsd ✓" else "lsd auto",
                        color = StudioColors.SuccessEmerald
                    )
                }

                OutlinedButton(
                    onClick = onReconnectAccount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text("Re-authenticate WebView Session")
                }
            }
        }

        item {
            Text(
                "Recent Execution Log (${logs.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = StudioColors.TextPrimary
            )
        }

        if (logs.isEmpty()) {
            item {
                StudioCard {
                    Text(
                        "No actions executed yet. Tap Run on any action in Dashboard or start Autopilot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StudioColors.TextSecondary
                    )
                }
            }
        } else {
            items(logs) { entry ->
                val pillColor = when (entry.outcome) {
                    "SUCCESS" -> StudioColors.SuccessEmerald
                    "RATE_LIMITED_429", "CHALLENGE_REQUIRED", "LOGIN_REQUIRED" -> StudioColors.WarningAmber
                    else -> StudioColors.ErrorRose
                }
                StudioCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${entry.actionType} • ${timeFormatter.format(Date(entry.timestampMs))}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        StatusPill(text = entry.outcome, color = pillColor)
                    }
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
