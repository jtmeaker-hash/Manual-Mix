/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui.transition

import app.myspoti.automix.TrackAnalysis
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionResolutionResult
import app.setflow.analysis.AnalysisStatus
import app.setflow.db.EdgeOrigin
import app.setflow.db.TrackEntity

/**
 * UI description and availability of transition style presets (Stage 08, Task 8).
 * Unsupported effects are visibly marked disabled with clear technical explanations.
 */
data class TransitionPresetDescriptor(
    val shape: SegueShape,
    val displayName: String,
    val description: String,
    val isSupported: Boolean,
    val disabledReason: String? = null
)

object TransitionPresets {
    val ALL: List<TransitionPresetDescriptor> = listOf(
        TransitionPresetDescriptor(
            shape = SegueShape.BASS_SWAP,
            displayName = "Bass Swap",
            description = "Bass frequencies swap on the drop; smooth volume curves",
            isSupported = true
        ),
        TransitionPresetDescriptor(
            shape = SegueShape.THREE_BAND,
            displayName = "3-Band Blend",
            description = "Bass, mid, and high bands hand over in sequential stages",
            isSupported = true
        ),
        TransitionPresetDescriptor(
            shape = SegueShape.CUT_ON_BEAT,
            displayName = "Cut on Beat",
            description = "Instant hard cut aligned exactly to downbeat",
            isSupported = true
        ),
        TransitionPresetDescriptor(
            shape = SegueShape.FILTER_OPEN,
            displayName = "Filter Open",
            description = "Incoming track sweeps open from low-pass filter",
            isSupported = true
        ),
        TransitionPresetDescriptor(
            shape = SegueShape.RISE_OUT,
            displayName = "Filter Rise Out",
            description = "Outgoing track thins from low frequencies to high-pass tail",
            isSupported = true
        ),
        TransitionPresetDescriptor(
            shape = SegueShape.EVEN_FADE,
            displayName = "Smooth Fade",
            description = "Pure equal-power energy crossfade without frequency kill",
            isSupported = true
        ),
        TransitionPresetDescriptor(
            shape = SegueShape.SWEEP_OUT,
            displayName = "Resonant Sweep",
            description = "Filter frequency sweep with audible resonance peak",
            isSupported = true
        ),
        // Explicitly disabled experimental/future presets (Task 8 & ACC-33)
        TransitionPresetDescriptor(
            shape = SegueShape.ECHO_TAIL,
            displayName = "Echo Tail",
            description = "Repeats outgoing beats as an echo over incoming track",
            isSupported = false,
            disabledReason = "Echo loop-buffer pipeline pending engine Stage 10."
        ),
        TransitionPresetDescriptor(
            shape = SegueShape.WASH_OUT,
            displayName = "Reverb Wash",
            description = "Outgoing track dissolves into algorithmic room reverb",
            isSupported = false,
            disabledReason = "Room reverb processor disabled in Stage 08."
        ),
        TransitionPresetDescriptor(
            shape = SegueShape.ECHO_OUT,
            displayName = "Echo Throw",
            description = "Downbeat delay throw with feedback decay",
            isSupported = false,
            disabledReason = "Synchronized BPM delay throw disabled in Stage 08."
        )
    )
}

/**
 * Immutable UI state for the focused Transition Editor (Stage 08, Tasks 2–5).
 * Modifying the draft does not mutate the committed database (Contract D12).
 */
data class TransitionEditorState(
    val setId: String,
    val edgeId: String,
    val edgeRevision: Long,
    val projectRevision: Long,
    val outgoingTrack: TrackEntity,
    val incomingTrack: TrackEntity,
    val outgoingAnalysis: TrackAnalysis? = null,
    val incomingAnalysis: TrackAnalysis? = null,
    val outgoingAnalysisStatus: AnalysisStatus = AnalysisStatus.Idle,
    val incomingAnalysisStatus: AnalysisStatus = AnalysisStatus.Idle,
    val draftParams: TransitionOverrideParams,
    val initialParams: TransitionOverrideParams,
    val validationResult: TransitionResolutionResult,
    val isPreviewPlaying: Boolean = false,
    val isSaving: Boolean = false,
    val activeZoomFactor: Float = 1.0f,
    val isAdvancedEqExpanded: Boolean = false,
    val edgeOrigin: EdgeOrigin = EdgeOrigin.AUTO,
    val committedPlanHash: String = "",
    val showDirtyConfirmationDialog: Boolean = false
) {
    val isDirty: Boolean get() = draftParams != initialParams

    val isValid: Boolean get() = validationResult is TransitionResolutionResult.Success

    val validationErrors: List<String> get() = when (validationResult) {
        is TransitionResolutionResult.Success -> emptyList()
        is TransitionResolutionResult.Failure -> validationResult.humanReadableErrors
    }

    val currentPlanHash: String get() = when (validationResult) {
        is TransitionResolutionResult.Success -> validationResult.planHashSha256
        is TransitionResolutionResult.Failure -> ""
    }

    val effectiveSpanUs: Long get() = draftParams.spanUs ?: (
        if (draftParams.barCount != null && outgoingAnalysis != null && outgoingAnalysis.bpm > 0) {
            val barDurationSec = 240.0 / outgoingAnalysis.bpm
            (draftParams.barCount!! * barDurationSec * 1_000_000.0).toLong()
        } else {
            12_000_000L
        }
    )

    val effectiveShape: SegueShape get() = draftParams.shape ?: SegueShape.BASS_SWAP

    val isGridded: Boolean get() = (outgoingAnalysis?.steady == true || outgoingAnalysis?.head != null) &&
            (incomingAnalysis?.steady == true || incomingAnalysis?.head != null)
}
