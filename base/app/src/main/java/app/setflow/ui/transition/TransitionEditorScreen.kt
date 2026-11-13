/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui.transition

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.TransitionOverrideParams
import app.setflow.db.EdgeOrigin
import app.setflow.ui.waveform.WaveformTileState
import kotlin.math.roundToLong

/**
 * Focused transition editor screen for an exact adjacent track pair (Stage 08, STAGES/08_SET_BUILDER_UI.txt).
 *
 * Guarantees:
 * - Operates entirely on an immutable draft state without mutating database (Task 5).
 * - Minimum 48dp touch targets on all interactive controls (Task 6).
 * - Honest preset status: unsupported effects are visibly disabled with explanatory notes (Task 8).
 * - Draggable handles for Track A exit and Track B entry anchors (Task 3).
 * - Exact 4/8/16/32 bar length controls without 12-second auto-clamp (Task 3).
 * - Back and Cancel trigger dirty confirmation dialog if draft modified (Task 5).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransitionEditorScreen(
    state: TransitionEditorState,
    waveformStateA: WaveformTileState,
    waveformStateB: WaveformTileState,
    onDraftParamsChanged: (TransitionOverrideParams) -> Unit,
    onSaveTransition: () -> Unit,
    onResetToAuto: () -> Unit,
    onTogglePreview: () -> Unit,
    onNavigateBack: () -> Unit,
    onDismissDirtyDialog: () -> Unit,
    onConfirmDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isAdvancedExpanded by remember { mutableStateOf(state.isAdvancedEqExpanded) }
    var zoomLevel by remember { mutableFloatStateOf(state.activeZoomFactor) }

    val outTrack = state.outgoingTrack
    val inTrack = state.incomingTrack
    val currentParams = state.draftParams

    val exitAnchorUs = currentParams.exitAnchorUs ?: (outTrack.durationUs - 16_000_000L).coerceAtLeast(0L)
    val entryAnchorUs = currentParams.entryAnchorUs ?: 0L
    val spanUs = state.effectiveSpanUs

    val bpm = state.outgoingAnalysis?.bpm?.takeIf { it > 0 }
        ?: state.incomingAnalysis?.bpm?.takeIf { it > 0 }
        ?: 155.0

    // Dirty confirmation dialog (Task 5)
    if (state.showDirtyConfirmationDialog) {
        AlertDialog(
            onDismissRequest = onDismissDirtyDialog,
            title = { Text("Discard Unsaved Changes?") },
            text = {
                Text("You have unsaved changes to this transition. Do you want to discard them, keep editing, or save?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissDirtyDialog()
                        onSaveTransition()
                    },
                    enabled = state.isValid
                ) {
                    Text("Save Transition")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onConfirmDiscard) {
                        Text("Discard")
                    }
                    TextButton(onClick = onDismissDirtyDialog) {
                        Text("Keep Editing")
                    }
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Top Bar
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Edit Transition",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${outTrack.title} → ${inTrack.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back or cancel transition edit"
                    )
                }
            },
            actions = {
                Button(
                    onClick = onSaveTransition,
                    enabled = state.isValid && !state.isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .height(48.dp) // >=48dp touch target
                        .semantics {
                            contentDescription = if (state.isValid) {
                                "Save transition override to database"
                            } else {
                                "Cannot save transition: ${state.validationErrors.joinToString("; ")}"
                            }
                        }
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (state.isSaving) "Saving…" else "Save")
                }
            }
        )

        // Main Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status & Origin Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OriginBadge(origin = state.edgeOrigin, isDirty = state.isDirty)
                            if (state.isPreviewPlaying) {
                                Surface(
                                    color = Color(0xFFE91E63),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "Previewing unsaved changes",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Plan Rev: ${state.edgeRevision} • Project Rev: ${state.projectRevision}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Overlap: ${String.format("%.1f", spanUs / 1_000_000.0)}s (${currentParams.barCount ?: (spanUs * bpm / 240_000_000.0).roundToLong()} bars at ${String.format("%.1f", bpm)} BPM)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Zoom toggles
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = { zoomLevel = (zoomLevel * 0.5f).coerceAtLeast(0.5f) },
                            modifier = Modifier.size(48.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { zoomLevel = (zoomLevel * 2.0f).coerceAtMost(8.0f) },
                            modifier = Modifier.size(48.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 2. Dual Waveform Timeline Canvas
            DualWaveformCanvas(
                stateA = waveformStateA,
                stateB = waveformStateB,
                exitAnchorUs = exitAnchorUs,
                entryAnchorUs = entryAnchorUs,
                spanUs = spanUs,
                trackADurationUs = outTrack.durationUs,
                trackBDurationUs = inTrack.durationUs,
                zoomFactor = zoomLevel,
                onExitAnchorChanged = { newExitUs ->
                    onDraftParamsChanged(
                        currentParams.copy(
                            exitAnchorUs = newExitUs,
                            userEditedFieldsBitset = currentParams.userEditedFieldsBitset or TransitionOverrideParams.BIT_EXIT_ANCHOR
                        )
                    )
                },
                onEntryAnchorChanged = { newEntryUs ->
                    onDraftParamsChanged(
                        currentParams.copy(
                            entryAnchorUs = newEntryUs,
                            userEditedFieldsBitset = currentParams.userEditedFieldsBitset or TransitionOverrideParams.BIT_ENTRY_ANCHOR
                        )
                    )
                }
            )

            // 3. Anchor Fine-Tuning Steppers (>=48dp touch targets)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Cue Anchor Positioning",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // Track A Exit Anchor Control
                    AnchorStepperRow(
                        label = "Track A Exit Point",
                        color = ColorTrackA,
                        currentUs = exitAnchorUs,
                        maxUs = outTrack.durationUs,
                        bpm = bpm,
                        onUpdateUs = { nextUs ->
                            onDraftParamsChanged(
                                currentParams.copy(
                                    exitAnchorUs = nextUs,
                                    userEditedFieldsBitset = currentParams.userEditedFieldsBitset or TransitionOverrideParams.BIT_EXIT_ANCHOR
                                )
                            )
                        }
                    )

                    // Track B Entry Anchor Control
                    AnchorStepperRow(
                        label = "Track B Entry Point",
                        color = ColorTrackB,
                        currentUs = entryAnchorUs,
                        maxUs = inTrack.durationUs,
                        bpm = bpm,
                        onUpdateUs = { nextUs ->
                            onDraftParamsChanged(
                                currentParams.copy(
                                    entryAnchorUs = nextUs,
                                    userEditedFieldsBitset = currentParams.userEditedFieldsBitset or TransitionOverrideParams.BIT_ENTRY_ANCHOR
                                )
                            )
                        }
                    )
                }
            }

            // 4. Overlap Length Controls (4, 8, 16, 32 bars or seconds)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Transition Length",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    if (state.isGridded) {
                        Text(
                            text = "Beat grid aligned. Select duration in musical bars:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(4, 8, 16, 32).forEach { bars ->
                                val isSelected = currentParams.barCount == bars
                                OutlinedButton(
                                    onClick = {
                                        val barDurationSec = 240.0 / bpm
                                        val newSpanUs = (bars * barDurationSec * 1_000_000.0).roundToLong()
                                        onDraftParamsChanged(
                                            currentParams.copy(
                                                barCount = bars,
                                                spanUs = newSpanUs,
                                                userEditedFieldsBitset = currentParams.userEditedFieldsBitset or
                                                        TransitionOverrideParams.BIT_BAR_COUNT or
                                                        TransitionOverrideParams.BIT_SPAN
                                            )
                                        )
                                    },
                                    colors = if (isSelected) {
                                        ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .semantics {
                                            contentDescription = "$bars bars transition duration"
                                        }
                                ) {
                                    Text(
                                        text = "$bars Bars",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Unreliable or missing beat grid. Manual duration in seconds:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        val currentSec = (spanUs / 1_000_000.0).toFloat().coerceIn(4f, 60f)
                        Slider(
                            value = currentSec,
                            onValueChange = { newSec ->
                                val newSpanUs = (newSec * 1_000_000.0).roundToLong()
                                onDraftParamsChanged(
                                    currentParams.copy(
                                        spanUs = newSpanUs,
                                        barCount = null,
                                        userEditedFieldsBitset = currentParams.userEditedFieldsBitset or TransitionOverrideParams.BIT_SPAN
                                    )
                                )
                            },
                            valueRange = 4f..60f,
                            steps = 56,
                            modifier = Modifier.semantics {
                                contentDescription = "Manual transition duration slider: ${String.format("%.1f", currentSec)} seconds"
                            }
                        )
                        Text(
                            text = "${String.format("%.1f", currentSec)} seconds",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 5. Transition Style Preset Selector (Honest feature truth)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Transition Style Preset",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransitionPresets.ALL.forEach { descriptor ->
                            val isSelected = (currentParams.shape ?: SegueShape.BASS_SWAP) == descriptor.shape
                            FilterChip(
                                selected = isSelected && descriptor.isSupported,
                                onClick = {
                                    if (descriptor.isSupported) {
                                        onDraftParamsChanged(
                                            currentParams.copy(
                                                shape = descriptor.shape,
                                                userEditedFieldsBitset = currentParams.userEditedFieldsBitset or TransitionOverrideParams.BIT_PRESET_ID
                                            )
                                        )
                                    }
                                },
                                label = {
                                    Column {
                                        Text(descriptor.displayName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        if (!descriptor.isSupported) {
                                            Text(
                                                text = "Disabled: ${descriptor.disabledReason}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                },
                                enabled = descriptor.isSupported,
                                modifier = Modifier
                                    .height(48.dp)
                                    .semantics {
                                        contentDescription = if (descriptor.isSupported) {
                                            "Preset ${descriptor.displayName}: ${descriptor.description}"
                                        } else {
                                            "Preset ${descriptor.displayName} disabled: ${descriptor.disabledReason}"
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            // 6. Advanced EQ Automation (Expandable)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Advanced EQ & Automation",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { isAdvancedExpanded = !isAdvancedExpanded },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isAdvancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isAdvancedExpanded) "Collapse advanced EQ" else "Expand advanced EQ"
                            )
                        }
                    }

                    if (isAdvancedExpanded) {
                        Text(
                            text = "3-Band EQ Bass Swap Position:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = currentParams.swapShare.toFloat(),
                            onValueChange = { nextShare ->
                                onDraftParamsChanged(
                                    currentParams.copy(
                                        swapShare = nextShare.toDouble(),
                                        userEditedFieldsBitset = currentParams.userEditedFieldsBitset or TransitionOverrideParams.BIT_LANE_OVERRIDES
                                    )
                                )
                            },
                            valueRange = 0.2f..0.8f,
                            modifier = Modifier.semantics {
                                contentDescription = "Bass swap center position ratio: ${String.format("%.2f", currentParams.swapShare)}"
                            }
                        )
                        Text(
                            text = "Swap at ${String.format("%.0f", currentParams.swapShare * 100)}% of transition length",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // 7. Validation Violations / In-situ Warnings
            if (!state.isValid) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Validation error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column {
                            Text(
                                text = "Invalid Transition Plan",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            state.validationErrors.forEach { err ->
                                Text(
                                    text = "• $err",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Action Footer: [Preview] [Reset to Auto] [Cancel]
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Preview Button (Clearly marked "Preview Unsaved")
                OutlinedButton(
                    onClick = onTogglePreview,
                    enabled = state.isValid || state.isPreviewPlaying,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics {
                            contentDescription = when {
                                state.isPreviewPlaying -> "Stop unsaved transition preview"
                                !state.isValid -> "Cannot preview unsaved transition: ${state.validationErrors.joinToString("; ")}"
                                else -> "Play unsaved transition preview audio"
                            }
                        }
                ) {
                    Icon(
                        imageVector = if (state.isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (state.isPreviewPlaying) "Stop" else "Preview Unsaved", fontSize = 12.sp)
                }

                // Reset to Auto Button
                OutlinedButton(
                    onClick = onResetToAuto,
                    modifier = Modifier
                        .weight(0.9f)
                        .height(48.dp)
                        .semantics {
                            contentDescription = "Reset transition override to automated recommendation"
                        }
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset Auto", fontSize = 12.sp)
                }

                // Cancel Button
                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .weight(0.7f)
                        .height(48.dp)
                        .semantics {
                            contentDescription = "Cancel transition edit"
                        }
                ) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun OriginBadge(origin: EdgeOrigin, isDirty: Boolean) {
    val (text, bgColor, textColor) = when {
        isDirty -> Triple("DRAFT (UNSAVED)", Color(0xFFFFB74D), Color.Black)
        origin == EdgeOrigin.CUSTOM -> Triple("CUSTOM", Color(0xFF26C6DA), Color.Black)
        else -> Triple("AUTO", Color(0xFF455A64), Color.White)
    }

    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AnchorStepperRow(
    label: String,
    color: Color,
    currentUs: Long,
    maxUs: Long,
    bpm: Double,
    onUpdateUs: (Long) -> Unit
) {
    val barDurationUs = (240.0 / bpm * 1_000_000.0).roundToLong().coerceAtLeast(1_000_000L)
    val oneSecUs = 1_000_000L

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold)
            Text(
                "${String.format("%.2f", currentUs / 1_000_000.0)}s / ${String.format("%.2f", maxUs / 1_000_000.0)}s",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedButton(
                onClick = { onUpdateUs((currentUs - barDurationUs).coerceIn(0L, maxUs)) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("-1 Bar", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { onUpdateUs((currentUs - oneSecUs).coerceIn(0L, maxUs)) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("-1.0s", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { onUpdateUs((currentUs + oneSecUs).coerceIn(0L, maxUs)) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("+1.0s", fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { onUpdateUs((currentUs + barDurationUs).coerceIn(0L, maxUs)) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Text("+1 Bar", fontSize = 11.sp)
            }
        }
    }
}
