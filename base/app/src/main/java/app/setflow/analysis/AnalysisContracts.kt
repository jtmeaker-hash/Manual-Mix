/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.analysis

import app.myspoti.automix.TrackAnalysis
import app.setflow.domain.AudioErrorCode
import kotlinx.coroutines.flow.StateFlow

/**
 * Priority classes for bounded background analysis queueing (Stage 06 Task 7).
 * Prevents UI freezes and ensures playback-armed or actively previewed transitions
 * are prioritized over idle library background scans.
 */
enum class AnalysisPriority(val weight: Int) {
    PLAYBACK_ARMED(4),
    TRANSITION_PREVIEW(3),
    QUEUE_NEXT(2),
    IDLE_LIBRARY(1)
}

/**
 * High-level confidence rating derived from beat grid steadiness, drift, and onset contrast.
 * Manual corrections never invent neural confidence (REFERENCE/04_DATA_MODEL_AND_PERSISTENCE.txt line 12).
 */
enum class AnalysisConfidence {
    /** Both head and tail (or global) beat grids are steady, low-drift, and reliable for phase-locked mixing. */
    CONFIDENT,
    /** Unsteady, absent, or drifting grid; requires time-based equal-power fade fallback (ACC-32, A04_BAD_ANALYSIS). */
    UNRELIABLE,
    /** Audio decode or I/O failure. */
    FAILED
}

/**
 * User-facing analysis status for a track recording.
 */
sealed interface AnalysisStatus {
    object Idle : AnalysisStatus

    data class InProgress(val progress: Float) : AnalysisStatus

    data class Confident(
        val analysis: TrackAnalysis,
        val isManualCorrection: Boolean,
        val bpm: Double,
        val key: Int,
        val steady: Boolean,
        val cacheHit: Boolean = false
    ) : AnalysisStatus

    data class Unreliable(
        val analysis: TrackAnalysis,
        val isManualCorrection: Boolean,
        val bpm: Double,
        val reason: String,
        val cacheHit: Boolean = false
    ) : AnalysisStatus

    data class Failed(
        val errorCode: AudioErrorCode,
        val message: String
    ) : AnalysisStatus
}

/**
 * Actions for half/double BPM ambiguity correction (e.g. 80 BPM vs 160 BPM drum & bass / dubstep).
 */
enum class HalfDoubleAction {
    DOUBLE_BPM,
    HALVE_BPM
}

/**
 * User manual beat-grid adjustment parameters (Stage 06 Task 6).
 */
data class BeatGridCorrectionParams(
    val bpmOverride: Double? = null,
    val phaseAnchorUs: Long? = null,
    val beatsPerBar: Int? = null,
    val halfDoubleAction: HalfDoubleAction? = null,
    val regionStartUs: Long? = null,
    val regionEndUs: Long? = null
)

/**
 * Analysis engine service contract for track beat grid analysis and manual correction.
 */
interface TrackAnalysisEngine {
    val states: StateFlow<Map<String, AnalysisStatus>>

    fun getStatus(trackId: String): AnalysisStatus

    suspend fun analyze(
        trackId: String,
        priority: AnalysisPriority = AnalysisPriority.IDLE_LIBRARY,
        forceReanalysis: Boolean = false
    ): AnalysisStatus

    suspend fun applyManualCorrection(
        trackId: String,
        params: BeatGridCorrectionParams
    ): AnalysisStatus

    fun cancelAnalysis(trackId: String)

    fun clearCache()
}
