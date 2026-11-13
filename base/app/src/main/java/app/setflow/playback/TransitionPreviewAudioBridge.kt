/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.SegueDeck
import app.myspoti.automix.segue.SeguePlan
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.AudioGraphOwner
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import java.io.Closeable
import kotlin.math.max

/**
 * Audio bridge managing draft transition pair preview playback (Stage 09, STAGES/09_DSP_PREVIEW.txt).
 *
 * Guarantees:
 * 1. Single audio graph owner: If set playback is active, suspends set playback cleanly and acquires
 *    [AudioGraphOwner.TRANSITION_PREVIEW]. Restores set playback on preview exit (Task 3).
 * 2. Immutable draft plan execution: Runs the exact draft SeguePlan and parameters, never mutating the database (Task 3, F05).
 * 3. Prebuffer without clicks: Starts 5–15 seconds (default 8s) before the cue point to avoid boundary clicks and allow decoder warm-up (Task 4).
 * 4. Effect session isolation & zero leakage: Fresh SegueDeck per session; Stop/Skip/Cancel immediately disarms DSP states (Task 7, F08).
 * 5. Full metrics recording: Captures peak, RMS, headroom limiter engagements, and frame alignment (Task 8).
 */
class TransitionPreviewAudioBridge(
    val playbackController: TwoDeckPlaybackController,
    private val diagnostics: DiagnosticsRingBuffer = DiagnosticsRingBuffer()
) : Closeable {

    @Volatile
    var isPreviewActive: Boolean = false
        private set

    @Volatile
    var activeDraftBinding: ArmedTransitionBinding? = null
        private set

    @Volatile
    var lastPreviewMetrics: DualDeckRenderMetrics? = null
        private set

    var hardwareBridgeProvider: (() -> AndroidMedia3DualDeckBridge?)? = null

    /**
     * Starts previewing [draftPlan] between [outgoingTrack] and [incomingTrack].
     * Suspends any active set playback session.
     *
     * @param preRollUs Lead-in window before exitAnchorUs (5 to 15 seconds; default 8s).
     * @param sourceAProvider Optional PCM provider for Track A (used in testing or when pre-decoded).
     * @param sourceBProvider Optional PCM provider for Track B (used in testing or when pre-decoded).
     */
    @Synchronized
    fun startPreview(
        projectId: String,
        projectRevision: Long,
        edgeId: String,
        edgePlanRevision: Long,
        outgoingOccurrenceId: String,
        incomingOccurrenceId: String,
        outgoingTrack: TrackEntity,
        incomingTrack: TrackEntity,
        draftPlan: SeguePlan,
        planHashSha256: String,
        preRollUs: Long = 8_000_000L,
        sourceAProvider: (() -> DecodedPcmBuffer)? = null,
        sourceBProvider: (() -> DecodedPcmBuffer)? = null
    ): Boolean {
        // Enforce 5-15s pre-roll range for boundary click safety and decoder warm-up (Task 4)
        val safePreRollUs = preRollUs.coerceIn(5_000_000L, 15_000_000L)

        // Stop any prior preview session
        stopPreview()

        val binding = ArmedTransitionBinding(
            projectId = projectId,
            projectRevision = projectRevision,
            edgeId = edgeId,
            edgePlanRevision = edgePlanRevision,
            outgoingOccurrenceId = outgoingOccurrenceId,
            incomingOccurrenceId = incomingOccurrenceId,
            outgoingTrackId = outgoingTrack.trackId,
            incomingTrackId = incomingTrack.trackId,
            audioKeyA = outgoingTrack.audioFingerprint ?: outgoingTrack.contentFingerprint,
            audioKeyB = incomingTrack.audioFingerprint ?: incomingTrack.contentFingerprint,
            exitAnchorUs = draftPlan.outgoing.anchorUs,
            entryAnchorUs = draftPlan.incoming.anchorUs,
            spanUs = draftPlan.span.lengthUs,
            ratePpmB = draftPlan.incoming.ratePpm,
            planHashSha256 = planHashSha256,
            seguePlan = draftPlan
        )

        val initialPosA = (draftPlan.outgoing.anchorUs - safePreRollUs).coerceAtLeast(0L)

        // Single audio owner arbitration: suspend set playback if running (Task 3)
        playbackController.prepareTransition(
            binding = binding,
            owner = AudioGraphOwner.TRANSITION_PREVIEW,
            initialPositionUsA = initialPosA
        )

        activeDraftBinding = binding
        isPreviewActive = true

        diagnostics.record(
            category = "PreviewBridge",
            phase = SchedulerPhase.PREPARING,
            message = "Started transition preview edge=$edgeId planHash=${planHashSha256.take(8)} preRoll=${safePreRollUs / 1_000_000L}s"
        )

        // If hardware bridge is available (Android live playback)
        val hwBridge = hardwareBridgeProvider?.invoke()
        if (hwBridge != null && outgoingTrack.contentUri.isNotBlank() && incomingTrack.contentUri.isNotBlank()) {
            hwBridge.disarmSeguePlan()
            hwBridge.prepareOutgoingDeckA(outgoingTrack.contentUri, initialPosA / 1_000L)
            hwBridge.prebufferIncomingDeckB(incomingTrack.contentUri, draftPlan.incoming.anchorUs / 1_000L, draftPlan.incoming.ratePpm)
            hwBridge.armSeguePlan(draftPlan)
            hwBridge.playerA.play()
        }

        // If PCM providers are available, render in-memory to compute deterministic metrics & safety verification
        if (sourceAProvider != null && sourceBProvider != null) {
            val result = DualDeckPcmEngine.renderTransitionWindow(
                binding = binding,
                sourceAProvider = sourceAProvider,
                sourceBProvider = sourceBProvider,
                preRollUs = safePreRollUs,
                postRollUs = 2_000_000L,
                useSegueDspIfAvailable = true
            )
            lastPreviewMetrics = result.metrics
        }

        return true
    }

    /**
     * Immediately stops preview playback, disarms DSP stages, and restores any suspended set playback session (Task 3 & 7).
     */
    @Synchronized
    fun stopPreview() {
        if (!isPreviewActive && activeDraftBinding == null) return

        diagnostics.record(
            category = "PreviewBridge",
            phase = SchedulerPhase.STOPPED,
            message = "Stopping preview and disarming DSP sessions for edge=${activeDraftBinding?.edgeId}"
        )

        val hwBridge = hardwareBridgeProvider?.invoke()
        hwBridge?.let {
            it.disarmSeguePlan()
            it.playerA.pause()
            it.playerB.pause()
        }

        isPreviewActive = false
        activeDraftBinding = null

        // Restore any suspended SET_PLAYBACK session cleanly (Task 3)
        playbackController.stopPreviewAndRestoreSuspendedSet()
    }

    override fun close() {
        stopPreview()
    }
}
