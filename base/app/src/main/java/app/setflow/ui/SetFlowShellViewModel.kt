/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.setflow.domain.DiagnosticEvent
import app.setflow.domain.SetDetailPlaceholderState
import app.setflow.domain.SetSummary
import app.setflow.service.AnalysisEngineStatus
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.service.PlaybackShellState
import app.setflow.service.SetFlowServiceBoundary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Navigation routes in the Stage 02 standalone local-only shell.
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
    val activeSetDetail: SetDetailPlaceholderState? = null,
    val playbackState: PlaybackShellState = PlaybackShellState(),
    val analysisStatus: AnalysisEngineStatus = AnalysisEngineStatus(
        engineRetained = true,
        modelAssetPath = LocalOnlyShellServiceGraph.MODEL_ASSET_RELATIVE_PATH,
        modelAssetPresent = true,
        modelAssetBytes = 5_596_894L,
        stageNote = "Stage 02 shell"
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
    private val _uiState = MutableStateFlow(buildSnapshot( listOf(SetFlowRoute.Sets)))
    val uiState: StateFlow<SetFlowShellUiState> = _uiState.asStateFlow()

    fun openSets() {
        updateStack(listOf(SetFlowRoute.Sets))
    }

    fun openSetDetail(setId: String) {
        val current = _uiState.value.backStack
        val next = current + SetFlowRoute.SetDetail(setId)
        services.diagnostics.record(
            category = "Navigation",
            message = "Opened SetDetail placeholder for setId=$setId"
        )
        updateStack(next)
    }

    fun openSettings() {
        val current = _uiState.value.backStack
        val next = if (current.lastOrNull() == SetFlowRoute.Settings) current else current + SetFlowRoute.Settings
        services.diagnostics.record(
            category = "Navigation",
            message = "Opened Settings placeholder"
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
        updateStack(_uiState.value.backStack)
    }

    private fun updateStack(stack: List<SetFlowRoute>) {
        _uiState.value = buildSnapshot(stack)
    }

    private fun buildSnapshot(stack: List<SetFlowRoute>): SetFlowShellUiState {
        val top = stack.lastOrNull() ?: SetFlowRoute.Sets
        val detail = (top as? SetFlowRoute.SetDetail)?.let {
            services.setRepository.getSetDetailPlaceholder(it.setId)
        }
        return SetFlowShellUiState(
            currentRoute = top,
            backStack = stack,
            sets = services.setRepository.sets.value,
            activeSetDetail = detail,
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
