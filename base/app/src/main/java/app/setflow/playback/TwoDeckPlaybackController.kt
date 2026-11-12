/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import app.setflow.db.SetFlowProjectDatabase
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.AudioGraphOwner
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import app.setflow.service.PlaybackSessionController
import app.setflow.service.PlaybackShellState
import com.music.spotui.automix.MixPreparation
import com.music.spotui.di.CrossfadeRamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of an individual deck's runtime state inside [TwoDeckPlaybackController].
 */
data class DeckRuntimeState(
    val deckId: String,
    val occurrenceId: String? = null,
    val trackId: String? = null,
    val isPrepared: Boolean = false,
    val isPlaying: Boolean = false,
    val positionUs: Long = 0L,
    val bufferedDurationMs: Long = 0L,
    val volume: Float = 0.0f,
    val ratePpm: Long = 1_000_000L,
    val segueArmed: Boolean = false
)

/**
 * Saved set-playback checkpoint when [AudioGraphOwner.TRANSITION_PREVIEW] preempts [AudioGraphOwner.SET_PLAYBACK] (S04).
 */
data class SuspendedSetSession(
    val projectId: String,
    val projectRevision: Long,
    val occurrenceId: String,
    val trackId: String,
    val positionUs: Long
)

/**
 * Service-owned dual-deck playback state machine (`IDLE -> PREPARING -> ARMED -> RUNNING -> HANDOFF -> COMPLETE`)
 * with monotonic `generation` tokens, single-owner audio graph arbitration, revision-locked transition bindings,
 * equal-power crossfade scheduling, audio focus handling, and output route-change protection (Stage 05).
 */
