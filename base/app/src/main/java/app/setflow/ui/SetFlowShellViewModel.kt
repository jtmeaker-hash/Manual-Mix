/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.myspoti.automix.TrackAnalysis
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionResolutionResult
import app.setflow.analysis.AnalysisStatus
import app.setflow.db.EdgeCompleteSnapshot
import app.setflow.db.ProjectDetailSnapshot
import app.setflow.domain.DiagnosticEvent
import app.setflow.domain.ImportItemOutcome
import app.setflow.domain.ImportProgressState
import app.setflow.domain.LocalTrackRecord
import app.setflow.domain.RelinkCandidateEvaluation
import app.setflow.domain.SetDetailPlaceholderState
import app.setflow.domain.SetSummary
import app.setflow.domain.TrackImportSource
import app.setflow.playback.SetPlaybackQueueState
import app.setflow.service.AnalysisEngineStatus
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.service.PlaybackShellState
import app.setflow.service.SetFlowServiceBoundary
import app.setflow.service.TrackLibraryRepository
import app.setflow.ui.transition.TransitionEditorState
import app.setflow.ui.waveform.WaveformDecimator
import app.setflow.ui.waveform.WaveformResolution
import app.setflow.ui.waveform.WaveformTileCache
import app.setflow.ui.waveform.WaveformTileState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Navigation routes in the local-only shell (Stages 02–08).
 */
sealed interface SetFlowRoute {
    val routePattern: String

    data object Sets : SetFlowRoute {
        override val routePattern: String = "sets"
    }

    data class SetDetail(val setId: String) : SetFlowRoute {
        override val routePattern: String = "set_detail/$setId"

        companion object {
            const val PATTERN = "set_detail/{setId}"
            const val ARG_SET_ID = "setId"
        }
    }

    data class TransitionEditor(val setId: String, val edgeId: String) : SetFlowRoute {
        override val routePattern: String = "transition_editor/$setId/$edgeId"

        companion object {
            const val PATTERN = "transition_editor/{setId}/{edgeId}"
            const val ARG_SET_ID = "setId"
            const val ARG_EDGE_ID = "edgeId"
        }
    }

    data object Settings : SetFlowRoute {
        override val routePattern: String = "settings"
    }
}

/**
 * Immutable UI state exposed to Compose screens.
 */
data class SetFlowShellUiState(
    val currentRoute: SetFlowRoute = SetFlowRoute.Sets,
    val backStack: List<SetFlowRoute> = listOf(SetFlowRoute.Sets),
    val sets: List<SetSummary> = emptyList(),
    val tracks: List<LocalTrackRecord> = emptyList(),
    val importProgress: ImportProgressState = ImportProgressState(),
    val pendingRelinkEvaluation: RelinkCandidateEvaluation? = null,
    val activeSetDetail: SetDetailPlaceholderState? = null,
    val activeProjectDetail: ProjectDetailSnapshot? = null,
    val activeTransitionState: TransitionEditorState? = null,
    val waveformStateA: WaveformTileState = WaveformTileState.Loading,
    val waveformStateB: WaveformTileState = WaveformTileState.Loading,
    val analysisStatuses: Map<String, AnalysisStatus> = emptyMap(),
    val playbackState: PlaybackShellState = PlaybackShellState(),
    val queueState: SetPlaybackQueueState = SetPlaybackQueueState(),
    val analysisStatus: AnalysisEngineStatus = AnalysisEngineStatus(
        engineRetained = true,
        modelAssetPath = LocalOnlyShellServiceGraph.MODEL_ASSET_RELATIVE_PATH,
        modelAssetPresent = true,
        modelAssetBytes = 5_596_894L,
        stageNote = "Stage 08 Set Builder & Transition Editor UI"
    ),
    val offlineOnlyEnforced: Boolean = true,
    val accountRequired: Boolean = false,
    val recentDiagnostics: List<DiagnosticEvent> = emptyList()
)

/**
 * Pure Kotlin shell state controller (usable both in JVM unit tests and inside [SetFlowShellViewModel]).
 */
