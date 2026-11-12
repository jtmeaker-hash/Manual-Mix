/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.service

import app.setflow.db.ProjectBackupManager
import app.setflow.db.ProjectDetailSnapshot
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.SetFlowSchemaAndMigrations
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.AudioGraphOwner
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.LocalTrackRecord
import app.setflow.domain.SchedulerPhase
import app.setflow.domain.SetDetailPlaceholderState
import app.setflow.domain.SetSummary
import app.setflow.io.TrackSourceProvider
import app.setflow.io.VirtualSafTrackSourceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
 * Read/write boundary for DJ sets backed by SQLite persistence (Stage 04).
 */
interface SetRepository {
    val sets: StateFlow<List<SetSummary>>
    fun getSetDetailPlaceholder(setId: String): SetDetailPlaceholderState
    fun getProjectDetail(setId: String): ProjectDetailSnapshot?
    fun createSet(name: String): ProjectDetailSnapshot
    fun renameSet(setId: String, expectedRevision: Long, newName: String): ProjectDetailSnapshot
    fun duplicateSet(setId: String, newName: String? = null): ProjectDetailSnapshot
    fun deleteSet(setId: String, expectedRevision: Long)
    fun addTracksToSet(setId: String, expectedRevision: Long, trackIds: List<String>): ProjectDetailSnapshot
    fun reorderSetOccurrences(setId: String, expectedRevision: Long, newOrderedOccurrenceIds: List<String>): ProjectDetailSnapshot
    fun removeSetOccurrence(setId: String, expectedRevision: Long, occurrenceId: String): ProjectDetailSnapshot
    fun undoLastSetChange(setId: String, expectedRevision: Long): ProjectDetailSnapshot
    fun syncLocalTracks(tracks: List<LocalTrackRecord>)
    fun refreshSets()
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
 * Stage 04 does not play audio and never pretends to play audio.
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
    val projectDatabase: SetFlowProjectDatabase
    val backupManager: ProjectBackupManager
    val setRepository: SetRepository
    val trackLibrary: TrackLibraryRepository
    val trackResolver: TrackResolver
    val analysisEngine: AnalysisEngineContract
    val playbackController: PlaybackSessionController
    val diagnostics: DiagnosticsRingBuffer
    val offlineOnlyEnforced: Boolean
    val accountRequired: Boolean
}

/**
 * SQLite-backed implementation of [SetRepository] (Stage 04).
 */
class SqliteSetRepository(
    private val database: SetFlowProjectDatabase
) : SetRepository {

    private val _sets = MutableStateFlow<List<SetSummary>>(emptyList())
    override val sets: StateFlow<List<SetSummary>> = _sets.asStateFlow()

    init {
        if (database.openMode == app.setflow.db.DatabaseOpenMode.READ_WRITE &&
            database.listProjects(includeDeleted = true).isEmpty()
        ) {
            database.createProject(
                name = "Starter Local Set",
                projectId = DEFAULT_STARTER_SET_ID
            )
        }
        refreshSets()
    }

    override fun refreshSets() {
        val projects = database.listProjects(includeDeleted = false)
        _sets.value = projects.map { p ->
            val detail = database.getProjectDetail(p.projectId)
            val occCount = detail?.occurrences?.size ?: 0
            val edgeCount = detail?.edges?.size ?: 0
            SetSummary(
                setId = p.projectId,
                title = p.name,
                trackCount = occCount,
                revision = p.editRevision,
                statusNote = "Persisted SQLite set • $occCount occurrence(s) • $edgeCount transition edge(s)"
            )
        }
    }

    override fun getSetDetailPlaceholder(setId: String): SetDetailPlaceholderState {
        val detail = database.getProjectDetail(setId)
        val occCount = detail?.occurrences?.size ?: 0
        val edgeCount = detail?.edges?.size ?: 0
        return SetDetailPlaceholderState(
            setId = setId,
            title = detail?.project?.name ?: "Set ($setId)",
            revision = detail?.project?.editRevision ?: 1L,
            notImplementedBanner = "Persisted $occCount occurrence(s) and $edgeCount transition edge(s) in SQLite. Not implemented in Stage 04: Two-deck Media3 playback (Stage 05), Beat grid analysis cache (Stage 06), Saved transition editor (Stage 07–09), and Offline WAV Export (Stage 11). Audio playback is inactive.",
            isAudioPlaying = false
        )
    }

    override fun getProjectDetail(setId: String): ProjectDetailSnapshot? =
        database.getProjectDetail(setId)

    override fun createSet(name: String): ProjectDetailSnapshot {
        val created = database.createProject(name = name)
        refreshSets()
        return database.getProjectDetail(created.projectId)!!
    }

    override fun renameSet(setId: String, expectedRevision: Long, newName: String): ProjectDetailSnapshot {
        database.renameProject(setId, expectedRevision, newName)
        refreshSets()
        return database.getProjectDetail(setId)!!
    }

    override fun duplicateSet(setId: String, newName: String?): ProjectDetailSnapshot {
        val dup = database.duplicateProject(setId, newName)
        refreshSets()
        return dup
    }

    override fun deleteSet(setId: String, expectedRevision: Long) {
        database.softDeleteProject(setId, expectedRevision)
        refreshSets()
    }

    override fun addTracksToSet(setId: String, expectedRevision: Long, trackIds: List<String>): ProjectDetailSnapshot {
        val updated = database.addTracksToProject(setId, expectedRevision, trackIds)
        refreshSets()
        return updated
    }

    override fun reorderSetOccurrences(
        setId: String,
        expectedRevision: Long,
        newOrderedOccurrenceIds: List<String>
    ): ProjectDetailSnapshot {
        val updated = database.reorderOccurrences(setId, expectedRevision, newOrderedOccurrenceIds)
        refreshSets()
        return updated
    }

    override fun removeSetOccurrence(
        setId: String,
        expectedRevision: Long,
        occurrenceId: String
    ): ProjectDetailSnapshot {
        val updated = database.removeOccurrence(setId, expectedRevision, occurrenceId)
        refreshSets()
        return updated
    }

    override fun undoLastSetChange(setId: String, expectedRevision: Long): ProjectDetailSnapshot {
        val updated = database.undoLastStructuralChange(setId, expectedRevision)
        refreshSets()
        return updated
    }

    override fun syncLocalTracks(tracks: List<LocalTrackRecord>) {
        if (database.openMode != app.setflow.db.DatabaseOpenMode.READ_WRITE) return
        for (record in tracks) {
            database.upsertTrack(TrackEntity.fromLocalTrackRecord(record))
        }
        refreshSets()
    }

    companion object {
        const val DEFAULT_STARTER_SET_ID = "sample-shell-set-01"
    }
}

