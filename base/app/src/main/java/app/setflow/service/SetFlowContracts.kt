/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.service

import app.setflow.domain.AudioErrorCode
import app.setflow.domain.AudioGraphOwner
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import app.setflow.domain.SetDetailPlaceholderState
import app.setflow.domain.SetSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Result of resolving a local track identity against persisted SAF/local URI grants.
 */
sealed interface TrackResolutionResult {
    data class GrantedReadableUri(val uriString: String) : TrackResolutionResult
    data class RelinkRequired(
        val trackId: String,
        val errorCode: AudioErrorCode = AudioErrorCode.A01_SOURCE_MISSING
    ) : TrackResolutionResult
    data class DecodeUnsupported(
        val trackId: String,
        val mimeType: String?,
        val errorCode: AudioErrorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC
    ) : TrackResolutionResult
}

/**
 * Resolves local track IDs outside UI/Activity lifecycle.
 */
interface TrackResolver {
    fun resolve(trackId: String): TrackResolutionResult
}

/**
 * Read/write boundary for DJ sets (Room persistence implemented in Stage 04).
 */
interface SetRepository {
    val sets: StateFlow<List<SetSummary>>
    fun getSetDetailPlaceholder(setId: String): SetDetailPlaceholderState
}

/**
 * Status of the local-only Beat This! ONNX + Kotlin analysis engine (`:automix`).
 */
data class AnalysisEngineStatus(
    val engineRetained: Boolean,
    val modelAssetPath: String,
    val modelAssetPresent: Boolean,
    val modelAssetBytes: Long,
    val stageNote: String
)

interface AnalysisEngineContract {
    val status: StateFlow<AnalysisEngineStatus>
}

/**
 * Explicit local-only playback shell state owned outside Activity.
 * Stage 02 does not play audio and never pretends to play audio.
 */
data class PlaybackShellState(
    val phase: SchedulerPhase = SchedulerPhase.IDLE,
    val activeOwner: AudioGraphOwner = AudioGraphOwner.NONE,
    val isAudioPlaying: Boolean = false,
    val generation: Long = 0L,
    val lastError: AudioErrorCode? = null,
    val statusMessage: String = "Playback engine not implemented yet (Stages 05–10). Audio is inactive."
)

/**
 * Service-owned playback controller boundary so Activity never owns ExoPlayer instances.
 */
interface PlaybackSessionController {
    val state: StateFlow<PlaybackShellState>
    fun stopAll()
}

/**
 * Application-scoped service boundary container (no Activity references, no network or account state).
 */
interface SetFlowServiceBoundary {
    val setRepository: SetRepository
    val trackResolver: TrackResolver
    val analysisEngine: AnalysisEngineContract
    val playbackController: PlaybackSessionController
    val diagnostics: DiagnosticsRingBuffer
    val offlineOnlyEnforced: Boolean
    val accountRequired: Boolean
}

/**
 * Default Stage 02 local-only service boundary implementation.
 */
class LocalOnlyShellServiceGraph(
    modelAssetPresent: Boolean = true,
    modelAssetBytes: Long = 5_596_894L
) : SetFlowServiceBoundary {

    override val diagnostics = DiagnosticsRingBuffer(capacity = 128)
    override val offlineOnlyEnforced: Boolean = true
    override val accountRequired: Boolean = false

    override val setRepository: SetRepository = object : SetRepository {
        private val sampleSets = MutableStateFlow(
            listOf(
                SetSummary(
                    setId = "sample-shell-set-01",
                    title = "Stage 02 Shell Preview Set (Not Implemented)",
                    trackCount = 0,
                    revision = 1L,
                    statusNote = "Placeholder only — SAF audio import & Room storage arrive in Stages 03–04"
                )
            )
        )
        override val sets: StateFlow<List<SetSummary>> = sampleSets.asStateFlow()

        override fun getSetDetailPlaceholder(setId: String): SetDetailPlaceholderState {
            val found = sampleSets.value.firstOrNull { it.setId == setId }
            return SetDetailPlaceholderState(
                setId = setId,
                title = found?.title ?: "Set ($setId)",
                revision = found?.revision ?: 1L,
                notImplementedBanner = "Not implemented in Stage 02: Local SAF track import (Stage 03), Room database (Stage 04), Two-deck Media3 playback (Stage 05), Beat grid analysis cache (Stage 06), Saved transitions (Stage 07), Set Builder UI (Stage 08), DSP Preview (Stage 09), and Offline WAV Export (Stage 11). Audio playback is inactive.",
                isAudioPlaying = false
            )
        }
    }

    override val trackResolver: TrackResolver = object : TrackResolver {
        override fun resolve(trackId: String): TrackResolutionResult {
            diagnostics.record(
                category = "TrackResolver",
                phase = SchedulerPhase.IDLE,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Stage 02 shell: SAF TrackResolver not implemented yet for trackId=$trackId"
            )
            return TrackResolutionResult.RelinkRequired(
                trackId = trackId,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING
            )
        }
    }

    override val analysisEngine: AnalysisEngineContract = object : AnalysisEngineContract {
        private val _status = MutableStateFlow(
            AnalysisEngineStatus(
                engineRetained = true,
                modelAssetPath = MODEL_ASSET_RELATIVE_PATH,
                modelAssetPresent = modelAssetPresent,
                modelAssetBytes = modelAssetBytes,
                stageNote = "Pure Kotlin :automix + Beat This! int8 ONNX retained; background analysis worker scheduled for Stage 06."
            )
        )
        override val status: StateFlow<AnalysisEngineStatus> = _status.asStateFlow()
    }

    override val playbackController: PlaybackSessionController = object : PlaybackSessionController {
        private val _state = MutableStateFlow(PlaybackShellState())
        override val state: StateFlow<PlaybackShellState> = _state.asStateFlow()

        override fun stopAll() {
            val nextGen = _state.value.generation + 1L
            _state.value = PlaybackShellState(
                phase = SchedulerPhase.STOPPED,
                activeOwner = AudioGraphOwner.NONE,
                isAudioPlaying = false,
                generation = nextGen,
                lastError = null,
                statusMessage = "Stopped (generation=$nextGen). Two-deck Media3 playback is not implemented in Stage 02."
            )
            diagnostics.record(
                category = "PlaybackController",
                phase = SchedulerPhase.STOPPED,
                message = "Idempotent stopAll invoked (generation=$nextGen)"
            )
        }
    }

    init {
        diagnostics.record(
            category = "Startup",
            phase = SchedulerPhase.IDLE,
            message = "SetFlow Stage 02 cold start: offlineOnly=true, accountRequired=false, modelAssetPresent=$modelAssetPresent"
        )
    }

    companion object {
        const val MODEL_ASSET_RELATIVE_PATH = "automix/beat-this-small0-int8.onnx"
    }
}