class TwoDeckPlaybackController(
    private val database: SetFlowProjectDatabase? = null,
    private val diagnostics: DiagnosticsRingBuffer = DiagnosticsRingBuffer()
) : PlaybackSessionController {

    private val _state = MutableStateFlow(
        PlaybackShellState(
            phase = SchedulerPhase.IDLE,
            activeOwner = AudioGraphOwner.NONE,
            isAudioPlaying = false,
            generation = 0L,
            lastError = null,
            statusMessage = "Dual-deck playback engine ready (IDLE)."
        )
    )
    override val state: StateFlow<PlaybackShellState> = _state.asStateFlow()

    @Volatile
    var deckA: DeckRuntimeState = DeckRuntimeState(deckId = "DECK_A")
        private set

    @Volatile
    var deckB: DeckRuntimeState = DeckRuntimeState(deckId = "DECK_B")
        private set

    @Volatile
    var activeBinding: ArmedTransitionBinding? = null
        private set

    @Volatile
    var suspendedSetSession: SuspendedSetSession? = null
        private set

    @Volatile
    var masterDuckGain: Float = 1.0f
        private set

    @Volatile
    var staleCallbacksRejectedCount: Int = 0
        private set

    /**
     * Starts preparing a two-deck transition under [owner] (`SET_PLAYBACK` or `TRANSITION_PREVIEW`).
     * If `TRANSITION_PREVIEW` is requested while `SET_PLAYBACK` owns the graph, `SET_PLAYBACK` is
     * suspended and disarmed first so two controllers never fight for audio focus (Task 7, S04).
     */
    @Synchronized
    fun prepareTransition(
        binding: ArmedTransitionBinding,
        owner: AudioGraphOwner = AudioGraphOwner.SET_PLAYBACK,
        initialPositionUsA: Long = (binding.exitAnchorUs - 4_000_000L).coerceAtLeast(0L)
    ): Long {
        require(owner != AudioGraphOwner.NONE) { "AudioGraphOwner must be SET_PLAYBACK or TRANSITION_PREVIEW" }

        val current = _state.value
        if (owner == AudioGraphOwner.TRANSITION_PREVIEW &&
            current.activeOwner == AudioGraphOwner.SET_PLAYBACK &&
            deckA.occurrenceId != null &&
            activeBinding != null
        ) {
            suspendedSetSession = SuspendedSetSession(
                projectId = activeBinding!!.projectId,
                projectRevision = activeBinding!!.projectRevision,
                occurrenceId = deckA.occurrenceId!!,
                trackId = deckA.trackId.orEmpty(),
                positionUs = deckA.positionUs
            )
            diagnostics.record(
                category = "PlaybackController",
                phase = SchedulerPhase.PREPARING,
                message = "Suspended SET_PLAYBACK at occurrence=${deckA.occurrenceId} to grant exclusive ownership to TRANSITION_PREVIEW"
            )
        }

        val nextGen = current.generation + 1L

        // Verify binding against database if attached
        if (database != null) {
            when (val check = binding.verifyAgainstDatabase(database)) {
                is ArmedTransitionBinding.ValidationResult.Mismatch -> {
                    activeBinding = null
                    deckA = DeckRuntimeState(deckId = "DECK_A")
                    deckB = DeckRuntimeState(deckId = "DECK_B")
                    _state.value = PlaybackShellState(
                        phase = SchedulerPhase.SAFE_FALLBACK,
                        activeOwner = owner,
                        isAudioPlaying = false,
                        generation = nextGen,
                        lastError = check.errorCode,
                        statusMessage = "Disarmed before prepare (${check.errorCode.code}): ${check.reason}"
                    )
                    diagnostics.record(
                        category = "PlaybackController",
                        phase = SchedulerPhase.SAFE_FALLBACK,
                        errorCode = check.errorCode,
                        message = "Transition binding mismatch at prepare: ${check.reason}"
                    )
                    return nextGen
                }
                ArmedTransitionBinding.ValidationResult.Valid -> Unit
            }
        }

        activeBinding = binding
        masterDuckGain = 1.0f
        deckA = DeckRuntimeState(
            deckId = "DECK_A",
            occurrenceId = binding.outgoingOccurrenceId,
            trackId = binding.outgoingTrackId,
            isPrepared = true,
            isPlaying = true,
            positionUs = initialPositionUsA,
            bufferedDurationMs = 2_000L,
            volume = 1.0f,
            ratePpm = 1_000_000L,
            segueArmed = false
        )
        // Deck B is initialized at entryAnchorUs with volume = 0.0f while prebuffering (Task 3)
        deckB = DeckRuntimeState(
            deckId = "DECK_B",
            occurrenceId = binding.incomingOccurrenceId,
            trackId = binding.incomingTrackId,
            isPrepared = false,
            isPlaying = false,
            positionUs = binding.entryAnchorUs,
            bufferedDurationMs = 0L,
            volume = 0.0f,
            ratePpm = binding.ratePpmB,
            segueArmed = false
        )

        _state.value = PlaybackShellState(
            phase = SchedulerPhase.PREPARING,
            activeOwner = owner,
            isAudioPlaying = true,
            generation = nextGen,
            lastError = null,
            statusMessage = "PREPARING transition edge=${binding.edgeId} (gen=$nextGen): Deck A playing, Deck B prebuffering at volume=0.0"
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = SchedulerPhase.PREPARING,
            message = "Prepared Deck A (${binding.outgoingOccurrenceId}) and started prebuffering Deck B (${binding.incomingOccurrenceId}) gen=$nextGen"
        )
        return nextGen
    }

    /**
     * Callback invoked when Deck B finishes prebuffering before the cue point.
     * Rejects stale callbacks whose [callbackGeneration] does not match current generation (Task 5, ACC-14).
     */
    @Synchronized
    fun onDeckBPrebuffered(
        callbackGeneration: Long,
        bufferedDurationMs: Long,
        decoderReady: Boolean = true
    ): Boolean {
        if (!checkGeneration(callbackGeneration, "onDeckBPrebuffered")) return false
        val binding = activeBinding ?: return false

        // Re-verify database revision before arming (S06: set reordered while preparing)
        if (!verifyBindingOrFallback(binding)) return false

        if (!decoderReady || bufferedDurationMs < MixPreparation.BUFFER_MS) {
            // Safe fallback: preserve outgoing Deck A at volume 1.0f, disarm Deck B (Task 3, Task 8)
            val err = if (!decoderReady) AudioErrorCode.A03_UNSUPPORTED_CODEC else AudioErrorCode.A06_BUFFER_UNDERRUN
            deckB = deckB.copy(isPrepared = false, isPlaying = false, volume = 0.0f, segueArmed = false)
            deckA = deckA.copy(volume = 1.0f, segueArmed = false)
            _state.value = _state.value.copy(
                phase = SchedulerPhase.SAFE_FALLBACK,
                isAudioPlaying = deckA.isPlaying,
                lastError = err,
                statusMessage = "SAFE_FALLBACK (${err.code}): Deck B not ready (buffered=${bufferedDurationMs}ms); preserving outgoing Deck A."
            )
            diagnostics.record(
                category = "PlaybackController",
                phase = SchedulerPhase.SAFE_FALLBACK,
                errorCode = err,
                message = "Deck B prebuffer failed/insufficient (${bufferedDurationMs}ms); preserved Deck A"
            )
            return false
        }

        // Deck B is prebuffered and remains at volume = 0.0f while ARMED prior to cue!
        deckB = deckB.copy(
            isPrepared = true,
            isPlaying = false,
            bufferedDurationMs = bufferedDurationMs,
            volume = 0.0f,
            segueArmed = binding.seguePlan != null
        )
        deckA = deckA.copy(
            segueArmed = binding.seguePlan != null
        )
        _state.value = _state.value.copy(
            phase = SchedulerPhase.ARMED,
            lastError = null,
            statusMessage = "ARMED edge=${binding.edgeId} (gen=$callbackGeneration): Deck B prebuffered (${bufferedDurationMs}ms) at volume=0.0"
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = SchedulerPhase.ARMED,
            message = "Armed transition edge=${binding.edgeId} gen=$callbackGeneration"
        )
        return true
    }

    /**
     * Advances playback into the overlap window (`ARMED -> RUNNING`) or steps through `progress` (`0.0f..1.0f`).
     */
    @Synchronized
    fun onStepOverlap(
        callbackGeneration: Long,
        sourcePositionUsA: Long
    ): Boolean {
        if (!checkGeneration(callbackGeneration, "onStepOverlap")) return false
        val binding = activeBinding ?: return false
        if (!verifyBindingOrFallback(binding)) return false

        if (_state.value.phase != SchedulerPhase.ARMED && _state.value.phase != SchedulerPhase.RUNNING) {
            return false
        }
        if (!deckB.isPrepared) {
            return onDeckBPrebuffered(callbackGeneration, deckB.bufferedDurationMs, decoderReady = false)
        }

        val elapsedInOverlapUs = (sourcePositionUsA - binding.exitAnchorUs).coerceAtLeast(0L)
        val fadeMs = (binding.spanUs / 1_000L).coerceAtLeast(1L)
        val progress = CrossfadeRamp.progress(elapsedInOverlapUs / 1_000L, fadeMs)
        val outGain = CrossfadeRamp.outgoingGain(progress) * masterDuckGain
        val inGain = CrossfadeRamp.incomingGain(progress) * masterDuckGain
        val incomingPosUs = binding.entryAnchorUs + (elapsedInOverlapUs * binding.ratePpmB) / 1_000_000L

        deckA = deckA.copy(
            isPlaying = true,
            positionUs = sourcePositionUsA,
            volume = outGain
        )
        deckB = deckB.copy(
            isPlaying = true,
            positionUs = incomingPosUs,
            volume = inGain
        )

        if (progress >= 1.0f) {
            return completeHandoffInternal(callbackGeneration, binding, incomingPosUs)
        }

        _state.value = _state.value.copy(
            phase = SchedulerPhase.RUNNING,
            isAudioPlaying = true,
            lastError = null,
            statusMessage = "RUNNING overlap edge=${binding.edgeId} progress=${(progress * 100).toInt()}% (gainA=${"%.2f".format(outGain)}, gainB=${"%.2f".format(inGain)})"
        )
        return true
    }

    @Synchronized
    private fun completeHandoffInternal(
        generation: Long,
        binding: ArmedTransitionBinding,
        finalIncomingPosUs: Long
    ): Boolean {
        // Transition through HANDOFF -> COMPLETE: Deck B becomes the sole active deck (Deck A stopped and cleared so no duplicate audio can ever play!)
        _state.value = _state.value.copy(
            phase = SchedulerPhase.HANDOFF,
            isAudioPlaying = true,
            statusMessage = "HANDOFF edge=${binding.edgeId}: promoting Deck B (${binding.incomingOccurrenceId}) to primary"
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = SchedulerPhase.HANDOFF,
            message = "Handoff promoting Deck B (${binding.incomingOccurrenceId}) gen=$generation"
        )

        deckA = DeckRuntimeState(
            deckId = "DECK_A",
            occurrenceId = binding.incomingOccurrenceId,
            trackId = binding.incomingTrackId,
            isPrepared = true,
            isPlaying = true,
            positionUs = finalIncomingPosUs,
            bufferedDurationMs = deckB.bufferedDurationMs,
            volume = 1.0f * masterDuckGain,
            ratePpm = binding.ratePpmB,
            segueArmed = false
        )
        deckB = DeckRuntimeState(
            deckId = "DECK_B",
            occurrenceId = null,
            trackId = null,
            isPrepared = false,
            isPlaying = false,
            positionUs = 0L,
            bufferedDurationMs = 0L,
            volume = 0.0f,
            ratePpm = 1_000_000L,
            segueArmed = false
        )
        activeBinding = null

        _state.value = _state.value.copy(
            phase = SchedulerPhase.COMPLETE,
            isAudioPlaying = true,
            lastError = null,
            statusMessage = "COMPLETE handoff to occurrence=${deckA.occurrenceId} (gen=$generation)"
        )
        return true
    }

    /**
     * Idempotent Pause: pauses both decks and increments generation so in-flight timer callbacks are ignored.
     */
    @Synchronized
    fun pause(): Long {
        val current = _state.value
        val nextGen = current.generation + 1L
        if (!current.isAudioPlaying) {
            _state.value = current.copy(
                generation = nextGen,
                statusMessage = "Paused (idempotent, gen=$nextGen)"
            )
            return nextGen
        }
        deckA = deckA.copy(isPlaying = false)
        deckB = deckB.copy(isPlaying = false)
        _state.value = current.copy(
            isAudioPlaying = false,
            generation = nextGen,
            statusMessage = "Paused at phase=${current.phase} (gen=$nextGen)"
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = current.phase,
            message = "Paused playback (gen=$nextGen)"
        )
        return nextGen
    }

    /**
     * Idempotent Resume: resumes active deck(s) if prepared and increments generation.
     */
    @Synchronized
    fun resume(): Long {
        val current = _state.value
        val nextGen = current.generation + 1L
        if (current.phase == SchedulerPhase.STOPPED || current.phase == SchedulerPhase.IDLE || !deckA.isPrepared) {
            _state.value = current.copy(generation = nextGen)
            return nextGen
        }
        val binding = activeBinding
        if (binding != null && !verifyBindingOrFallback(binding)) {
            return _state.value.generation
        }
        deckA = deckA.copy(isPlaying = true)
        if (current.phase == SchedulerPhase.RUNNING && deckB.isPrepared) {
            deckB = deckB.copy(isPlaying = true)
        }
        _state.value = current.copy(
            isAudioPlaying = true,
            generation = nextGen,
            statusMessage = "Resumed at phase=${current.phase} (gen=$nextGen)"
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = current.phase,
            message = "Resumed playback (gen=$nextGen)"
        )
        return nextGen
    }

    /**
     * Idempotent Skip / Next: cancels any active or armed transition, stops outgoing Deck A immediately,
     * promotes incoming Deck B to primary at unity gain, and increments generation so all old callbacks are rejected (Task 5, S05, ACC-14).
     */
    @Synchronized
    fun skipToNext(): Long {
        val current = _state.value
        val nextGen = current.generation + 1L
        val binding = activeBinding
        if (binding != null) {
            deckA = DeckRuntimeState(
                deckId = "DECK_A",
                occurrenceId = binding.incomingOccurrenceId,
                trackId = binding.incomingTrackId,
                isPrepared = true,
                isPlaying = current.isAudioPlaying,
                positionUs = deckB.positionUs.takeIf { it > 0L } ?: binding.entryAnchorUs,
                bufferedDurationMs = deckB.bufferedDurationMs.coerceAtLeast(1_000L),
                volume = 1.0f * masterDuckGain,
                ratePpm = binding.ratePpmB,
                segueArmed = false
            )
            deckB = DeckRuntimeState(deckId = "DECK_B")
            activeBinding = null
            _state.value = current.copy(
                phase = SchedulerPhase.COMPLETE,
                generation = nextGen,
                lastError = null,
                statusMessage = "Skipped to incoming occurrence=${deckA.occurrenceId} (gen=$nextGen)"
            )
            diagnostics.record(
                category = "PlaybackController",
                phase = SchedulerPhase.COMPLETE,
                message = "Skip/Next promoted incoming occurrence=${deckA.occurrenceId} and invalidated prior generation (gen=$nextGen)"
            )
        } else {
            _state.value = current.copy(
                generation = nextGen,
                statusMessage = "Skip/Next invoked with no pending transition (gen=$nextGen)"
            )
        }
        return nextGen
    }

    /**
     * Seeks to [targetPositionUs] on the active track, disarming any in-flight transition and incrementing generation (ACC-14).
     */
    @Synchronized
    fun seekToUs(targetPositionUs: Long): Long {
        val current = _state.value
        val nextGen = current.generation + 1L
        val clamped = targetPositionUs.coerceAtLeast(0L)
        deckA = deckA.copy(
            positionUs = clamped,
            volume = 1.0f * masterDuckGain,
            segueArmed = false
        )
        deckB = DeckRuntimeState(deckId = "DECK_B")
        activeBinding = null
        val nextPhase = if (deckA.isPrepared) SchedulerPhase.IDLE else SchedulerPhase.STOPPED
        _state.value = current.copy(
            phase = nextPhase,
            generation = nextGen,
            statusMessage = "Seeked to ${clamped / 1_000L}ms and disarmed pending transition (gen=$nextGen)"
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = nextPhase,
            message = "Seek to ${clamped}us disarmed transition (gen=$nextGen)"
        )
        return nextGen
    }

    /**
     * Handles system audio focus changes (Task 7, A08_AUDIO_FOCUS_LOST, ACC-18).
     */
    @Synchronized
    fun onAudioFocusChange(focusLost: Boolean, transient: Boolean = false, canDuck: Boolean = false): Long {
        val current = _state.value
        val nextGen = current.generation + 1L
        if (!focusLost) {
            masterDuckGain = 1.0f
            if (deckA.isPrepared && current.phase != SchedulerPhase.STOPPED) {
                deckA = deckA.copy(volume = 1.0f)
            }
            _state.value = current.copy(
                generation = nextGen,
                lastError = null,
                statusMessage = "Audio focus regained (gen=$nextGen)"
            )
            return nextGen
        }

        if (transient && canDuck) {
            masterDuckGain = 0.25f
            deckA = deckA.copy(volume = deckA.volume * masterDuckGain)
            deckB = deckB.copy(volume = deckB.volume * masterDuckGain)
            _state.value = current.copy(
                generation = nextGen,
                lastError = AudioErrorCode.A08_AUDIO_FOCUS_LOST,
                statusMessage = "Audio focus transient duck (-12 dB, gen=$nextGen)"
            )
            diagnostics.record(
                category = "PlaybackController",
                phase = current.phase,
                errorCode = AudioErrorCode.A08_AUDIO_FOCUS_LOST,
                message = "Ducked output on transient audio focus loss (gen=$nextGen)"
            )
            return nextGen
        }

        // Permanent or non-duckable transient focus loss: pause both decks and disarm secondary deck
        deckA = deckA.copy(isPlaying = false, segueArmed = false, volume = 1.0f)
        deckB = deckB.copy(isPlaying = false, segueArmed = false, volume = 0.0f)
        _state.value = current.copy(
            phase = SchedulerPhase.SAFE_FALLBACK,
            isAudioPlaying = false,
            generation = nextGen,
            lastError = AudioErrorCode.A08_AUDIO_FOCUS_LOST,
            statusMessage = "Paused on audio focus loss (A08, gen=$nextGen)"
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = SchedulerPhase.SAFE_FALLBACK,
            errorCode = AudioErrorCode.A08_AUDIO_FOCUS_LOST,
            message = "Paused and disarmed secondary deck on audio focus loss (gen=$nextGen)"
        )
        return nextGen
    }

    /**
     * Handles output route changes such as Bluetooth disconnect (`ACTION_AUDIO_BECOMING_NOISY`, Task 7, A09, ACC-19).
     */
    @Synchronized
    fun onOutputRouteChanged(newRouteName: String): Long {
        val current = _state.value
        val nextGen = current.generation + 1L
        deckA = deckA.copy(isPlaying = false, segueArmed = false, volume = 1.0f)
        deckB = DeckRuntimeState(deckId = "DECK_B")
        activeBinding = null
        _state.value = current.copy(
            phase = SchedulerPhase.SAFE_FALLBACK,
            isAudioPlaying = false,
            generation = nextGen,
            lastError = AudioErrorCode.A09_OUTPUT_ROUTE_CHANGED,
            statusMessage = "Paused safely on audio route change to '$newRouteName' (A09, gen=$nextGen)"
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = SchedulerPhase.SAFE_FALLBACK,
            errorCode = AudioErrorCode.A09_OUTPUT_ROUTE_CHANGED,
            message = "Output route changed to $newRouteName; flushed secondary deck and paused (gen=$nextGen)"
        )
        return nextGen
    }

    /**
     * Stops `TRANSITION_PREVIEW` and restores any suspended `SET_PLAYBACK` session (S04).
     */
    @Synchronized
    fun stopPreviewAndRestoreSuspendedSet(): Boolean {
        val suspended = suspendedSetSession ?: run {
            stopAll()
            return false
        }
        val nextGen = _state.value.generation + 1L
        suspendedSetSession = null
        activeBinding = null
        deckA = DeckRuntimeState(
            deckId = "DECK_A",
            occurrenceId = suspended.occurrenceId,
            trackId = suspended.trackId,
            isPrepared = true,
            isPlaying = false,
            positionUs = suspended.positionUs,
            bufferedDurationMs = 1_500L,
            volume = 1.0f
        )
        deckB = DeckRuntimeState(deckId = "DECK_B")
        _state.value = PlaybackShellState(
            phase = SchedulerPhase.IDLE,
            activeOwner = AudioGraphOwner.SET_PLAYBACK,
            isAudioPlaying = false,
            generation = nextGen,
            lastError = null,
            statusMessage = "Restored SET_PLAYBACK at occurrence=${suspended.occurrenceId} after preview exit (gen=$nextGen)"
        )
        return true
    }

    @Synchronized
    override fun stopAll() {
        val nextGen = _state.value.generation + 1L
        activeBinding = null
        suspendedSetSession = null
        masterDuckGain = 1.0f
        deckA = DeckRuntimeState(deckId = "DECK_A")
        deckB = DeckRuntimeState(deckId = "DECK_B")
        _state.value = PlaybackShellState(
            phase = SchedulerPhase.STOPPED,
            activeOwner = AudioGraphOwner.NONE,
            isAudioPlaying = false,
            generation = nextGen,
            lastError = null,
            statusMessage = "Stopped all decks and disarmed transitions (gen=$nextGen)."
        )
        diagnostics.record(
            category = "PlaybackController",
            phase = SchedulerPhase.STOPPED,
            message = "Idempotent stopAll invoked (generation=$nextGen)"
        )
    }

    private fun checkGeneration(callbackGeneration: Long, source: String): Boolean {
        val currentGen = _state.value.generation
        if (callbackGeneration != currentGen) {
            staleCallbacksRejectedCount++
            diagnostics.record(
                category = "PlaybackController",
                phase = _state.value.phase,
                message = "Ignored stale callback from $source (callbackGen=$callbackGeneration, currentGen=$currentGen)"
            )
            return false
        }
        return true
    }

    private fun verifyBindingOrFallback(binding: ArmedTransitionBinding): Boolean {
        val db = database ?: return true
        return when (val check = binding.verifyAgainstDatabase(db)) {
            ArmedTransitionBinding.ValidationResult.Valid -> true
            is ArmedTransitionBinding.ValidationResult.Mismatch -> {
                val nextGen = _state.value.generation + 1L
                activeBinding = null
                deckB = DeckRuntimeState(deckId = "DECK_B")
                deckA = deckA.copy(
                    isPlaying = check.errorCode != AudioErrorCode.A01_SOURCE_MISSING,
                    volume = 1.0f,
                    segueArmed = false
                )
                _state.value = _state.value.copy(
                    phase = SchedulerPhase.SAFE_FALLBACK,
                    isAudioPlaying = deckA.isPlaying,
                    generation = nextGen,
                    lastError = check.errorCode,
                    statusMessage = "Disarmed on revision/source mismatch (${check.errorCode.code}): ${check.reason}"
                )
                diagnostics.record(
                    category = "PlaybackController",
                    phase = SchedulerPhase.SAFE_FALLBACK,
                    errorCode = check.errorCode,
                    message = "Disarmed transition on mismatch: ${check.reason}"
                )
                false
            }
        }
    }
}
