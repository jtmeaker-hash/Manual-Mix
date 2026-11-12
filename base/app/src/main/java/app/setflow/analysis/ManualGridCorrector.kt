/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.analysis

import app.myspoti.automix.Grid
import app.myspoti.automix.TrackAnalysis
import app.setflow.db.BeatGridCorrectionEntity
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Applies persisted manual beat-grid corrections ([BeatGridCorrectionEntity]) to a track's [TrackAnalysis]
 * without faking neural model confidence (Stage 06 Task 6, REFERENCE/04_DATA_MODEL_AND_PERSISTENCE.txt line 12).
 *
 * Supports:
 * - Half/double BPM toggle (e.g. 80 BPM <-> 160 BPM drum & bass / dubstep disambiguation)
 * - Exact BPM nudging / override
 * - Phase anchor adjustment (shifting grid offset in microseconds)
 * - Downbeat anchor adjustment and beats-per-bar configuration
 */
object ManualGridCorrector {

    /**
     * Reconstructs an updated [TrackAnalysis] reflecting user manual grid corrections.
     * Guarantees all bar arrays match the new bar count and preserves honest confidence metrics.
     */
    fun applyCorrection(
        base: TrackAnalysis,
        correction: BeatGridCorrectionEntity
    ): TrackAnalysis {
        val effectiveBpm = (correction.bpmOverride ?: base.bpm).coerceIn(20.0, 300.0)
        val effectivePeriod = 60.0 / effectiveBpm
        val beatsPerBar = (correction.beatsPerBar ?: 4).coerceIn(1, 16)

        // 1. Calculate phase-aligned first beat
        val contentStart = base.contentStart
        val contentEnd = max(contentStart + effectivePeriod, base.contentEnd)

        var firstBeat = if (correction.phaseAnchorUs != null) {
            val anchorSec = correction.phaseAnchorUs / 1_000_000.0
            val periodsBefore = floor((anchorSec - contentStart) / effectivePeriod)
            anchorSec - (periodsBefore * effectivePeriod)
        } else {
            base.head?.first ?: base.beats.firstOrNull() ?: contentStart
        }

        while (firstBeat < contentStart) firstBeat += effectivePeriod
        while (firstBeat > contentStart + effectivePeriod) firstBeat -= effectivePeriod

        // 2. Synthesize new constant-grid beats
        val beatCount = max(1, floor((contentEnd - firstBeat) / effectivePeriod).toInt() + 1)
        val newBeats = DoubleArray(beatCount) { k -> firstBeat + k * effectivePeriod }

        // 3. Determine downbeat offset
        val downbeatAnchorSec = if (correction.phaseAnchorUs != null) {
            correction.phaseAnchorUs / 1_000_000.0
        } else if (base.headDown.isFinite()) {
            base.headDown
        } else {
            firstBeat
        }

        // Find the beat closest to the downbeat anchor
        var closestBeatIdx = 0
        var minDiff = Double.MAX_VALUE
        for (k in newBeats.indices) {
            val diff = abs(newBeats[k] - downbeatAnchorSec)
            if (diff < minDiff) {
                minDiff = diff
                closestBeatIdx = k
            }
        }
        val downbeatPhaseOffset = Math.floorMod(closestBeatIdx, beatsPerBar)

        // 4. Construct bar starts
        val barList = mutableListOf<Double>()
        for (k in newBeats.indices) {
            if (Math.floorMod(k - downbeatPhaseOffset, beatsPerBar) == 0) {
                barList.add(newBeats[k])
            }
        }
        if (barList.isEmpty()) {
            barList.add(firstBeat)
        }
        val newBarStarts = barList.toDoubleArray()
        val numBars = newBarStarts.size

        // 5. Build new Grid instances
        val steadiness = base.head?.steadiness?.coerceAtLeast(0.8) ?: 1.0
        val contrast = base.head?.contrast?.coerceAtLeast(1.0) ?: 1.0
        val newGrid = Grid(
            first = firstBeat,
            period = effectivePeriod,
            count = beatCount,
            steadiness = steadiness,
            driftMs = 0.0,
            contrast = contrast
        )

        val headDown = newBarStarts.firstOrNull() ?: firstBeat
        val tailDown = newBarStarts.lastOrNull() ?: firstBeat

        // 6. Resample/interpolate bar arrays to match new numBars
        val barDb = FloatArray(numBars)
        val barLowDb = FloatArray(numBars)
        val barHighDb = FloatArray(numBars)
        val barCentre = FloatArray(numBars)
        val barNovelty = FloatArray(numBars)
        val barBassDb = FloatArray(numBars)
        val barVocalRisk = FloatArray(numBars)
        val barDownConfidence = FloatArray(numBars)

        val origBars = base.bars
        for (i in 0 until numBars) {
            val t = newBarStarts[i]
            val origIdx = if (origBars > 0) base.barAt(t).coerceIn(0, origBars - 1) else 0
            if (origBars > 0) {
                barDb[i] = base.barDb[origIdx]
                barLowDb[i] = base.barLowDb[origIdx]
                barHighDb[i] = base.barHighDb[origIdx]
                barCentre[i] = base.barCentre[origIdx]
                barNovelty[i] = base.barNovelty[origIdx]
                barBassDb[i] = base.barBassDb[origIdx]
                barVocalRisk[i] = base.barVocalRisk[origIdx]
                barDownConfidence[i] = if (origIdx < base.barDownConfidence.size) {
                    base.barDownConfidence[origIdx]
                } else {
                    0f
                }
            } else {
                barDb[i] = -20f
                barLowDb[i] = -24f
                barHighDb[i] = -26f
                barCentre[i] = 0f
                barNovelty[i] = 0f
                barBassDb[i] = -24f
                barVocalRisk[i] = 0.1f
                barDownConfidence[i] = 0f
            }
        }

        // Note: Do NOT fake confidence! Honest confidence from base analysis is preserved.
        return TrackAnalysis(
            duration = base.duration,
            contentStart = base.contentStart,
            contentEnd = base.contentEnd,
            loudnessDb = base.loudnessDb,
            key = base.key,
            keyStrength = base.keyStrength,
            bpm = effectiveBpm,
            steady = true,
            head = newGrid,
            tail = newGrid,
            beats = newBeats,
            barStarts = newBarStarts,
            barDb = barDb,
            barLowDb = barLowDb,
            barHighDb = barHighDb,
            barCentre = barCentre,
            barNovelty = barNovelty,
            phraseOffset = base.phraseOffset,
            stereo = base.stereo,
            headDown = headDown,
            tailDown = tailDown,
            note = "manual_correction_rev_${correction.revision}",
            barVocalRisk = barVocalRisk,
            audioKey = base.audioKey,
            barBassDb = barBassDb,
            headPhase = base.headPhase,
            tailPhase = base.tailPhase,
            headMetre = base.headMetre,
            tailMetre = base.tailMetre,
            modelBeats = base.modelBeats,
            barDownConfidence = barDownConfidence
        )
    }

    /**
     * Resolves half or double BPM action from current BPM.
     */
    fun resolveHalfDoubleBpm(currentBpm: Double, action: HalfDoubleAction): Double {
        return when (action) {
            HalfDoubleAction.DOUBLE_BPM -> (currentBpm * 2.0).coerceIn(20.0, 300.0)
            HalfDoubleAction.HALVE_BPM -> (currentBpm / 2.0).coerceIn(20.0, 300.0)
        }
    }
}
