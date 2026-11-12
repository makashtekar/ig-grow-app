package com.iggrow.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object StudioColors {
    val BgObsidian = Color(0xFF0D0F14)
    val SurfaceCard = Color(0xFF161922)
    val SurfaceElevated = Color(0xFF1E222E)
    val OutlineSubtle = Color(0xFF2A3040)

    val TextPrimary = Color(0xFFF3F4F6)
    val TextSecondary = Color(0xFF9CA3AF)

    val Coral = Color(0xFFFF5E62)
    val Magenta = Color(0xFFC837AB)
    val Amber = Color(0xFFF77737)

    val SuccessEmerald = Color(0xFF22C55E)
    val WarningAmber = Color(0xFFF59E0B)
    val ErrorRose = Color(0xFFEF4444)

    val BrandGradient = Brush.horizontalGradient(
        colors = listOf(Coral, Magenta, Amber)
    )
}

private val StudioColorScheme = darkColorScheme(
    primary = StudioColors.Magenta,
    onPrimary = Color.White,
    secondary = StudioColors.Amber,
    onSecondary = Color.White,
    background = StudioColors.BgObsidian,
    onBackground = StudioColors.TextPrimary,
    surface = StudioColors.SurfaceCard,
    onSurface = StudioColors.TextPrimary,
    surfaceVariant = StudioColors.SurfaceElevated,
    onSurfaceVariant = StudioColors.TextSecondary,
    outline = StudioColors.OutlineSubtle,
    error = StudioColors.ErrorRose,
    onError = Color.White
)

@Composable
fun IgGrowStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        content = content
    )
}

@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (elevated) StudioColors.SurfaceElevated else StudioColors.SurfaceCard
        ),
        border = BorderStroke(1.dp, StudioColors.OutlineSubtle),
        content = {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    )
}

@Composable
fun GradientButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = StudioColors.SurfaceElevated
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = if (enabled) StudioColors.BrandGradient else Brush.horizontalGradient(
                        listOf(StudioColors.SurfaceElevated, StudioColors.SurfaceElevated)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) Color.White else StudioColors.TextSecondary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
