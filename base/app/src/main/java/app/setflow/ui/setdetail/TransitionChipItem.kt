/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui.setdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.setflow.db.EdgeCompleteSnapshot
import app.setflow.db.EdgeOrigin
import app.setflow.db.EdgeValidationStatus
import java.util.Locale

/**
 * Compact inline transition chip rendered between every adjacent track pair in the set (Stage 08, Task 2).
 *
 * Guarantees:
 * - Minimum 48dp touch target (Task 6).
 * - Distinguishes Auto / Custom / Needs Review status badges (Task 2).
 * - Shows actual length in bars and seconds (Task 2).
 * - Tapping opens editor for the exact edgeId and plan revision (Task 2).
 * - TalkBack accessibility labels (Task 6).
 */
@Composable
fun TransitionChipItem(
    edgeSnapshot: EdgeCompleteSnapshot,
    outgoingTitle: String,
    incomingTitle: String,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val edge = edgeSnapshot.edge
    val override = edgeSnapshot.overridePlan

    val isCustom = edge.origin == EdgeOrigin.CUSTOM
    val isStale = edge.lastValidationStatus == EdgeValidationStatus.STALE_ANALYSIS ||
            edge.lastValidationStatus == EdgeValidationStatus.INVALID

    val barCount = override?.barCount
    val spanUs = override?.spanUs ?: 12_000_000L
    val spanSec = spanUs / 1_000_000.0

    val lengthText = if (barCount != null && barCount > 0) {
        "$barCount bars (${String.format(Locale.US, "%.1f", spanSec)}s)"
    } else {
        "${String.format(Locale.US, "%.1f", spanSec)}s"
    }

    val presetName = override?.presetId?.replace("_", " ")?.lowercase(Locale.US)?.replaceFirstChar { it.uppercase() }
        ?: "Bass swap"

    val originLabel = when {
        isStale -> "Needs Review"
        isCustom -> "Custom"
        else -> "Auto"
    }

    val (badgeBg, badgeFg) = when {
        isStale -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        isCustom -> Color(0xFF26C6DA).copy(alpha = 0.2f) to Color(0xFF26C6DA)
        else -> Color(0xFF455A64).copy(alpha = 0.3f) to Color(0xFFA5B0C2)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            // Minimum 48dp touch target on entire chip (Task 6)
            .height(52.dp)
            .clickable(
                role = Role.Button,
                onClick = onOpenEditor
            )
            .semantics {
                contentDescription = "Transition between $outgoingTitle and $incomingTitle, $originLabel, $lengthText, style $presetName, tap to edit."
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Waveform / transition icon
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    tint = if (isCustom) Color(0xFF26C6DA) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Origin Badge
                Box(
                    modifier = Modifier
                        .background(badgeBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = originLabel,
                        color = badgeFg,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Length & Preset Info
                Text(
                    text = "$lengthText • $presetName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            // Edit Affordance (Arrow / Edit >)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Edit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
