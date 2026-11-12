/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.transition

import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.SegueSideFacts
import app.myspoti.automix.segue.SegueTuning
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionPlanAdapter
import app.myspoti.automix.segue.TransitionResolutionResult
import app.setflow.db.EdgeCompleteSnapshot
import app.setflow.db.EdgeDraftState
import app.setflow.db.InvalidPlanPersistenceException
import app.setflow.db.OverrideSnapshotEntity
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase

/**
 * Manages transition editing, draft resolution, previewing, and optimistic-locked persistence
 * for adjacent track pairs (Stage 07, STAGES/07_SAVED_TRANSITIONS.txt).
 *
 * Guarantees:
 * - Preview and save reference the exact same resolved plan hash at a given revision.
 * - Supports 4, 8, 16, and 32-bar plans with exact microsecond duration without auto-clamping.
 * - Enforces optimistic locking on save.
 * - Prevents background auto analysis from overwriting manual overrides.
 */
class TransitionOverrideManager(
    private val database: SetFlowProjectDatabase,
    private val diagnostics: DiagnosticsRingBuffer? = null,
    private val tuning: SegueTuning = SegueTuning()
) {

    /**
     * Derives an in-memory editor draft combining [AutoPlanSnapshotEntity] and [OverrideSnapshotEntity].
     */
    fun getDraft(edgeId: String): EdgeDraftState {
        return database.deriveEditorDraft(edgeId)
    }

    /**
     * Previews a transition override without mutating the database (D12).
     * Derives the exact canonical JSON and SHA-256 plan hash with DSP execution parity.
     */
    fun preview(
        edgeId: String,
        params: TransitionOverrideParams
    ): TransitionResolutionResult {
        val edgeSnap = database.getEdgeCompleteSnapshot(edgeId)
            ?: run {
                val err = "Edge not found: $edgeId"
                return TransitionResolutionResult.Failure(emptyList(), listOf(err))
            }

        val sideTracks = database.getEdgeSideTracks(edgeId)
            ?: run {
                val err = "Tracks not found for edge $edgeId"
                return TransitionResolutionResult.Failure(emptyList(), listOf(err))
            }

        val (outTrack, inTrack) = sideTracks
        val autoPlan = edgeSnap.autoPlan?.planCanonicalJson?.let {
            runCatching<SeguePlan> { SegueCodec.decode(it) }.getOrNull()
        }

        val outFacts = toSegueSideFacts(outTrack)
        val inFacts = toSegueSideFacts(inTrack)

        return TransitionPlanAdapter.resolve(
            autoPlan = autoPlan,
            override = params,
            outgoingFacts = outFacts,
            incomingFacts = inFacts,
            tuning = tuning
        )
    }

    /**
     * Previews an editor draft state without mutating the database.
     */
    fun preview(
        edgeId: String,
        draft: EdgeDraftState
    ): TransitionResolutionResult {
        return preview(edgeId, toOverrideParams(draft))
    }

    /**
     * Atomically validates and commits a user transition override into the database.
     * Enforces optimistic concurrency on [expectedEdgePlanRevision].
     */
    fun saveOverride(
        edgeId: String,
        params: TransitionOverrideParams,
        expectedEdgePlanRevision: Long,
        expectedProjectRevision: Long? = null
    ): EdgeCompleteSnapshot {
        val edgeSnap = database.getEdgeCompleteSnapshot(edgeId)
            ?: throw IllegalArgumentException("Edge not found: $edgeId")

        val sideTracks = database.getEdgeSideTracks(edgeId)
            ?: throw IllegalArgumentException("Tracks not found for edge $edgeId")

        val (outTrack, inTrack) = sideTracks
        val autoPlan = edgeSnap.autoPlan?.planCanonicalJson?.let {
            runCatching<SeguePlan> { SegueCodec.decode(it) }.getOrNull()
        }

        val outFacts = toSegueSideFacts(outTrack)
        val inFacts = toSegueSideFacts(inTrack)

        val resolution = TransitionPlanAdapter.resolve(
            autoPlan = autoPlan,
            override = params,
            outgoingFacts = outFacts,
            incomingFacts = inFacts,
            tuning = tuning
        )

        val success = when (resolution) {
            is TransitionResolutionResult.Success -> resolution
            is TransitionResolutionResult.Failure -> {
                val errorMsg = resolution.humanReadableErrors.joinToString("; ")
                diagnostics?.record(
                    category = "TransitionManager",
                    phase = SchedulerPhase.IDLE,
                    errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                    message = "Save rejected for edge $edgeId: $errorMsg"
                )
                throw InvalidPlanPersistenceException(
                    errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                    message = errorMsg,
                    violations = resolution.humanReadableErrors
                )
            }
        }

        val overrideSnapshot = toOverrideSnapshot(params, edgeId, expectedEdgePlanRevision + 1L)

        return database.saveEdgeOverrideAndResolvedPlan(
            edgeId = edgeId,
            expectedEdgePlanRevision = expectedEdgePlanRevision,
            overrideSnapshot = overrideSnapshot,
            canonicalSegueJson = success.canonicalJson,
            outgoingFacts = outFacts,
            incomingFacts = inFacts,
            expectedProjectRevision = expectedProjectRevision
        )
    }

    /**
     * Atomically validates and commits an [EdgeDraftState].
     */
    fun saveOverride(
        edgeId: String,
        draft: EdgeDraftState,
        expectedEdgePlanRevision: Long,
        expectedProjectRevision: Long? = null
    ): EdgeCompleteSnapshot {
        return saveOverride(edgeId, toOverrideParams(draft), expectedEdgePlanRevision, expectedProjectRevision)
    }

    /**
     * Explicitly resets a custom edge back to [app.setflow.db.EdgeOrigin.AUTO] (Task 5).
     */
    fun resetToAuto(
        edgeId: String,
        expectedEdgePlanRevision: Long? = null,
        expectedProjectRevision: Long? = null
    ): EdgeCompleteSnapshot {
        return database.resetEdgeToAuto(edgeId, expectedEdgePlanRevision, expectedProjectRevision)
    }

    fun toOverrideParams(draft: EdgeDraftState): TransitionOverrideParams = TransitionOverrideParams(
        exitAnchorUs = draft.exitAnchorUs,
        entryAnchorUs = draft.entryAnchorUs,
        spanUs = draft.spanUs,
        barCount = draft.barCount,
        pulseMode = draft.pulseMode?.let { mode ->
            PulseState.values().firstOrNull { it.wire.equals(mode, ignoreCase = true) || it.name.equals(mode, ignoreCase = true) }
        },
        plannedRatePpm = draft.plannedRatePpm.toLong(),
        shape = draft.presetId?.let { p ->
            SegueShape.values().firstOrNull { it.name.equals(p, ignoreCase = true) }
        },
        customLanesJson = draft.laneOverridesJson,
        userEditedFieldsBitset = draft.userEditedFieldsBitset
    )

    fun toOverrideParams(ov: OverrideSnapshotEntity): TransitionOverrideParams = TransitionOverrideParams(
        exitAnchorUs = ov.exitAnchorUs,
        entryAnchorUs = ov.entryAnchorUs,
        spanUs = ov.spanUs,
        barCount = ov.barCount,
        pulseMode = ov.pulseMode?.let { mode ->
            PulseState.values().firstOrNull { it.wire.equals(mode, ignoreCase = true) || it.name.equals(mode, ignoreCase = true) }
        },
        plannedRatePpm = ov.plannedRatePpm?.toLong(),
        shape = ov.presetId?.let { p ->
            SegueShape.values().firstOrNull { it.name.equals(p, ignoreCase = true) }
        },
        customLanesJson = ov.laneOverridesJson,
        userEditedFieldsBitset = ov.userEditedFieldsBitset
    )

    fun toOverrideSnapshot(params: TransitionOverrideParams, edgeId: String, revision: Long = 1L): OverrideSnapshotEntity =
        OverrideSnapshotEntity(
            edgeId = edgeId,
            overrideSchema = OverrideSnapshotEntity.CURRENT_OVERRIDE_SCHEMA_VERSION,
            revision = revision,
            exitAnchorUs = params.exitAnchorUs,
            entryAnchorUs = params.entryAnchorUs,
            spanUs = params.spanUs,
            barCount = params.barCount,
            pulseMode = params.pulseMode?.wire,
            plannedRatePpm = params.plannedRatePpm?.toInt(),
            laneOverridesJson = params.customLanesJson,
            presetId = params.shape?.name,
            userEditedFieldsBitset = params.userEditedFieldsBitset,
            editTimestampUtc = System.currentTimeMillis()
        )

    private fun toSegueSideFacts(track: TrackEntity): SegueSideFacts = SegueSideFacts(
        audioKey = track.audioFingerprint.orEmpty(),
        gridKey = track.contentFingerprint.ifBlank { track.trackId },
        durationUs = track.durationUs
    )
}
