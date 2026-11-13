/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui.transition

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.setflow.ui.waveform.WaveformPeakTile
import app.setflow.ui.waveform.WaveformTileState
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

val ColorTrackA = Color(0xFF26C6DA) // Cyan / Teal
val ColorTrackB = Color(0xFFFFA726) // Amber / Orange
val ColorGridBeat = Color(0x33FFFFFF)
val ColorGridDownbeat = Color(0x88FFFFFF)
val ColorOverlapBand = Color(0x22FFFFFF)

/**
 * Dual zoomable waveform canvas displaying Track A (exit) and Track B (entry) with interactive cue handles (Stage 08, Task 3).
 *
 * Guarantees:
 * - Fixed height placeholder while loading (Task 4).
 * - High contrast distinct colors for Track A and Track B (Task 3).
 * - Minimum 48dp touch targets on draggable cue handles (Task 6).
 * - TalkBack accessibility labels on cue anchors and grid lines.
 */
@Composable
fun DualWaveformCanvas(
    stateA: WaveformTileState,
    stateB: WaveformTileState,
    exitAnchorUs: Long,
    entryAnchorUs: Long,
    spanUs: Long,
    trackADurationUs: Long,
    trackBDurationUs: Long,
    onExitAnchorChanged: (Long) -> Unit,
    onEntryAnchorChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    zoomFactor: Float = 1.0f
) {
    val canvasHeightDp = 220.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(canvasHeightDp)
            .background(Color(0xFF0F131C), RoundedCornerShape(8.dp))
            .semantics {
                contentDescription = "Dual waveform timeline. Track A Exit anchor at ${String.format(Locale.US, "%.1f", exitAnchorUs / 1_000_000.0)}s, Track B Entry anchor at ${String.format(Locale.US, "%.1f", entryAnchorUs / 1_000_000.0)}s, overlap duration ${String.format(Locale.US, "%.1f", spanUs / 1_000_000.0)}s."
            }
    ) {
        val availableWidthDp = maxWidth

        if (stateA is WaveformTileState.Loading || stateB is WaveformTileState.Loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Decimating analyzed audio energy…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            return@BoxWithConstraints
        }

        val tileA = (stateA as? WaveformTileState.Ready)?.tile
        val tileB = (stateB as? WaveformTileState.Ready)?.tile

        // Window range in microseconds centered around the transition overlap
        val windowDurationUs = (spanUs * 3L / zoomFactor).toLong().coerceIn(8_000_000L, 120_000_000L)
        val windowStartUs = (exitAnchorUs - windowDurationUs / 3L).coerceIn(0L, max(0L, trackADurationUs - windowDurationUs))
        val windowEndUs = windowStartUs + windowDurationUs

        // Canvas drawing
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp)
        ) {
            val width = size.width
            val height = size.height
            val midY = height / 2f
            val trackAHeight = midY - 6f
            val trackBHeight = height - midY - 6f

            // 1. Shaded Overlap Window Background
            val overlapStartX = ((exitAnchorUs - windowStartUs).toFloat() / windowDurationUs * width).coerceIn(0f, width)
            val overlapEndX = (((exitAnchorUs + spanUs) - windowStartUs).toFloat() / windowDurationUs * width).coerceIn(0f, width)
            if (overlapEndX > overlapStartX) {
                drawRect(
                    color = ColorOverlapBand,
                    topLeft = Offset(overlapStartX, 0f),
                    size = Size(overlapEndX - overlapStartX, height)
                )
            }

            // 2. Track A Waveform (Top Half)
            if (tileA != null && tileA.peakAmplitudes.isNotEmpty()) {
                drawWaveformPeaks(
                    peaks = tileA.peakAmplitudes,
                    color = ColorTrackA,
                    baseY = trackAHeight,
                    maxHeight = trackAHeight * 0.9f,
                    width = width,
                    isUpward = true
                )
            }

            // 3. Track B Waveform (Bottom Half)
            if (tileB != null && tileB.peakAmplitudes.isNotEmpty()) {
                drawWaveformPeaks(
                    peaks = tileB.peakAmplitudes,
                    color = ColorTrackB,
                    baseY = midY + 6f,
                    maxHeight = trackBHeight * 0.9f,
                    width = width,
                    isUpward = false
                )
            }

            // 4. Center Divider Line
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, midY),
                end = Offset(width, midY),
                strokeWidth = 1f
            )

            // 5. Cue Anchor Vertical Lines
            // Track A Exit Anchor Line
            val exitX = ((exitAnchorUs - windowStartUs).toFloat() / windowDurationUs * width).coerceIn(0f, width)
            drawLine(
                color = ColorTrackA,
                start = Offset(exitX, 0f),
                end = Offset(exitX, height),
                strokeWidth = 3f
            )

            // Track B Entry Anchor Line
            val entryX = ((entryAnchorUs - windowStartUs).toFloat() / windowDurationUs * width).coerceIn(0f, width)
            drawLine(
                color = ColorTrackB,
                start = Offset(entryX, 0f),
                end = Offset(entryX, height),
                strokeWidth = 3f
            )
        }

        // Draggable Cue Handle: Track A Exit
        CueAnchorDraggableHandle(
            label = "A Exit",
            timeUs = exitAnchorUs,
            windowStartUs = windowStartUs,
            windowDurationUs = windowDurationUs,
            trackMaxUs = trackADurationUs,
            availableWidthDp = availableWidthDp,
            color = ColorTrackA,
            isTop = true,
            onAnchorChanged = onExitAnchorChanged
        )

        // Draggable Cue Handle: Track B Entry
        CueAnchorDraggableHandle(
            label = "B Entry",
            timeUs = entryAnchorUs,
            windowStartUs = windowStartUs,
            windowDurationUs = windowDurationUs,
            trackMaxUs = trackBDurationUs,
            availableWidthDp = availableWidthDp,
            color = ColorTrackB,
            isTop = false,
            onAnchorChanged = onEntryAnchorChanged
        )
    }
}

