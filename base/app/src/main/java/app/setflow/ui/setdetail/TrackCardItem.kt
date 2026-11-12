/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui.setdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.setflow.analysis.AnalysisStatus
import app.setflow.db.OccurrenceWithTrack
import app.setflow.domain.TrackAvailabilityState
import java.util.Locale

/**
 * Track card in the Spotify-simple vertical set playlist (Stage 08, Task 1).
 *
 * Guarantees:
 * - Minimum 48dp touch targets on move and remove actions.
 * - Comprehensive TalkBack semantic labels.
 * - Displays detected BPM, musical key, artwork placeholder, and availability state.
 */
@Composable
fun TrackCardItem(
    item: OccurrenceWithTrack,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    analysisStatus: AnalysisStatus?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val occ = item.occurrence
    val track = item.track

    val bpmText = when (analysisStatus) {
        is AnalysisStatus.Confident -> String.format(Locale.US, "%.1f BPM", analysisStatus.bpm)
        is AnalysisStatus.Unreliable -> String.format(Locale.US, "%.1f BPM (approx)", analysisStatus.bpm)
        else -> "-- BPM"
    }

    val keyText = when (analysisStatus) {
        is AnalysisStatus.Confident -> formatKey(analysisStatus.key)
        else -> "--"
    }

    val durationText = formatDuration(track.durationUs)
    val artistText = if (track.artist.isBlank()) "Unknown Artist" else track.artist

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Track #${occ.positionIndex + 1}: ${track.title} by $artistText, duration $durationText, $bpmText, key $keyText, status ${track.availabilityState}."
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork placeholder (fixed dimensions, dark container)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Metadata Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${occ.positionIndex + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Text(
                    text = "$artistText • $durationText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )

                // Badges row: BPM, Key, Availability
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Badge(text = bpmText, bg = Color(0xFF1E2838), fg = Color(0xFF26C6DA))
                    Badge(text = keyText, bg = Color(0xFF1E2838), fg = Color(0xFFFFA726))
                    if (track.availabilityState != TrackAvailabilityState.AVAILABLE.name) {
                        Badge(
                            text = track.availabilityState,
                            bg = MaterialTheme.colorScheme.errorContainer,
                            fg = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Action Buttons (Enforcing >=48dp touch targets, Task 6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canMoveUp) {
                    IconButton(
                        onClick = onMoveUp,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Move ${track.title} up" }
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }

                if (canMoveDown) {
                    IconButton(
                        onClick = onMoveDown,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Move ${track.title} down" }
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Remove ${track.title} from set" }
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text = text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatDuration(durationUs: Long): String {
    val totalSec = durationUs / 1_000_000L
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private fun formatKey(key: Int): String {
    if (key < 0) return "--"
    val noteNames = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    return if (key < 12) {
        "${noteNames[key]} Maj"
    } else {
        "${noteNames[key % 12]} Min"
    }
}
