/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.analysis

import android.content.Context
import app.myspoti.automix.BeatThis
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Manages loading the bundled Beat This! `small0` int8 ONNX model asset with guaranteed
 * fallback to pure-Kotlin [app.myspoti.automix.BeatTracker] on missing, corrupted, or unsupported
 * runtime environments (Stage 06 Task 1 & Acceptance Gate 4).
 */
class BeatThisModelProvider(
    private val context: Context? = null,
    private val modelAssetPath: String = "automix/beat-this-small0-int8.onnx",
    private val overrideModelBytes: ByteArray? = null,
    private val diagnostics: DiagnosticsRingBuffer? = null,
    private val forcePureKotlinFallback: Boolean = false
) {
    @Volatile
    private var cachedModelBytes: ByteArray? = overrideModelBytes

    val modelFingerprint: String by lazy {
        if (forcePureKotlinFallback) {
            PURE_KOTLIN_FINGERPRINT
        } else {
            val bytes = loadModelBytes()
            if (bytes != null && bytes.isNotEmpty()) {
                sha256Hex(bytes)
            } else {
                PURE_KOTLIN_FINGERPRINT
            }
        }
    }

    val isModelAssetPresent: Boolean
        get() = !forcePureKotlinFallback && loadModelBytes() != null

    /**
     * Loads the raw ONNX model bytes from Android assets, classpath resource, or provided override.
     */
    fun loadModelBytes(): ByteArray? {
        if (forcePureKotlinFallback) return null
        cachedModelBytes?.let { return it }

        synchronized(this) {
            cachedModelBytes?.let { return it }

            // 1. Try Android Context AssetManager if present
            if (context != null) {
                try {
                    context.assets.open(modelAssetPath).use { input ->
                        val bytes = input.readBytes()
                        cachedModelBytes = bytes
                        return bytes
                    }
                } catch (t: Throwable) {
                    diagnostics?.record(
                        category = "AnalysisModel",
                        phase = SchedulerPhase.IDLE,
                        message = "Android asset '$modelAssetPath' read failed: ${t.message}"
                    )
                }
            }

            // 2. Try ClassLoader / classpath resource (useful for JVM unit tests)
            try {
                val resourcePath = if (modelAssetPath.startsWith("/")) modelAssetPath else "/$modelAssetPath"
                val stream: InputStream? = javaClass.getResourceAsStream(resourcePath)
                    ?: javaClass.classLoader?.getResourceAsStream(modelAssetPath)
                    ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(modelAssetPath)

                if (stream != null) {
                    stream.use { input ->
                        val bytes = input.readBytes()
                        cachedModelBytes = bytes
                        return bytes
                    }
                }
            } catch (t: Throwable) {
                diagnostics?.record(
                    category = "AnalysisModel",
                    phase = SchedulerPhase.IDLE,
                    message = "Classpath model read failed: ${t.message}"
                )
            }

            // 3. Try standard file path relative to workspace/project
            val candidates = listOf(
                File("src/main/assets", modelAssetPath),
                File("base/app/src/main/assets", modelAssetPath),
                File("/root/SetFlow/base/app/src/main/assets", modelAssetPath)
            )
            for (candidate in candidates) {
                if (candidate.exists() && candidate.isFile && candidate.canRead()) {
                    try {
                        val bytes = candidate.readBytes()
                        cachedModelBytes = bytes
                        return bytes
                    } catch (t: Throwable) {
                        diagnostics?.record(
                            category = "AnalysisModel",
                            phase = SchedulerPhase.IDLE,
                            message = "File model read '${candidate.path}' failed: ${t.message}"
                        )
                    }
                }
            }

            return null
        }
    }

    /**
     * Instantiates a [BeatThis] ONNX inference session.
     * Returns null if model is missing, corrupt, unsupported on host ABI, or fails ONNX session initialization.
     * When null is returned, caller safely falls back to pure-Kotlin [app.myspoti.automix.BeatTracker].
     */
    fun createBeatThis(threads: Int = 1): BeatThis? {
        if (forcePureKotlinFallback) {
            diagnostics?.record(
                category = "AnalysisModel",
                phase = SchedulerPhase.IDLE,
                message = "BeatThis ONNX explicitly disabled; using pure-Kotlin BeatTracker fallback"
            )
            return null
        }

        val bytes = loadModelBytes() ?: run {
            diagnostics?.record(
                category = "AnalysisModel",
                phase = SchedulerPhase.IDLE,
                message = "BeatThis ONNX model asset not found; falling back to pure-Kotlin BeatTracker"
            )
            return null
        }

        return try {
            BeatThis(bytes, defaultThreads = threads).also {
                diagnostics?.record(
                    category = "AnalysisModel",
                    phase = SchedulerPhase.IDLE,
                    message = "BeatThis int8 ONNX session created (bytes=${bytes.size}, sha256=${modelFingerprint.take(12)}...)"
                )
            }
        } catch (t: Throwable) {
            diagnostics?.record(
                category = "AnalysisModel",
                phase = SchedulerPhase.IDLE,
                message = "BeatThis ONNX initialization failed (${t.javaClass.simpleName}: ${t.message}); falling back to pure-Kotlin BeatTracker"
            )
            null
        }
    }

    companion object {
        const val PURE_KOTLIN_FINGERPRINT = "pure-kotlin-beattracker"

        fun sha256Hex(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(bytes)
            val sb = StringBuilder(hash.size * 2)
            for (b in hash) {
                sb.append(String.format("%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }
    }
}
