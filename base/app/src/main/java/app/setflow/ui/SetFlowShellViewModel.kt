/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.setflow.db.ProjectDetailSnapshot
import app.setflow.domain.DiagnosticEvent
import app.setflow.domain.ImportItemOutcome
import app.setflow.domain.ImportProgressState
import app.setflow.domain.LocalTrackRecord
import app.setflow.domain.RelinkCandidateEvaluation
import app.setflow.domain.SetDetailPlaceholderState
import app.setflow.domain.SetSummary
import app.setflow.domain.TrackImportSource
import app.setflow.service.AnalysisEngineStatus
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.service.PlaybackShellState
import app.setflow.service.SetFlowServiceBoundary
import app.setflow.service.TrackLibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation routes in the standalone local-only shell (Stages 02–04).
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
    val playbackState: PlaybackShellState = PlaybackShellState(),
    val analysisStatus: AnalysisEngineStatus = AnalysisEngineStatus(
        engineRetained = true,
        modelAssetPath = LocalOnlyShellServiceGraph.MODEL_ASSET_RELATIVE_PATH,
        modelAssetPresent = true,
        modelAssetBytes = 5_596_894L,
        stageNote = "Stage 04 SQLite persistence"
    ),
    val offlineOnlyEnforced: Boolean = true,
    val accountRequired: Boolean = false,
    val recentDiagnostics: List<DiagnosticEvent> = emptyList()
)

/**
 * Pure Kotlin shell state controller (usable both in JVM unit tests and inside [SetFlowShellViewModel]).
 */
class SetFlowShellController(
    private val services: SetFlowServiceBoundary = LocalOnlyShellServiceGraph()
) {
    private val _uiState = MutableStateFlow(buildSnapshot(listOf(SetFlowRoute.Sets)))
    val uiState: StateFlow<SetFlowShellUiState> = _uiState.asStateFlow()

    fun openSets() {
        updateStack(listOf(SetFlowRoute.Sets))
    }

    fun openSetDetail(setId: String) {
        val current = _uiState.value.backStack
        val next = current + SetFlowRoute.SetDetail(setId)
        services.diagnostics.record(
            category = "Navigation",
            message = "Opened SetDetail for setId=$setId"
        )
        updateStack(next)
    }

    fun openSettings() {
        val current = _uiState.value.backStack
        val next = if (current.lastOrNull() == SetFlowRoute.Settings) current else current + SetFlowRoute.Settings
        services.diagnostics.record(
            category = "Navigation",
            message = "Opened Settings"
        )
        updateStack(next)
    }

    /**
     * Pops back stack if deeper than root; returns true if handled, false if at root.
     */
    fun navigateBack(): Boolean {
        val current = _uiState.value.backStack
        if (current.size <= 1) return false
        updateStack(current.dropLast(1))
        return true
    }

    fun requestStopPlayback() {
        services.playbackController.stopAll()
        refreshSnapshot()
    }

    // -------------------------------------------------------------------------
    // Stage 04 Persisted Set & Occurrence Actions
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
    // Stage 03 Local Track Import & Relink Actions
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
        val detailPlaceholder = (top as? SetFlowRoute.SetDetail)?.let {
            services.setRepository.getSetDetailPlaceholder(it.setId)
        }
        val projectDetail = (top as? SetFlowRoute.SetDetail)?.let {
            services.setRepository.getProjectDetail(it.setId)
        }
        return SetFlowShellUiState(
            currentRoute = top,
            backStack = stack,
            sets = services.setRepository.sets.value,
            tracks = services.trackLibrary.tracks.value,
            importProgress = services.trackLibrary.importProgress.value,
            pendingRelinkEvaluation = services.trackLibrary.pendingRelinkEvaluation.value,
            activeSetDetail = detailPlaceholder,
            activeProjectDetail = projectDetail,
            playbackState = services.playbackController.state.value,
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
