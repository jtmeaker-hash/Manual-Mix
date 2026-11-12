/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui.setdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
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
import app.setflow.db.ProjectDetailSnapshot
import app.setflow.domain.LocalTrackRecord
import java.util.Locale

/**
 * Spotify-simple vertical set playlist screen in Jetpack Compose (Stage 08, Task 1).
 *
 * Guarantees:
 * - LazyColumn items keyed strictly by stable occurrenceId and edgeId (Bug Trap 38).
 * - Minimum 48dp touch targets on all interactive controls (Task 6).
 * - Play and Export actions clearly indicated as disabled / arriving in later stages (Task 1).
 * - Inline transition chips between every adjacent track pair (Task 2).
 * - Undo support for track reorder and removal actions (Task 7).
 */
@Composable
fun SetDetailScreen(
    setId: String,
    projectDetail: ProjectDetailSnapshot?,
    libraryTracks: List<LocalTrackRecord>,
    analysisStatuses: Map<String, AnalysisStatus>,
    onOpenTransitionEditor: (edgeId: String) -> Unit,
    onAddTrackToSet: (trackId: String) -> Unit,
    onMoveOccurrenceUp: (occurrenceId: String) -> Unit,
    onMoveOccurrenceDown: (occurrenceId: String) -> Unit,
    onRemoveOccurrence: (occurrenceId: String) -> Unit,
    onUndoStructuralChange: () -> Unit,
    onBackToSets: () -> Unit,
    modifier: Modifier = Modifier
) {
    val occurrences = projectDetail?.occurrences.orEmpty()
    val edgesByPair = projectDetail?.edges.orEmpty().associateBy {
        it.edge.outgoingOccurrenceId to it.edge.incomingOccurrenceId
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Header Card
        item(key = "set_header_$setId") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = projectDetail?.project?.name ?: "Set Detail",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Revision ${projectDetail?.project?.editRevision ?: 1L} • ${occurrences.size} track(s) • ${projectDetail?.edges?.size ?: 0} transition(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    // Action buttons (Play / Export - clearly marked arriving in Stage 10/11 per Task 1)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {},
                            enabled = false, // Disabled until Stage 10 (Task 1)
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "Play full set (Playback engine arrives in Stage 10)"
                                }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play (Stage 10)")
                        }

                        OutlinedButton(
                            onClick = {},
                            enabled = false, // Disabled until Stage 11 (Task 1)
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "Export set to WAV (Offline WAV export arrives in Stage 11)"
                                }
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export (Stage 11)")
                        }
                    }

                    // Undo structural change banner
                    if (projectDetail?.canUndo == true) {
                        OutlinedButton(
                            onClick = onUndoStructuralChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "Undo last track reorder or removal"
                                }
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Undo Last Reorder / Removal")
                        }
                    }
                }
            }
        }

        // 2. Empty state or List of Track occurrences and Inline Transition chips
        if (occurrences.isEmpty()) {
            item(key = "empty_occurrences_$setId") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No tracks in this set yet.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Import local audio files or append imported tracks below to start building your DJ set.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Render tracks and transition chips
            occurrences.forEachIndexed { index, occWithTrack ->
                val occ = occWithTrack.occurrence
                val track = occWithTrack.track

                // Track Item (Strictly keyed by occurrenceId, Bug Trap 38)
                item(key = "track_${occ.occurrenceId}") {
                    TrackCardItem(
                        item = occWithTrack,
                        canMoveUp = index > 0,
                        canMoveDown = index < occurrences.lastIndex,
                        analysisStatus = analysisStatuses[track.trackId],
                        onMoveUp = { onMoveOccurrenceUp(occ.occurrenceId) },
                        onMoveDown = { onMoveOccurrenceDown(occ.occurrenceId) },
                        onRemove = { onRemoveOccurrence(occ.occurrenceId) }
                    )
                }

                // Inline Transition Chip between adjacent pairs
                if (index < occurrences.lastIndex) {
                    val nextOcc = occurrences[index + 1].occurrence
                    val nextTrack = occurrences[index + 1].track
                    val edgeSnap = edgesByPair[occ.occurrenceId to nextOcc.occurrenceId]

                    if (edgeSnap != null) {
                        // Strictly keyed by edgeId (Bug Trap 38)
                        item(key = "edge_${edgeSnap.edge.edgeId}") {
                            TransitionChipItem(
                                edgeSnapshot = edgeSnap,
                                outgoingTitle = track.title,
                                incomingTitle = nextTrack.title,
                                onOpenEditor = { onOpenTransitionEditor(edgeSnap.edge.edgeId) }
                            )
                        }
                    }
                }
            }
        }

        // 3. Add Imported Tracks section
        if (libraryTracks.isNotEmpty()) {
            item(key = "add_tracks_section_header") {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Add Imported Track to Set",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            itemsIndexed(
                items = libraryTracks,
                key = { _, track -> "lib_track_${track.trackId}" }
            ) { _, track ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = "${track.artist.ifBlank { "Unknown Artist" }} • ${formatTrackDuration(track.durationUs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedButton(
                            onClick = { onAddTrackToSet(track.trackId) },
                            modifier = Modifier
                                .height(48.dp)
                                .semantics {
                                    contentDescription = "Append ${track.title} as new occurrence in set"
                                }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                }
            }
        }

        // 4. Back to Sets button
        item(key = "back_to_sets_footer") {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBackToSets,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics { contentDescription = "Return to sets library" }
            ) {
                Text("Back to Sets")
            }
        }
    }
}

private fun formatTrackDuration(durationUs: Long): String {
    val totalSec = durationUs / 1_000_000L
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
