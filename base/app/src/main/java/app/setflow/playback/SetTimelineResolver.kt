/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.SegueSideFacts
import app.myspoti.automix.segue.SegueTuning
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionPlanAdapter
import app.myspoti.automix.segue.TransitionResolutionResult
import app.setflow.db.EdgeCompleteSnapshot
import app.setflow.db.OccurrenceWithTrack
import app.setflow.db.ProjectDetailSnapshot
import app.setflow.domain.AudioErrorCode
import kotlin.math.max

/**
 * Metadata and global timeline mapping for an individual track occurrence in a set (Stage 10, Task 1).
 */
data class TimelineOccurrenceSlot(
    val occurrenceIndex: Int,
    val occurrenceId: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val durationUs: Long,
    val startGlobalUs: Long,
    val endGlobalUs: Long,
    val entryAnchorUs: Long = 0L,
    val exitAnchorGlobalUs: Long? = null
) {
    val effectiveGlobalDurationUs: Long get() = max(0L, endGlobalUs - startGlobalUs)
}

/**
 * Metadata and global timeline placement for an adjacent transition edge in a set (Stage 10, Task 1).
 */
data class TimelineEdgeSlot(
    val edgeIndex: Int,
    val edgeId: String,
    val outgoingOccurrenceId: String,
    val incomingOccurrenceId: String,
    val planRevision: Long,
    val planHashSha256: String,
    val overlapStartGlobalUs: Long,
    val overlapEndGlobalUs: Long,
    val exitAnchorUs: Long,
    val entryAnchorUs: Long,
    val spanUs: Long,
    val ratePpmB: Long = 1_000_000L,
    val seguePlan: SeguePlan? = null
) {
    val durationUs: Long get() = max(0L, overlapEndGlobalUs - overlapStartGlobalUs)
}

/**
 * Immutable authoritative whole-set timeline representation.
 */
data class SetTimeline(
    val projectId: String,
    val projectRevision: Long,
    val totalDurationUs: Long,
    val occurrenceSlots: List<TimelineOccurrenceSlot>,
    val edgeSlots: List<TimelineEdgeSlot>
) {
    /**
     * Resolves a global microsecond timestamp into the active occurrence and its track-local position.
     */
    fun resolveGlobalPosition(globalUs: Long): Pair<TimelineOccurrenceSlot, Long>? {
        if (occurrenceSlots.isEmpty()) return null
        val clamped = globalUs.coerceIn(0L, totalDurationUs)
        // Find the occurrence whose audible window covers clamped
        val slot = occurrenceSlots.firstOrNull { clamped >= it.startGlobalUs && clamped <= it.endGlobalUs }
            ?: occurrenceSlots.last()
        // Map global time back to track-local time
        val localPosUs = (clamped - slot.startGlobalUs + slot.entryAnchorUs).coerceIn(0L, slot.durationUs)
        return slot to localPosUs
    }

    /**
     * Finds the active transition edge (if any) currently executing at [globalUs].
     */
    fun findActiveEdge(globalUs: Long): TimelineEdgeSlot? {
        return edgeSlots.firstOrNull { globalUs >= it.overlapStartGlobalUs && globalUs < it.overlapEndGlobalUs }
    }
}

/**
 * Result of resolving a whole-set timeline.
 */
sealed interface SetTimelineResolution {
    data class Success(val timeline: SetTimeline) : SetTimelineResolution
    data class Error(val reason: String, val violationCode: AudioErrorCode) : SetTimelineResolution
}

/**
 * Authoritative set timeline resolver computing global sample-accurate timelines,
 * accounting for transition overlaps, and strictly rejecting triple overlaps (Stage 10 Task 1).
 */
object SetTimelineResolver {

