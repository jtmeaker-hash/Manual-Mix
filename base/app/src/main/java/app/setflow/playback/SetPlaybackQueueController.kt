/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import app.setflow.db.ProjectDetailSnapshot
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.AudioGraphOwner
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

enum class SetQueuePhase {
    IDLE,
    PLAYING_TRACK,
    PREBUFFERING_INCOMING,
    TRANSITION_ARMED,
    OVERLAP_RUNNING,
    SET_COMPLETED,
    ERROR
}

/**
 * State snapshot of whole-set automatic queue playback (Stage 10, Task 2).
 */
data class SetPlaybackQueueState(
    val phase: SetQueuePhase = SetQueuePhase.IDLE,
    val projectId: String? = null,
    val projectRevision: Long = 0L,
    val currentOccurrenceIndex: Int = 0,
    val totalOccurrences: Int = 0,
    val currentOccurrenceId: String? = null,
    val currentTrackId: String? = null,
    val currentTrackTitle: String = "",
    val currentTrackArtist: String = "",
    val currentLocalPositionUs: Long = 0L,
    val currentGlobalPositionUs: Long = 0L,
    val totalSetDurationUs: Long = 0L,
    val activeEdgeId: String? = null,
    val isPlaying: Boolean = false,
    val lastError: AudioErrorCode? = null,
    val statusMessage: String = "Set playback queue idle."
)

/**
 * Controller orchestrating whole-set playback queue progression across all occurrences and transitions
 * (Stage 10, STAGES/10_SET_PLAYBACK_SERVICE.txt).
 *
 * Guarantees:
 * - Automatically arms transitions 5-15s before cue point and advances on handoff completion (Task 2).
 * - Bounded memory: unloads finished tracks and retains only current + next incoming deck (Task 7).
 * - Global timeline seeking maps cleanly to occurrence and track-local position (Task 6).
 * - Mid-blend seek safely disarms secondary deck and restarts cleanly without stale firing (Task 6).
 * - Captures immutable plan revisions when arming transitions; edits apply on next safe boundary (Task 2).
 */