/**
 * Default local-only service boundary implementation (Stages 02–04).
 */
class LocalOnlyShellServiceGraph(
    modelAssetPresent: Boolean = true,
    modelAssetBytes: Long = 5_596_894L,
    sourceProvider: TrackSourceProvider = VirtualSafTrackSourceProvider(),
    storageDirectory: File? = null
) : SetFlowServiceBoundary {

    override val diagnostics = DiagnosticsRingBuffer(capacity = 128)
    override val offlineOnlyEnforced: Boolean = true
    override val accountRequired: Boolean = false

    private val dbFile: File = storageDirectory?.let {
        File(it, "setflow_projects_v3.db")
    } ?: File.createTempFile("setflow_shell_db_", ".sqlite").apply { deleteOnExit() }

    override val projectDatabase: SetFlowProjectDatabase = SetFlowProjectDatabase(
        driver = SetFlowSchemaAndMigrations.createDriver(dbFile),
        diagnostics = diagnostics
    )

    override val backupManager: ProjectBackupManager = ProjectBackupManager(
        database = projectDatabase,
        diagnostics = diagnostics
    )

    override val trackLibrary: TrackLibraryRepository = LocalTrackLibraryService(
        sourceProvider = sourceProvider,
        diagnostics = diagnostics,
        storageDirectory = storageDirectory
    )

    override val trackResolver: TrackResolver
        get() = trackLibrary

    override val setRepository: SetRepository = SqliteSetRepository(projectDatabase).also { repo ->
        repo.syncLocalTracks(trackLibrary.tracks.value)
    }

    override val analysisEngine: AnalysisEngineContract = object : AnalysisEngineContract {
        private val _status = MutableStateFlow(
            AnalysisEngineStatus(
                engineRetained = true,
                modelAssetPath = MODEL_ASSET_RELATIVE_PATH,
                modelAssetPresent = modelAssetPresent,
                modelAssetBytes = modelAssetBytes,
                stageNote = "Pure Kotlin :automix + Beat This! int8 ONNX retained; SQLite project/occurrence/edge persistence active in Stage 04."
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
                statusMessage = "Stopped (generation=$nextGen). Two-deck Media3 playback is not implemented in Stage 04."
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
            message = "SetFlow cold start: offlineOnly=true, accountRequired=false, modelAssetPresent=$modelAssetPresent, dbSchemaVersion=${projectDatabase.schemaVersion}"
        )
    }

    companion object {
        const val MODEL_ASSET_RELATIVE_PATH = "automix/beat-this-small0-int8.onnx"
    }
}
