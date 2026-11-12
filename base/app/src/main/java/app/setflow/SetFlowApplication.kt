/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import android.app.Application
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.service.SetFlowServiceBoundary

/**
 * Application entry point owning the local-only service boundary outside Activity lifecycle.
 * Holds no Activity references, no network clients, and no account/streaming sessions.
 */
class SetFlowApplication : Application() {

    lateinit var services: SetFlowServiceBoundary
        private set

    override fun onCreate() {
        super.onCreate()
        val (present, sizeBytes) = inspectBundledBeatModel()
        services = LocalOnlyShellServiceGraph(
            modelAssetPresent = present,
            modelAssetBytes = sizeBytes
        )
    }

    private fun inspectBundledBeatModel(): Pair<Boolean, Long> = runCatching {
        assets.open(LocalOnlyShellServiceGraph.MODEL_ASSET_RELATIVE_PATH).use { stream ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                total += read
            }
            true to total
        }
    }.getOrDefault(false to 0L)
}
