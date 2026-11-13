package com.iggrow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iggrow.app.cloud.RegisterAccountRequest
import com.iggrow.app.cloud.SuggestTargetsResponse
import com.iggrow.app.session.IgSession
import com.iggrow.app.ui.theme.GradientButton
import com.iggrow.app.ui.theme.StudioCard
import com.iggrow.app.ui.theme.StudioColors
import kotlinx.coroutines.launch
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalOnboardingScreen(
    session: IgSession,
    isSubmitting: Boolean,
    errorMessage: String?,
    onSuggestTargets: (suspend (String, String) -> SuggestTargetsResponse)? = null,
    onSubmitGoal: (RegisterAccountRequest) -> Unit
) {
    var selectedGoal by remember { mutableStateOf("INCREASE_FOLLOWERS") }
    var niche by remember { mutableStateOf("") }
    var competitors by remember { mutableStateOf("") }
    var hashtags by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("friendly, authentic, concise") }
    var isSuggesting by remember { mutableStateOf(false) }
    var nicheAdvice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val goals = listOf(
        "INCREASE_FOLLOWERS" to "Followers",
        "BOOST_ENGAGEMENT" to "Engagement",
        "NICHE_AUTHORITY" to "Authority"
    )

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
                            "Configure growth for @${session.username}",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.TextSecondary
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(StudioColors.BgObsidian)
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StudioCard {
                Text("Primary Goal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    goals.forEach { (key, label) ->
                        FilterChip(
                            selected = selectedGoal == key,
                            onClick = { selectedGoal = key },
                            label = { Text(label) }
                        )
                    }
                }
            }

            StudioCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = niche,
                        onValueChange = { niche = it },
                        label = { Text("Your Niche (e.g. Home Workouts, Nature, SaaS)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Competitors & Hashtags", style = MaterialTheme.typography.labelMedium, color = StudioColors.TextSecondary)
                        if (onSuggestTargets != null) {
                            TextButton(
                                onClick = {
                                    if (niche.isNotBlank()) {
                                        scope.launch {
                                            isSuggesting = true
                                            runCatching {
                                                onSuggestTargets(niche, selectedGoal)
                                            }.onSuccess { suggestions ->
                                                competitors = suggestions.competitorHandles.joinToString(", ")
                                                hashtags = suggestions.targetHashtags.joinToString(", ")
                                                if (suggestions.recommendedTone.isNotBlank()) {
                                                    tone = suggestions.recommendedTone
                                                }
                                                nicheAdvice = suggestions.nicheAdvice
                                            }
                                            isSuggesting = false
                                        }
                                    }
                                },
                                enabled = niche.isNotBlank() && !isSuggesting
                            ) {
                                if (isSuggesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = StudioColors.Amber)
                                    Spacer(Modifier.width(6.dp))
                                    Text("AI Brain Thinking...", style = MaterialTheme.typography.labelSmall, color = StudioColors.Amber)
                                } else {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = StudioColors.Amber, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Auto-Fill with AI", style = MaterialTheme.typography.labelSmall, color = StudioColors.Amber, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (!nicheAdvice.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StudioColors.SurfaceElevated,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = StudioColors.Amber, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(nicheAdvice!!, style = MaterialTheme.typography.bodySmall, color = StudioColors.TextPrimary)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = competitors,
                        onValueChange = { competitors = it },
                        label = { Text("Competitor Handles (comma-separated)") },
                        placeholder = { Text("fantastic.life, gymshark") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = hashtags,
                        onValueChange = { hashtags = it },
                        label = { Text("Target Hashtags (comma-separated)") },
                        placeholder = { Text("nature, fitness") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = tone,
                        onValueChange = { tone = it },
                        label = { Text("Comment Tone") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage, color = StudioColors.ErrorRose)
            }

            Spacer(modifier = Modifier.height(4.dp))

            GradientButton(
                text = if (isSubmitting) "Generating 7-Day Plan..." else "Generate 7-Day Plan",
                enabled = !isSubmitting && niche.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                val compList = competitors.split(",")
                    .map { it.trim().removePrefix("@") }
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(session.username) }
                val tagList = hashtags.split(",")
                    .map { it.trim().removePrefix("#") }
                    .filter { it.isNotBlank() }
                    .ifEmpty { listOf(niche.trim().replace(" ", "")) }

                onSubmitGoal(
                    RegisterAccountRequest(
                        dsUserId = session.dsUserId,
                        username = session.username,
                        goal = selectedGoal,
                        niche = niche.trim().ifBlank { "lifestyle" },
                        competitorHandles = compList,
                        targetHashtags = tagList,
                        commentTone = tone.trim().ifBlank { "authentic" },
                        timezone = TimeZone.getDefault().id
                    )
                )
            }
        }
    }
}