class SetFlowShellController(
    val services: SetFlowServiceBoundary = LocalOnlyShellServiceGraph(),
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
    parentScope: CoroutineScope? = null
) {
    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + backgroundDispatcher)
    val waveformCache = WaveformTileCache(diagnostics = services.diagnostics)

    private var activeTransitionDraft: TransitionEditorState? = null
    private var activeWaveformA: WaveformTileState = WaveformTileState.Loading
    private var activeWaveformB: WaveformTileState = WaveformTileState.Loading

    private val _uiState = MutableStateFlow(buildSnapshot(listOf(SetFlowRoute.Sets)))
    val uiState: StateFlow<SetFlowShellUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            services.setPlaybackQueueController.queueState.collect {
                refreshSnapshot()
            }
        }
    }

    fun openSets() {
        stopPreviewAndClearTransition()
        updateStack(listOf(SetFlowRoute.Sets))
    }

    fun openSetDetail(setId: String) {
        stopPreviewAndClearTransition()
        val current = _uiState.value.backStack
        val next = current + SetFlowRoute.SetDetail(setId)
        services.diagnostics.record(
            category = "Navigation",
            message = "Opened SetDetail for setId=$setId"
        )
        updateStack(next)
    }

    fun openSettings() {
        stopPreviewAndClearTransition()
        val current = _uiState.value.backStack
        val next = if (current.lastOrNull() == SetFlowRoute.Settings) current else current + SetFlowRoute.Settings
        services.diagnostics.record(
            category = "Navigation",
            message = "Opened Settings"
        )
        updateStack(next)
    }

    /**
     * Opens focused Transition Editor for an exact adjacent pair (Task 2 & Task 5).
     */
    fun openTransitionEditor(setId: String, edgeId: String): Boolean {
        val edgeSnap = services.projectDatabase.getEdgeCompleteSnapshot(edgeId) ?: return false
        val sideTracks = services.projectDatabase.getEdgeSideTracks(edgeId) ?: return false
        val projectDetail = services.setRepository.getProjectDetail(setId) ?: return false

        val (outTrack, inTrack) = sideTracks
        val draft = services.transitionManager.getDraft(edgeId)
        val initialParams = services.transitionManager.toOverrideParams(draft)
        val initialResolution = services.transitionManager.preview(edgeId, initialParams)

        val outStatus = services.analysisEngine.engine?.getStatus(outTrack.trackId) ?: AnalysisStatus.Idle
        val inStatus = services.analysisEngine.engine?.getStatus(inTrack.trackId) ?: AnalysisStatus.Idle

        val outAnalysis = (outStatus as? AnalysisStatus.Confident)?.analysis
            ?: (outStatus as? AnalysisStatus.Unreliable)?.analysis
        val inAnalysis = (inStatus as? AnalysisStatus.Confident)?.analysis
            ?: (inStatus as? AnalysisStatus.Unreliable)?.analysis

        val committedHash = edgeSnap.resolvedPlan?.planHashSha256.orEmpty()

        activeTransitionDraft = TransitionEditorState(
            setId = setId,
            edgeId = edgeId,
            edgeRevision = edgeSnap.edge.planRevision,
            projectRevision = projectDetail.project.editRevision,
            outgoingTrack = outTrack,
            incomingTrack = inTrack,
            outgoingAnalysis = outAnalysis,
            incomingAnalysis = inAnalysis,
            outgoingAnalysisStatus = outStatus,
            incomingAnalysisStatus = inStatus,
            draftParams = initialParams,
            initialParams = initialParams,
            validationResult = initialResolution,
            isPreviewPlaying = false,
            isSaving = false,
            edgeOrigin = edgeSnap.edge.origin,
            committedPlanHash = committedHash,
            showDirtyConfirmationDialog = false
        )

        // Waveforms decimation (Task 4: Never random waveforms, actual analyzed energy data)
        loadWaveformsForTransition(outTrack.trackId, outTrack.contentFingerprint, outAnalysis, inTrack.trackId, inTrack.contentFingerprint, inAnalysis)

        val current = _uiState.value.backStack
        val next = current + SetFlowRoute.TransitionEditor(setId, edgeId)
        services.diagnostics.record(
            category = "Navigation",
            message = "Opened TransitionEditor for edgeId=$edgeId"
        )
        updateStack(next)
        return true
    }

    /**
     * Updates draft parameters in-memory without mutating the database (Task 5, Contract D12).
     * Maintains a bounded undo history stack (Stage 13 Task 3).
     */
    fun updateTransitionDraft(params: TransitionOverrideParams) {
        val current = activeTransitionDraft ?: return
        if (params == current.draftParams) return
        val resolution = services.transitionManager.preview(current.edgeId, params)
        val newUndo = (listOf(current.draftParams) + current.undoStack).take(50)
        activeTransitionDraft = current.copy(
            draftParams = params,
            validationResult = resolution,
            undoStack = newUndo,
            redoStack = emptyList()
        )
        refreshSnapshot()
    }

    /**
     * Undoes the last in-memory transition parameter change (Stage 13 Task 3).
     */
    fun undoTransitionDraft(): Boolean {
        val current = activeTransitionDraft ?: return false
        val previous = current.undoStack.firstOrNull() ?: return false
        val newUndo = current.undoStack.drop(1)
        val newRedo = (listOf(current.draftParams) + current.redoStack).take(50)
        val resolution = services.transitionManager.preview(current.edgeId, previous)
        activeTransitionDraft = current.copy(
            draftParams = previous,
            validationResult = resolution,
            undoStack = newUndo,
            redoStack = newRedo
        )
        refreshSnapshot()
        return true
    }

    /**
     * Redoes the last undone in-memory transition parameter change (Stage 13 Task 3).
     */
    fun redoTransitionDraft(): Boolean {
        val current = activeTransitionDraft ?: return false
        val next = current.redoStack.firstOrNull() ?: return false
        val newRedo = current.redoStack.drop(1)
        val newUndo = (listOf(current.draftParams) + current.undoStack).take(50)
        val resolution = services.transitionManager.preview(current.edgeId, next)
        activeTransitionDraft = current.copy(
            draftParams = next,
            validationResult = resolution,
            undoStack = newUndo,
            redoStack = newRedo
        )
        refreshSnapshot()
        return true
    }

    /**
     * Toggles preview playback of the current draft (Task 5: Clearly marked PREVIEW UNSAVED).
     */
    fun toggleTransitionPreview() {
        val current = activeTransitionDraft ?: return
        if (!current.isPreviewPlaying) {
            val success = current.validationResult as? TransitionResolutionResult.Success ?: run {
                services.diagnostics.record(
                    category = "Preview",
                    message = "Cannot preview invalid transition draft: ${current.validationErrors.joinToString("; ")}"
                )
                return
            }
            val edgeSnap = services.projectDatabase.getEdgeCompleteSnapshot(current.edgeId)
            val outOccId = edgeSnap?.edge?.outgoingOccurrenceId ?: "occA"
            val inOccId = edgeSnap?.edge?.incomingOccurrenceId ?: "occB"

            services.transitionPreviewBridge.startPreview(
                projectId = current.setId,
                projectRevision = current.projectRevision,
                edgeId = current.edgeId,
                edgePlanRevision = current.edgeRevision,
                outgoingOccurrenceId = outOccId,
                incomingOccurrenceId = inOccId,
                outgoingTrack = current.outgoingTrack,
                incomingTrack = current.incomingTrack,
                draftPlan = success.plan,
                planHashSha256 = success.planHashSha256
            )
            activeTransitionDraft = current.copy(isPreviewPlaying = true)
            services.diagnostics.record(
                category = "Preview",
                message = "Draft preview started for edgeId=${current.edgeId} planHash=${success.planHashSha256.take(8)} (PREVIEW UNSAVED)"
            )
        } else {
            services.transitionPreviewBridge.stopPreview()
            activeTransitionDraft = current.copy(isPreviewPlaying = false)
            services.diagnostics.record(
                category = "Preview",
                message = "Draft preview stopped for edgeId=${current.edgeId}"
            )
        }
        refreshSnapshot()
    }

    /**
     * Requests confirmation before resetting transition override to auto recommendation (Stage 13 Task 3).
     */
    fun requestResetTransitionToAuto() {
        val current = activeTransitionDraft ?: return
        activeTransitionDraft = current.copy(showResetConfirmationDialog = true)
        refreshSnapshot()
    }

    /**
     * Dismisses the reset-to-auto confirmation dialog without modifying the draft.
     */
    fun dismissResetConfirmationDialog() {
        val current = activeTransitionDraft ?: return
        activeTransitionDraft = current.copy(showResetConfirmationDialog = false)
        refreshSnapshot()
    }

    /**
     * Resets transition override to automated recommendation (Task 5).
     */
    fun resetTransitionToAuto(): EdgeCompleteSnapshot? {
        val current = activeTransitionDraft ?: return null
        val updatedSnap = services.transitionManager.resetToAuto(
            edgeId = current.edgeId,
            expectedEdgePlanRevision = current.edgeRevision,
            expectedProjectRevision = current.projectRevision
        )
        // Refresh draft from newly reset database state
        val draft = services.transitionManager.getDraft(current.edgeId)
        val resetParams = services.transitionManager.toOverrideParams(draft)
        val resetResolution = services.transitionManager.preview(current.edgeId, resetParams)

        activeTransitionDraft = current.copy(
            edgeRevision = updatedSnap.edge.planRevision,
            projectRevision = services.setRepository.getProjectDetail(current.setId)?.project?.editRevision ?: current.projectRevision,
            edgeOrigin = updatedSnap.edge.origin,
            draftParams = resetParams,
            initialParams = resetParams,
            validationResult = resetResolution,
            committedPlanHash = updatedSnap.resolvedPlan?.planHashSha256.orEmpty(),
            showResetConfirmationDialog = false,
            undoStack = emptyList(),
            redoStack = emptyList()
        )
        services.setRepository.refreshSets()
        refreshSnapshot()
        return updatedSnap
    }

    /**
     * Atomically validates and commits the transition override to SQLite (Task 5).
     */
    fun saveTransitionOverride(): Boolean {
        val current = activeTransitionDraft ?: return false
        if (!current.isValid) return false

        activeTransitionDraft = current.copy(isSaving = true)
        refreshSnapshot()

        try {
            services.transitionManager.saveOverride(
                edgeId = current.edgeId,
                params = current.draftParams,
                expectedEdgePlanRevision = current.edgeRevision,
                expectedProjectRevision = current.projectRevision
            )
            services.setRepository.refreshSets()
            services.diagnostics.record(
                category = "TransitionManager",
                message = "Committed transition override for edgeId=${current.edgeId} (hash=${current.currentPlanHash})"
            )
            stopPreviewAndClearTransition()
            navigateBack()
            return true
        } catch (e: Exception) {
            services.diagnostics.record(
                category = "TransitionManager",
                message = "Save failed for edgeId=${current.edgeId}: ${e.message}"
            )
            activeTransitionDraft = current.copy(isSaving = false)
            refreshSnapshot()
            return false
        }
    }

    /**
     * Handles back navigation from Transition Editor with dirty check (Task 5).
     */
    fun requestCloseTransitionEditor(): Boolean {
        val current = activeTransitionDraft
        if (current != null && current.isDirty) {
            // Display dirty confirmation dialog (Task 5)
            activeTransitionDraft = current.copy(showDirtyConfirmationDialog = true)
            refreshSnapshot()
            return false
        }
        stopPreviewAndClearTransition()
        return navigateBack()
    }

    fun dismissDirtyConfirmationDialog() {
        val current = activeTransitionDraft ?: return
        activeTransitionDraft = current.copy(showDirtyConfirmationDialog = false)
        refreshSnapshot()
    }

    fun confirmDiscardTransitionChanges() {
        services.diagnostics.record(
            category = "TransitionEditor",
            message = "Discarded unsaved draft changes for edgeId=${activeTransitionDraft?.edgeId}"
        )
        stopPreviewAndClearTransition()
        navigateBack()
    }

    private fun stopPreviewAndClearTransition() {
        if (activeTransitionDraft?.isPreviewPlaying == true || services.transitionPreviewBridge.isPreviewActive) {
            services.transitionPreviewBridge.stopPreview()
            services.diagnostics.record(
                category = "Preview",
                message = "Stopped preview and disarmed bridge on editor close"
            )
        }
        activeTransitionDraft = null
        activeWaveformA = WaveformTileState.Loading
        activeWaveformB = WaveformTileState.Loading
    }

    private fun loadWaveformsForTransition(
        trackIdA: String,
        fingerprintA: String,
        analysisA: TrackAnalysis?,
        trackIdB: String,
        fingerprintB: String,
        analysisB: TrackAnalysis?
    ) {
        if (analysisA != null) {
            val tile = WaveformDecimator.decimateFromAnalysis(
                trackId = trackIdA,
                analysis = analysisA,
                startUs = 0L,
                endUs = (analysisA.duration * 1_000_000.0).toLong(),
                resolution = WaveformResolution.MEDIUM
            )
            activeWaveformA = WaveformTileState.Ready(tile)
            waveformCache.putTile(trackIdA, fingerprintA, tile, WaveformResolution.MEDIUM)
        } else {
            activeWaveformA = WaveformTileState.Loading
        }

        if (analysisB != null) {
            val tile = WaveformDecimator.decimateFromAnalysis(
                trackId = trackIdB,
                analysis = analysisB,
                startUs = 0L,
                endUs = (analysisB.duration * 1_000_000.0).toLong(),
                resolution = WaveformResolution.MEDIUM
            )
            activeWaveformB = WaveformTileState.Ready(tile)
            waveformCache.putTile(trackIdB, fingerprintB, tile, WaveformResolution.MEDIUM)
        } else {
            activeWaveformB = WaveformTileState.Loading
        }
    }

    /**
     * Pops back stack if deeper than root; returns true if handled, false if at root.
     */
    fun navigateBack(): Boolean {
        val current = _uiState.value.backStack
        if (current.size <= 1) return false
        val next = current.dropLast(1)
        updateStack(next)
        return true
    }

    fun requestStopPlayback() {
        services.playbackController.stopAll()
        refreshSnapshot()
    }

    // -------------------------------------------------------------------------
    // Whole-Set Playback Actions (Stage 10)
    // -------------------------------------------------------------------------

    fun playSet(setId: String, startOccurrenceId: String? = null, initialPosUs: Long = 0L): Boolean {
        val success = services.setPlaybackQueueController.startSet(setId, startOccurrenceId, initialPosUs)
        refreshSnapshot()
        return success
    }

    fun pauseSet() {
        services.setPlaybackQueueController.pause()
        refreshSnapshot()
    }

    fun resumeSet() {
        services.setPlaybackQueueController.resume()
        refreshSnapshot()
    }

    fun skipToNextTrack(): Boolean {
        val success = services.setPlaybackQueueController.skipToNext()
        refreshSnapshot()
        return success
    }

    fun skipToPreviousTrack(): Boolean {
        val success = services.setPlaybackQueueController.skipToPrevious()
        refreshSnapshot()
        return success
    }

    fun seekSetTimeline(globalUs: Long): Boolean {
        val success = services.setPlaybackQueueController.seekToGlobalUs(globalUs)
        refreshSnapshot()
        return success
    }

    // -------------------------------------------------------------------------
    // Set & Occurrence Actions (Stage 04 & Stage 08)
    // -------------------------------------------------------------------------

    fun createNewSet(name: String): ProjectDetailSnapshot {
        val detail = services.setRepository.createSet(name)
        refreshSnapshot()
        return detail
    }

    fun duplicateSet(setId: String): ProjectDetailSnapshot {
        val detail = services.setRepository.duplicateSet(setId)
        refreshSnapshot()
        return detail
    }

    fun deleteSet(setId: String, expectedRevision: Long) {
        services.setRepository.deleteSet(setId, expectedRevision)
        refreshSnapshot()
    }

    fun addTrackToSet(setId: String, expectedRevision: Long, trackId: String): ProjectDetailSnapshot {
        services.setRepository.syncLocalTracks(services.trackLibrary.tracks.value)
        val detail = services.setRepository.addTracksToSet(setId, expectedRevision, listOf(trackId))
        refreshSnapshot()
        return detail
    }

    fun moveOccurrence(
        setId: String,
        expectedRevision: Long,
        occurrenceId: String,
        delta: Int
    ): ProjectDetailSnapshot? {
        val detail = services.setRepository.getProjectDetail(setId) ?: return null
        val ids = detail.occurrences.map { it.occurrence.occurrenceId }.toMutableList()
        val idx = ids.indexOf(occurrenceId)
        if (idx < 0) return detail
        val targetIdx = (idx + delta).coerceIn(0, ids.lastIndex)
        if (targetIdx == idx) return detail
        val item = ids.removeAt(idx)
        ids.add(targetIdx, item)
        val updated = services.setRepository.reorderSetOccurrences(setId, expectedRevision, ids)
        refreshSnapshot()
        return updated
    }

    fun reorderSetOccurrences(
        setId: String,
        expectedRevision: Long,
        orderedOccurrenceIds: List<String>
    ): ProjectDetailSnapshot {
        val updated = services.setRepository.reorderSetOccurrences(setId, expectedRevision, orderedOccurrenceIds)
        refreshSnapshot()
        return updated
    }

    fun removeOccurrenceFromSet(
        setId: String,
        expectedRevision: Long,
        occurrenceId: String
    ): ProjectDetailSnapshot {
        val updated = services.setRepository.removeSetOccurrence(setId, expectedRevision, occurrenceId)
        refreshSnapshot()
        return updated
    }

    fun undoLastSetStructuralChange(setId: String, expectedRevision: Long): ProjectDetailSnapshot {
        val updated = services.setRepository.undoLastSetChange(setId, expectedRevision)
        refreshSnapshot()
        return updated
    }

    // -------------------------------------------------------------------------
    // Offline Mix Export (Stage 11)
    // -------------------------------------------------------------------------

    fun exportSet(
        setId: String,
        destinationFile: java.io.File,
        isCancelled: () -> Boolean = { false },
        onProgress: (progress: Double, framesWritten: Long, totalFrames: Long) -> Unit = { _, _, _ -> }
    ): app.setflow.export.ExportResult {
        val detail = services.setRepository.getProjectDetail(setId)
            ?: return app.setflow.export.ExportResult.Error(
                jobId = java.util.UUID.randomUUID().toString(),
                errorCode = app.setflow.domain.AudioErrorCode.A01_SOURCE_MISSING,
                reason = "Project $setId not found for export"
            )
        val result = services.offlineSetRenderEngine.renderProject(
            snapshot = detail,
            destinationFile = destinationFile,
            isCancelled = isCancelled,
            onProgress = onProgress
        )
        refreshSnapshot()
        return result
    }

    // -------------------------------------------------------------------------
    // Local Track Import & Relink Actions (Stage 03)
    // -------------------------------------------------------------------------

    fun importLocalUris(
        uriStrings: List<String>,
        intentFlags: Int = TrackLibraryRepository.DEFAULT_SAF_GRANT_FLAGS,
        importSource: TrackImportSource = TrackImportSource.SAF_DOCUMENT_PICKER
    ): List<ImportItemOutcome> {
        val outcomes = services.trackLibrary.importUris(
            uriStrings = uriStrings,
            intentFlags = intentFlags,
            importSource = importSource
        )
        services.setRepository.syncLocalTracks(services.trackLibrary.tracks.value)
        refreshSnapshot()
        return outcomes
    }

    fun cancelActiveImport() {
        services.trackLibrary.cancelActiveImport()
        refreshSnapshot()
    }

    fun verifyAllTracks(): List<LocalTrackRecord> {
        val verified = services.trackLibrary.verifyAllTracks()
        services.setRepository.syncLocalTracks(verified)
        refreshSnapshot()
        return verified
    }

    fun evaluateRelink(
        trackId: String,
        candidateUri: String,
        intentFlags: Int = TrackLibraryRepository.DEFAULT_SAF_GRANT_FLAGS,
        autoApplyOnExactMatch: Boolean = true
    ): RelinkCandidateEvaluation {
        val eval = services.trackLibrary.evaluateRelinkCandidate(
            trackId = trackId,
            candidateUri = candidateUri,
            intentFlags = intentFlags,
            autoApplyOnExactMatch = autoApplyOnExactMatch
        )
        services.setRepository.syncLocalTracks(services.trackLibrary.tracks.value)
        refreshSnapshot()
        return eval
    }

    fun confirmPendingRelink(userConfirmedUncertainMatch: Boolean): LocalTrackRecord? {
        val pending = services.trackLibrary.pendingRelinkEvaluation.value ?: return null
        val updated = services.trackLibrary.confirmRelink(pending, userConfirmedUncertainMatch)
        services.setRepository.syncLocalTracks(services.trackLibrary.tracks.value)
        refreshSnapshot()
        return updated
    }

    fun dismissPendingRelink() {
        services.trackLibrary.clearPendingRelinkEvaluation()
        refreshSnapshot()
    }

    fun refreshSnapshot() {
        updateStack(_uiState.value.backStack)
    }

    private fun updateStack(stack: List<SetFlowRoute>) {
        _uiState.value = buildSnapshot(stack)
    }

    private fun buildSnapshot(stack: List<SetFlowRoute>): SetFlowShellUiState {
        val top = stack.lastOrNull() ?: SetFlowRoute.Sets
        val currentSetId = when (top) {
            is SetFlowRoute.SetDetail -> top.setId
            is SetFlowRoute.TransitionEditor -> top.setId
            else -> null
        }
        val detailPlaceholder = currentSetId?.let { services.setRepository.getSetDetailPlaceholder(it) }
        val projectDetail = currentSetId?.let { services.setRepository.getProjectDetail(it) }

        val analysisStatuses = services.analysisEngine.engine?.states?.value ?: emptyMap()

        return SetFlowShellUiState(
            currentRoute = top,
            backStack = stack,
            sets = services.setRepository.sets.value,
            tracks = services.trackLibrary.tracks.value,
            importProgress = services.trackLibrary.importProgress.value,
            pendingRelinkEvaluation = services.trackLibrary.pendingRelinkEvaluation.value,
            activeSetDetail = detailPlaceholder,
            activeProjectDetail = projectDetail,
            activeTransitionState = activeTransitionDraft,
            waveformStateA = activeWaveformA,
            waveformStateB = activeWaveformB,
            analysisStatuses = analysisStatuses,
            playbackState = services.playbackController.state.value,
            queueState = services.setPlaybackQueueController.queueState.value,
            analysisStatus = services.analysisEngine.status.value,
            offlineOnlyEnforced = services.offlineOnlyEnforced,
            accountRequired = services.accountRequired,
            recentDiagnostics = services.diagnostics.snapshot()
        )
    }
}

/**
 * AndroidX ViewModel wrapping [SetFlowShellController] so UI state survives rotation and recreation.
 */
class SetFlowShellViewModel(
    val controller: SetFlowShellController
) : ViewModel() {
    val uiState: StateFlow<SetFlowShellUiState> = controller.uiState

    class Factory(private val services: SetFlowServiceBoundary) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SetFlowShellViewModel(SetFlowShellController(services)) as T
        }
    }
}