    fun resolve(
        detail: ProjectDetailSnapshot,
        tuning: SegueTuning = SegueTuning()
    ): SetTimelineResolution {
        val occurrences = detail.occurrences.sortedBy { it.occurrence.positionIndex }
        if (occurrences.isEmpty()) {
            return SetTimelineResolution.Success(
                SetTimeline(
                    projectId = detail.project.projectId,
                    projectRevision = detail.project.editRevision,
                    totalDurationUs = 0L,
                    occurrenceSlots = emptyList(),
                    edgeSlots = emptyList()
                )
            )
        }

        if (occurrences.size == 1) {
            val occ = occurrences.first()
            val dur = occ.track.durationUs
            val slot = TimelineOccurrenceSlot(
                occurrenceIndex = 0,
                occurrenceId = occ.occurrence.occurrenceId,
                trackId = occ.track.trackId,
                title = occ.track.title,
                artist = occ.track.artist,
                durationUs = dur,
                startGlobalUs = 0L,
                endGlobalUs = dur,
                entryAnchorUs = 0L,
                exitAnchorGlobalUs = null
            )
            return SetTimelineResolution.Success(
                SetTimeline(
                    projectId = detail.project.projectId,
                    projectRevision = detail.project.editRevision,
                    totalDurationUs = dur,
                    occurrenceSlots = listOf(slot),
                    edgeSlots = emptyList()
                )
            )
        }

        val occSlots = mutableListOf<TimelineOccurrenceSlot>()
        val edgeSlots = mutableListOf<TimelineEdgeSlot>()

        var currentBaseGlobalUs = 0L
        var previousOverlapEndGlobalUs = -1L

        for (i in 0 until occurrences.size) {
            val occ = occurrences[i]
            val trackDur = occ.track.durationUs
            if (trackDur <= 0L) {
                return SetTimelineResolution.Error(
                    reason = "Track ${occ.track.title} (${occ.track.trackId}) has non-positive duration $trackDur",
                    violationCode = AudioErrorCode.A12_TIMELINE_OVERFLOW
                )
            }

            if (i < occurrences.size - 1) {
                val nextOcc = occurrences[i + 1]
                val edgeSnap = detail.edges.firstOrNull {
                    it.edge.outgoingOccurrenceId == occ.occurrence.occurrenceId &&
                    it.edge.incomingOccurrenceId == nextOcc.occurrence.occurrenceId
                } ?: return SetTimelineResolution.Error(
                    reason = "Missing transition edge between occurrence ${occ.occurrence.occurrenceId} and ${nextOcc.occurrence.occurrenceId}",
                    violationCode = AudioErrorCode.A05_SOURCE_CHANGED
                )

                // Resolve effective parameters from override or resolvedPlan or defaults
                val (exitAnchorUs, entryAnchorUs, spanUs, planHash, seguePlan) = resolveEdgeParams(edgeSnap, occ, nextOcc, tuning)

                if (exitAnchorUs + spanUs > trackDur) {
                    return SetTimelineResolution.Error(
                        reason = "Transition exitAnchor ($exitAnchorUs us) + span ($spanUs us) exceeds track duration ($trackDur us) on track ${occ.track.title}",
                        violationCode = AudioErrorCode.A12_TIMELINE_OVERFLOW
                    )
                }

                if (entryAnchorUs + spanUs > nextOcc.track.durationUs) {
                    return SetTimelineResolution.Error(
                        reason = "Transition entryAnchor ($entryAnchorUs us) + span ($spanUs us) exceeds incoming track duration (${nextOcc.track.durationUs} us) on track ${nextOcc.track.title}",
                        violationCode = AudioErrorCode.A12_TIMELINE_OVERFLOW
                    )
                }

                val overlapStartGlobalUs = currentBaseGlobalUs + exitAnchorUs
                val overlapEndGlobalUs = overlapStartGlobalUs + spanUs

                // TRIPLE OVERLAP CHECK (Task 1): Next transition must not start before previous transition ends
                if (previousOverlapEndGlobalUs > 0L && overlapStartGlobalUs < previousOverlapEndGlobalUs) {
                    return SetTimelineResolution.Error(
                        reason = "Unsupported triple overlap: transition at global ${overlapStartGlobalUs}us begins before prior transition finishes at ${previousOverlapEndGlobalUs}us",
                        violationCode = AudioErrorCode.A12_TIMELINE_OVERFLOW
                    )
                }

                val slotStartGlobalUs = if (i == 0) 0L else currentBaseGlobalUs
                val slotEndGlobalUs = overlapEndGlobalUs

                occSlots.add(
                    TimelineOccurrenceSlot(
                        occurrenceIndex = i,
                        occurrenceId = occ.occurrence.occurrenceId,
                        trackId = occ.track.trackId,
                        title = occ.track.title,
                        artist = occ.track.artist,
                        durationUs = trackDur,
                        startGlobalUs = slotStartGlobalUs,
                        endGlobalUs = slotEndGlobalUs,
                        entryAnchorUs = if (i == 0) 0L else edgeSlots.last().entryAnchorUs,
                        exitAnchorGlobalUs = overlapStartGlobalUs
                    )
                )

                edgeSlots.add(
                    TimelineEdgeSlot(
                        edgeIndex = i,
                        edgeId = edgeSnap.edge.edgeId,
                        outgoingOccurrenceId = occ.occurrence.occurrenceId,
                        incomingOccurrenceId = nextOcc.occurrence.occurrenceId,
                        planRevision = edgeSnap.edge.planRevision,
                        planHashSha256 = planHash,
                        overlapStartGlobalUs = overlapStartGlobalUs,
                        overlapEndGlobalUs = overlapEndGlobalUs,
                        exitAnchorUs = exitAnchorUs,
                        entryAnchorUs = entryAnchorUs,
                        spanUs = spanUs,
                        ratePpmB = seguePlan?.incoming?.ratePpm ?: 1_000_000L,
                        seguePlan = seguePlan
                    )
                )

                previousOverlapEndGlobalUs = overlapEndGlobalUs
                // Set the base coordinate for the incoming track so its entryAnchor lands at overlapStartGlobalUs
                currentBaseGlobalUs = overlapStartGlobalUs - entryAnchorUs

            } else {
                // Final track in the set
                val slotStartGlobalUs = currentBaseGlobalUs
                val slotEndGlobalUs = currentBaseGlobalUs + trackDur

                // Triple overlap check against prior edge
                if (previousOverlapEndGlobalUs > 0L && slotEndGlobalUs < previousOverlapEndGlobalUs) {
                    return SetTimelineResolution.Error(
                        reason = "Final track ends before prior transition finishes",
                        violationCode = AudioErrorCode.A12_TIMELINE_OVERFLOW
                    )
                }

                occSlots.add(
                    TimelineOccurrenceSlot(
                        occurrenceIndex = i,
                        occurrenceId = occ.occurrence.occurrenceId,
                        trackId = occ.track.trackId,
                        title = occ.track.title,
                        artist = occ.track.artist,
                        durationUs = trackDur,
                        startGlobalUs = slotStartGlobalUs,
                        endGlobalUs = slotEndGlobalUs,
                        entryAnchorUs = edgeSlots.lastOrNull()?.entryAnchorUs ?: 0L,
                        exitAnchorGlobalUs = null
                    )
                )
            }
        }

        val totalDurationUs = occSlots.last().endGlobalUs
        return SetTimelineResolution.Success(
            SetTimeline(
                projectId = detail.project.projectId,
                projectRevision = detail.project.editRevision,
                totalDurationUs = totalDurationUs,
                occurrenceSlots = occSlots,
                edgeSlots = edgeSlots
            )
        )
    }