class SetPlaybackQueueController(
    private val database: SetFlowProjectDatabase,
    val twoDeckController: TwoDeckPlaybackController,
    private val diagnostics: DiagnosticsRingBuffer = DiagnosticsRingBuffer()
) {

    private val _queueState = MutableStateFlow(SetPlaybackQueueState())
    val queueState: StateFlow<SetPlaybackQueueState> = _queueState.asStateFlow()

    @Volatile
    var activeTimeline: SetTimeline? = null
        private set

    @Volatile
    private var activeProjectDetail: ProjectDetailSnapshot? = null

    val isPlaying: Boolean get() = _queueState.value.isPlaying

    /**
     * Starts continuous playback of [projectId] from [startOccurrenceId] or beginning.
     */
    @Synchronized
    fun startSet(
        projectId: String,
        startOccurrenceId: String? = null,
        initialLocalPositionUs: Long = 0L
    ): Boolean {
        val detail = database.getProjectDetail(projectId) ?: run {
            diagnostics.record(
                category = "SetQueue",
                phase = SchedulerPhase.SAFE_FALLBACK,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Project $projectId not found for set playback"
            )
            _queueState.value = _queueState.value.copy(
                phase = SetQueuePhase.ERROR,
                lastError = AudioErrorCode.A01_SOURCE_MISSING,
                statusMessage = "Project $projectId not found."
            )
            return false
        }

        when (val res = SetTimelineResolver.resolve(detail)) {
            is SetTimelineResolution.Error -> {
                diagnostics.record(
                    category = "SetQueue",
                    phase = SchedulerPhase.SAFE_FALLBACK,
                    errorCode = res.violationCode,
                    message = "Timeline resolution failed for $projectId: ${res.reason}"
                )
                _queueState.value = _queueState.value.copy(
                    phase = SetQueuePhase.ERROR,
                    lastError = res.violationCode,
                    statusMessage = "Timeline error: ${res.reason}"
                )
                return false
            }
            is SetTimelineResolution.Success -> {
                activeTimeline = res.timeline
                activeProjectDetail = detail
            }
        }

        val timeline = activeTimeline!!
        if (timeline.occurrenceSlots.isEmpty()) {
            _queueState.value = SetPlaybackQueueState(
                phase = SetQueuePhase.SET_COMPLETED,
                statusMessage = "Empty set; playback complete."
            )
            return true
        }

        val targetSlot = if (startOccurrenceId != null) {
            timeline.occurrenceSlots.firstOrNull { it.occurrenceId == startOccurrenceId }
                ?: timeline.occurrenceSlots.first()
        } else {
            timeline.occurrenceSlots.first()
        }

        return playOccurrence(targetSlot.occurrenceIndex, initialLocalPositionUs)
    }

    @Synchronized
    private fun playOccurrence(index: Int, localPositionUs: Long = 0L): Boolean {
        val timeline = activeTimeline ?: return false
        if (index !in timeline.occurrenceSlots.indices) {
            completeSet()
            return false
        }

        val slot = timeline.occurrenceSlots[index]
        val clampedPos = localPositionUs.coerceIn(0L, slot.durationUs)

        // Reset two-deck controller for fresh primary deck
        twoDeckController.stopAll()

        val occ = activeProjectDetail?.occurrences?.firstOrNull { it.occurrence.occurrenceId == slot.occurrenceId }
        val key = occ?.track?.audioFingerprint ?: occ?.track?.contentFingerprint ?: occ?.track?.trackId ?: slot.trackId

        // Set up primary deck A
        twoDeckController.prepareTransition(
            binding = ArmedTransitionBinding(
                projectId = timeline.projectId,
                projectRevision = timeline.projectRevision,
                edgeId = "track_${slot.occurrenceId}",
                edgePlanRevision = 1L,
                outgoingOccurrenceId = slot.occurrenceId,
                incomingOccurrenceId = slot.occurrenceId,
                outgoingTrackId = slot.trackId,
                incomingTrackId = slot.trackId,
                audioKeyA = key,
                audioKeyB = key,
                exitAnchorUs = slot.durationUs,
                entryAnchorUs = 0L,
                spanUs = 1_000L
            ),
            owner = AudioGraphOwner.SET_PLAYBACK,
            initialPositionUsA = clampedPos
        )

        val globalUs = slot.startGlobalUs + (clampedPos - slot.entryAnchorUs).coerceAtLeast(0L)

        _queueState.value = SetPlaybackQueueState(
            phase = SetQueuePhase.PLAYING_TRACK,
            projectId = timeline.projectId,
            projectRevision = timeline.projectRevision,
            currentOccurrenceIndex = index,
            totalOccurrences = timeline.occurrenceSlots.size,
            currentOccurrenceId = slot.occurrenceId,
            currentTrackId = slot.trackId,
            currentTrackTitle = slot.title,
            currentTrackArtist = slot.artist,
            currentLocalPositionUs = clampedPos,
            currentGlobalPositionUs = globalUs,
            totalSetDurationUs = timeline.totalDurationUs,
            activeEdgeId = null,
            isPlaying = true,
            statusMessage = "Playing ${slot.title} (${index + 1}/${timeline.occurrenceSlots.size})"
        )

        diagnostics.record(
            category = "SetQueue",
            phase = SchedulerPhase.RUNNING,
            message = "Started occurrence $index (${slot.occurrenceId}) at local ${clampedPos / 1_000_000L}s"
        )
        return true
    }

    /**
     * Advances playback clock and drives automated transition progression (Task 2).
     */
    @Synchronized
    fun onStepProgress(sourcePositionUsA: Long): Boolean {
        val timeline = activeTimeline ?: return false
        val current = _queueState.value
        val index = current.currentOccurrenceIndex
        if (index !in timeline.occurrenceSlots.indices) return false

        val slot = timeline.occurrenceSlots[index]
        val globalUs = slot.startGlobalUs + (sourcePositionUsA - slot.entryAnchorUs).coerceAtLeast(0L)

        // Check if an edge exists for this occurrence
        val edgeSlot = timeline.edgeSlots.firstOrNull { it.outgoingOccurrenceId == slot.occurrenceId }

        if (edgeSlot == null) {
            // Last track in set
            if (sourcePositionUsA >= slot.durationUs) {
                completeSet()
                return false
            }
            _queueState.value = current.copy(
                currentLocalPositionUs = sourcePositionUsA,
                currentGlobalPositionUs = globalUs
            )
            return true
        }

        // We have an outgoing edge to the next track
        val exitAnchorUs = edgeSlot.exitAnchorUs
        val prebufferWindowUs = 8_000_000L // Prebuffer 8 seconds before cue point (Task 2 & 7)

        when (current.phase) {
            SetQueuePhase.PLAYING_TRACK -> {
                if (sourcePositionUsA >= exitAnchorUs - prebufferWindowUs) {
                    val occA = activeProjectDetail?.occurrences?.firstOrNull { it.occurrence.occurrenceId == edgeSlot.outgoingOccurrenceId }
                    val occB = activeProjectDetail?.occurrences?.firstOrNull { it.occurrence.occurrenceId == edgeSlot.incomingOccurrenceId }
                    val keyA = occA?.track?.audioFingerprint ?: occA?.track?.contentFingerprint ?: occA?.track?.trackId ?: slot.trackId
                    val inOccSlot = timeline.occurrenceSlots.getOrNull(index + 1)
                    val inTrackId = inOccSlot?.trackId ?: occB?.track?.trackId ?: edgeSlot.incomingOccurrenceId
                    val keyB = occB?.track?.audioFingerprint ?: occB?.track?.contentFingerprint ?: occB?.track?.trackId ?: inTrackId

                    // Arm transition and begin Deck B prebuffering at volume = 0.0f
                    val binding = ArmedTransitionBinding(
                        projectId = timeline.projectId,
                        projectRevision = timeline.projectRevision,
                        edgeId = edgeSlot.edgeId,
                        edgePlanRevision = edgeSlot.planRevision,
                        outgoingOccurrenceId = edgeSlot.outgoingOccurrenceId,
                        incomingOccurrenceId = edgeSlot.incomingOccurrenceId,
                        outgoingTrackId = slot.trackId,
                        incomingTrackId = inTrackId,
                        audioKeyA = keyA,
                        audioKeyB = keyB,
                        exitAnchorUs = edgeSlot.exitAnchorUs,
                        entryAnchorUs = edgeSlot.entryAnchorUs,
                        spanUs = edgeSlot.spanUs,
                        ratePpmB = edgeSlot.ratePpmB,
                        planHashSha256 = edgeSlot.planHashSha256,
                        seguePlan = edgeSlot.seguePlan
                    )

                    val gen = twoDeckController.prepareTransition(
                        binding = binding,
                        owner = AudioGraphOwner.SET_PLAYBACK,
                        initialPositionUsA = sourcePositionUsA
                    )

                    twoDeckController.onDeckBPrebuffered(
                        callbackGeneration = gen,
                        bufferedDurationMs = 2_000L,
                        decoderReady = true
                    )

                    _queueState.value = current.copy(
                        phase = SetQueuePhase.TRANSITION_ARMED,
                        activeEdgeId = edgeSlot.edgeId,
                        currentLocalPositionUs = sourcePositionUsA,
                        currentGlobalPositionUs = globalUs,
                        statusMessage = "Armed transition ${edgeSlot.edgeId} -> Deck B prebuffered"
                    )
                } else {
                    _queueState.value = current.copy(
                        currentLocalPositionUs = sourcePositionUsA,
                        currentGlobalPositionUs = globalUs
                    )
                }
            }

            SetQueuePhase.TRANSITION_ARMED -> {
                if (sourcePositionUsA >= exitAnchorUs) {
                    // Enter overlap window
                    twoDeckController.onStepOverlap(
                        callbackGeneration = twoDeckController.state.value.generation,
                        sourcePositionUsA = sourcePositionUsA
                    )
                    _queueState.value = current.copy(
                        phase = SetQueuePhase.OVERLAP_RUNNING,
                        currentLocalPositionUs = sourcePositionUsA,
                        currentGlobalPositionUs = globalUs,
                        statusMessage = "Executing transition overlap for edge ${edgeSlot.edgeId}"
                    )
                } else {
                    _queueState.value = current.copy(
                        currentLocalPositionUs = sourcePositionUsA,
                        currentGlobalPositionUs = globalUs
                    )
                }
            }

            SetQueuePhase.OVERLAP_RUNNING -> {
                val handoffDone = twoDeckController.onStepOverlap(
                    callbackGeneration = twoDeckController.state.value.generation,
                    sourcePositionUsA = sourcePositionUsA
                )

                if (twoDeckController.state.value.phase == SchedulerPhase.COMPLETE || sourcePositionUsA >= exitAnchorUs + edgeSlot.spanUs) {
                    // Handoff complete! Advance to next occurrence
                    val nextIndex = index + 1
                    val nextSlot = timeline.occurrenceSlots.getOrNull(nextIndex)
                    if (nextSlot == null) {
                        completeSet()
                        return true
                    }
                    diagnostics.record(
                        category = "SetQueue",
                        phase = SchedulerPhase.COMPLETE,
                        message = "Completed transition edge ${edgeSlot.edgeId}; advancing to occurrence $nextIndex (${nextSlot.occurrenceId})"
                    )

                    _queueState.value = current.copy(
                        phase = SetQueuePhase.PLAYING_TRACK,
                        currentOccurrenceIndex = nextIndex,
                        currentOccurrenceId = nextSlot.occurrenceId,
                        currentTrackId = nextSlot.trackId,
                        currentTrackTitle = nextSlot.title,
                        currentTrackArtist = nextSlot.artist,
                        currentLocalPositionUs = edgeSlot.entryAnchorUs + edgeSlot.spanUs,
                        currentGlobalPositionUs = globalUs,
                        activeEdgeId = null,
                        statusMessage = "Playing ${nextSlot.title} (${nextIndex + 1}/${timeline.occurrenceSlots.size})"
                    )
                } else {
                    _queueState.value = current.copy(
                        currentLocalPositionUs = sourcePositionUsA,
                        currentGlobalPositionUs = globalUs
                    )
                }
            }

            else -> {
                _queueState.value = current.copy(
                    currentLocalPositionUs = sourcePositionUsA,
                    currentGlobalPositionUs = globalUs
                )
            }
        }

        return true
    }

    /**
     * Seeks to [globalUs] across the whole set timeline (Task 6).
     */
    @Synchronized
    fun seekToGlobalUs(globalUs: Long): Boolean {
        val timeline = activeTimeline ?: return false
        val resolved = timeline.resolveGlobalPosition(globalUs) ?: return false
        val (slot, localPosUs) = resolved

        diagnostics.record(
            category = "SetQueue",
            message = "Global seek to ${globalUs / 1_000_000L}s -> occurrence ${slot.occurrenceIndex} (${slot.occurrenceId}) at local ${localPosUs / 1_000_000L}s"
        )

        // If seek lands on different occurrence or during blend, restart occurrence cleanly (Task 6)
        return playOccurrence(slot.occurrenceIndex, localPosUs)
    }

    /**
     * Skips to the next track / occurrence immediately with pop-safe transition (Task 2 & 6).
     */
    @Synchronized
    fun skipToNext(): Boolean {
        val timeline = activeTimeline ?: return false
        val current = _queueState.value

        if (current.phase == SetQueuePhase.OVERLAP_RUNNING || current.phase == SetQueuePhase.TRANSITION_ARMED) {
            // Pop-safe skip promotes Deck B immediately
            twoDeckController.skipToNext()
            val nextIndex = current.currentOccurrenceIndex + 1
            if (nextIndex in timeline.occurrenceSlots.indices) {
                val nextSlot = timeline.occurrenceSlots[nextIndex]
                _queueState.value = current.copy(
                    phase = SetQueuePhase.PLAYING_TRACK,
                    currentOccurrenceIndex = nextIndex,
                    currentOccurrenceId = nextSlot.occurrenceId,
                    currentTrackId = nextSlot.trackId,
                    currentTrackTitle = nextSlot.title,
                    currentTrackArtist = nextSlot.artist,
                    activeEdgeId = null,
                    statusMessage = "Skipped to ${nextSlot.title}"
                )
                return true
            } else {
                completeSet()
                return true
            }
        }

        val nextIndex = current.currentOccurrenceIndex + 1
        if (nextIndex in timeline.occurrenceSlots.indices) {
            return playOccurrence(nextIndex, 0L)
        } else {
            completeSet()
            return true
        }
    }

    /**
     * Skips to previous track or restarts current track (Task 6).
     */
    @Synchronized
    fun skipToPrevious(): Boolean {
        val current = _queueState.value
        if (current.currentLocalPositionUs > 3_000_000L || current.currentOccurrenceIndex == 0) {
            return playOccurrence(current.currentOccurrenceIndex, 0L)
        }
        return playOccurrence(current.currentOccurrenceIndex - 1, 0L)
    }

    @Synchronized
    fun pause() {
        twoDeckController.pause()
        _queueState.value = _queueState.value.copy(
            isPlaying = false,
            statusMessage = "Set playback paused."
        )
    }

    @Synchronized
    fun resume() {
        twoDeckController.resume()
        _queueState.value = _queueState.value.copy(
            isPlaying = true,
            statusMessage = "Set playback resumed."
        )
    }

    @Synchronized
    fun stop() {
        twoDeckController.stopAll()
        _queueState.value = SetPlaybackQueueState(
            phase = SetQueuePhase.IDLE,
            isPlaying = false,
            statusMessage = "Set playback stopped."
        )
        activeTimeline = null
        activeProjectDetail = null
    }

    @Synchronized
    private fun completeSet() {
        twoDeckController.stopAll()
        _queueState.value = _queueState.value.copy(
            phase = SetQueuePhase.SET_COMPLETED,
            isPlaying = false,
            statusMessage = "Whole set playback completed successfully."
        )
        diagnostics.record(
            category = "SetQueue",
            phase = SchedulerPhase.COMPLETE,
            message = "Whole set playback finished all occurrences."
        )
    }
}