@Composable
private fun CueAnchorDraggableHandle(
    label: String,
    timeUs: Long,
    windowStartUs: Long,
    windowDurationUs: Long,
    trackMaxUs: Long,
    availableWidthDp: Dp,
    color: Color,
    isTop: Boolean,
    onAnchorChanged: (Long) -> Unit
) {
    var dragAccumulatorPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Compute horizontal offset responsive to actual measured viewport width
        val fraction = ((timeUs - windowStartUs).toFloat() / windowDurationUs).coerceIn(0.02f, 0.98f)
        val handleStartDp = ((availableWidthDp * fraction) - 28.dp).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .align(if (isTop) Alignment.TopStart else Alignment.BottomStart)
                .padding(
                    start = handleStartDp,
                    top = if (isTop) 4.dp else 0.dp,
                    bottom = if (isTop) 0.dp else 4.dp
                )
                // Enforce >= 48dp touch target (Task 6)
                .height(48.dp)
                .pointerInput(timeUs, windowDurationUs) {
                    detectDragGestures(
                        onDragStart = { dragAccumulatorPx = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulatorPx += dragAmount.x
                            val usPerPx = windowDurationUs / size.width.coerceAtLeast(1)
                            val deltaUs = (dragAmount.x * usPerPx).toLong()
                            val nextUs = (timeUs + deltaUs).coerceIn(0L, trackMaxUs)
                            onAnchorChanged(nextUs)
                        }
                    )
                }
                .semantics {
                    contentDescription = "$label anchor handle at ${String.format(Locale.US, "%.1f", timeUs / 1_000_000.0)}s. Drag horizontally to adjust."
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .background(color, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "$label: ${String.format(Locale.US, "%.1f", timeUs / 1_000_000.0)}s",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveformPeaks(
    peaks: FloatArray,
    color: Color,
    baseY: Float,
    maxHeight: Float,
    width: Float,
    isUpward: Boolean
) {
    val barCount = peaks.size
    val stepX = width / max(1, barCount)
    val barWidth = max(1.5f, stepX * 0.75f)

    for (i in 0 until barCount) {
        val amp = peaks[i].coerceIn(0.04f, 1.0f)
        val barHeight = amp * maxHeight
        val x = i * stepX
        val top = if (isUpward) baseY - barHeight else baseY
        val bottom = if (isUpward) baseY else baseY + barHeight

        drawRect(
            color = color,
            topLeft = Offset(x, top),
            size = Size(barWidth, bottom - top)
        )
    }
}
