/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import android.app.Application
import android.util.Log
import app.setflow.io.AndroidSafTrackSourceProvider
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.service.SetFlowServiceBoundary
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application entry point owning the local-only service boundary outside Activity lifecycle.
 * Holds no Activity references, no network clients, and no account/streaming sessions.
 */
class SetFlowApplication : Application() {

    lateinit var services: SetFlowServiceBoundary
        private set

    override fun onCreate() {
        super.onCreate()
        setupStartupCrashHandler()
        try {
            val (present, sizeBytes) = inspectBundledBeatModel()
            services = LocalOnlyShellServiceGraph(
                modelAssetPresent = present,
                modelAssetBytes = sizeBytes,
                sourceProvider = AndroidSafTrackSourceProvider(this),
                storageDirectory = filesDir,
                context = this
            )
            Log.i(TAG, "SetFlowApplication initialized successfully. StorageDir: ${filesDir.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "FATAL EXCEPTION in SetFlowApplication.onCreate()", t)
            recordStartupCrash(t)
            throw t
        }
    }

    private fun setupStartupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
                recordStartupCrash(throwable)
            } catch (_: Throwable) {
                // Never allow crash logging to mask the failure or crash itself
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun recordStartupCrash(throwable: Throwable) {
        runCatching {
            val logFile = File(filesDir, CRASH_LOG_FILENAME)
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val logContent = "=== SetFlow Fatal Crash at ${System.currentTimeMillis()} ===\n" +
                    "Thread: ${Thread.currentThread().name}\n" +
                    "Exception: ${throwable.javaClass.name}: ${throwable.message}\n" +
                    "Stacktrace:\n$sw\n"
            logFile.writeText(logContent)
        }
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

    companion object {
        private const val TAG = "SetFlowStartup"
        const val CRASH_LOG_FILENAME = "setflow_startup_crash.log"
    }
}
