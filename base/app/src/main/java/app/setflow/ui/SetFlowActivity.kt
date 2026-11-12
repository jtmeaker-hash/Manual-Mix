/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import app.setflow.SetFlowApplication
import app.setflow.domain.TrackImportSource
import app.setflow.service.LocalOnlyShellServiceGraph

/**
 * Main Activity hosting the standalone local-only SetFlow Compose shell.
 * Registers SAF ACTION_OPEN_DOCUMENT multi-select & relink launchers (Stage 03).
 */
class SetFlowActivity : ComponentActivity() {

    private var pendingRelinkTrackId: String? = null
    private lateinit var shellViewModel: SetFlowShellViewModel

    private val openMultipleAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty() && ::shellViewModel.isInitialized) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            shellViewModel.controller.importLocalUris(
                uriStrings = uris.map { it.toString() },
                intentFlags = flags,
                importSource = TrackImportSource.SAF_DOCUMENT_PICKER
            )
        }
    }

    private val openRelinkDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = pendingRelinkTrackId
        pendingRelinkTrackId = null
        if (uri != null && targetId != null && ::shellViewModel.isInitialized) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            shellViewModel.controller.evaluateRelink(
                trackId = targetId,
                candidateUri = uri.toString(),
                intentFlags = flags,
                autoApplyOnExactMatch = true
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appServices = (application as? SetFlowApplication)?.services ?: LocalOnlyShellServiceGraph()
        shellViewModel = ViewModelProvider(
            this,
            SetFlowShellViewModel.Factory(appServices)
        )[SetFlowShellViewModel::class.java]

        setContent {
            SetFlowAppShell(
                viewModel = shellViewModel,
                onLaunchSafMultiPicker = {
                    openMultipleAudioLauncher.launch(arrayOf("audio/*"))
                },
                onLaunchSafRelinkPicker = { trackId ->
                    pendingRelinkTrackId = trackId
                    openRelinkDocumentLauncher.launch(arrayOf("audio/*"))
                }
            )
        }
    }
}