    private data class EdgeParams(
        val exitAnchorUs: Long,
        val entryAnchorUs: Long,
        val spanUs: Long,
        val planHash: String,
        val seguePlan: SeguePlan?
    )

    private fun resolveEdgeParams(
        edgeSnap: EdgeCompleteSnapshot,
        occA: OccurrenceWithTrack,
        occB: OccurrenceWithTrack,
        tuning: SegueTuning
    ): EdgeParams {
        val resolved = edgeSnap.resolvedPlan?.canonicalSegueJson?.let {
            runCatching { SegueCodec.decode(it) }.getOrNull()
        }
        val auto = edgeSnap.autoPlan?.planCanonicalJson?.let {
            runCatching { SegueCodec.decode(it) }.getOrNull()
        }

        val plan = resolved ?: auto
        if (plan != null) {
            val hash = edgeSnap.resolvedPlan?.planHashSha256
                ?: SegueCodec.fingerprint(plan)
            return EdgeParams(
                exitAnchorUs = plan.outgoing.anchorUs,
                entryAnchorUs = plan.incoming.anchorUs,
                spanUs = plan.span.lengthUs,
                planHash = hash,
                seguePlan = plan
            )
        }

        val override = edgeSnap.overridePlan
        val outDur = occA.track.durationUs
        val defaultSpan = override?.spanUs ?: 8_000_000L
        val exit = override?.exitAnchorUs ?: (outDur - defaultSpan).coerceAtLeast(0L)
        val entry = override?.entryAnchorUs ?: 0L
        val span = defaultSpan

        val outFacts = SegueSideFacts(durationUs = outDur, audioKey = occA.track.trackId, gridKey = null)
        val inFacts = SegueSideFacts(durationUs = occB.track.durationUs, audioKey = occB.track.trackId, gridKey = null)

        val res = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(
                exitAnchorUs = exit,
                entryAnchorUs = entry,
                spanUs = span,
                shape = SegueShape.EVEN_FADE
            ),
            outgoingFacts = outFacts,
            incomingFacts = inFacts,
            tuning = tuning
        )

        return when (res) {
            is TransitionResolutionResult.Success -> EdgeParams(
                exitAnchorUs = res.plan.outgoing.anchorUs,
                entryAnchorUs = res.plan.incoming.anchorUs,
                spanUs = res.plan.span.lengthUs,
                planHash = res.planHashSha256,
                seguePlan = res.plan
            )
            is TransitionResolutionResult.Failure -> EdgeParams(
                exitAnchorUs = exit,
                entryAnchorUs = entry,
                spanUs = span,
                planHash = "fallback_${edgeSnap.edge.edgeId}",
                seguePlan = null
            )
        }
    }
}
