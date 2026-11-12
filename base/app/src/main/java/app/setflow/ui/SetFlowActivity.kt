/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import app.setflow.SetFlowApplication
import app.setflow.service.LocalOnlyShellServiceGraph

/**
 * Main Activity hosting the standalone local-only SetFlow Compose shell.
 * Owns no ExoPlayer or audio instances; delegates to [SetFlowApplication.services].
 */
class SetFlowActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appServices = (application as? SetFlowApplication)?.services ?: LocalOnlyShellServiceGraph()
        val viewModel = ViewModelProvider(
            this,
            SetFlowShellViewModel.Factory(appServices)
        )[SetFlowShellViewModel::class.java]

        setContent {
            SetFlowAppShell(viewModel = viewModel)
        }
    }
}
