/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.analysis

import app.myspoti.automix.TrackAnalysis
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Two-tier analysis cache (Level 1 in-memory LRU + Level 2 persistent binary disk files)
 * keyed strictly by recordingIdentity + audioKey + analyzerVersion + modelFingerprint + correctionRevision (ADR-004, Task 3).
 *
 * Guarantees zero duplicate analyses across occurrences of the same track in different sets,
 * and deterministic invalidation when either audio content or manual beat-grid revision changes.
 */
class TrackAnalysisCache(
    private val diskCacheDir: File,
    private val memoryCacheCapacity: Int = DEFAULT_MEMORY_CAPACITY,
    private val diagnostics: DiagnosticsRingBuffer? = null
) {
    // Level 1: Thread-safe pure JVM/Android memory LRU cache
    private val memoryCache: MutableMap<String, TrackAnalysis> = Collections.synchronizedMap(
        object : LinkedHashMap<String, TrackAnalysis>(memoryCacheCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysis>?): Boolean {
                return size > memoryCacheCapacity
            }
        }
    )

    // Mapping of recordingIdentity -> Set of cacheKeys for fast targeted invalidation
    private val recordingToCacheKeys = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs()
        }
    }

    /**
     * Computes the deterministic composite cache key.
     */
    fun computeKey(
        recordingIdentity: String,
        audioKey: String,
        modelFingerprint: String,
        correctionRevision: Long = 0L,
        analyzerVersion: Int = TrackAnalysis.VERSION
    ): String {
        val raw = "$recordingIdentity|$audioKey|$analyzerVersion|$modelFingerprint|$correctionRevision"
        val key = sha256Hex(raw.toByteArray(Charsets.UTF_8))
        recordingToCacheKeys.computeIfAbsent(recordingIdentity) { ConcurrentHashMap.newKeySet() }.add(key)
        return key
    }

    /**
     * Retrieves analysis from memory cache (L1) or disk cache (L2).
     * Returns null on cache miss or corrupted disk entry.
     */
    fun get(cacheKey: String): TrackAnalysis? {
        // 1. Check Level 1 memory cache
        synchronized(memoryCache) {
            val memoryHit = memoryCache.get(cacheKey)
            if (memoryHit != null) {
                diagnostics?.record(
                    category = "AnalysisCache",
                    phase = SchedulerPhase.IDLE,
                    message = "Cache HIT (L1 Memory): ${cacheKey.take(12)}..."
                )
                return memoryHit
            }
        }

        // 2. Check Level 2 disk cache
        val diskFile = File(diskCacheDir, "$cacheKey.bin")
        if (!diskFile.exists() || !diskFile.isFile) {
            return null
        }

        return try {
            BufferedInputStream(FileInputStream(diskFile)).use { input ->
                val diskHit = TrackAnalysis.read(input)
                if (diskHit != null) {
                    synchronized(memoryCache) {
                        memoryCache.put(cacheKey, diskHit)
                    }
                    diagnostics?.record(
                        category = "AnalysisCache",
                        phase = SchedulerPhase.IDLE,
                        message = "Cache HIT (L2 Disk): ${cacheKey.take(12)}... (duration=${diskHit.duration}s, bpm=${diskHit.bpm})"
                    )
                    diskHit
                } else {
                    diagnostics?.record(
                        category = "AnalysisCache",
                        phase = SchedulerPhase.IDLE,
                        message = "Cache CORRUPT (version mismatch) for ${cacheKey.take(12)}...; deleting entry"
                    )
                    diskFile.delete()
                    null
                }
            }
        } catch (t: Throwable) {
            diagnostics?.record(
                category = "AnalysisCache",
                phase = SchedulerPhase.IDLE,
                message = "Cache READ ERROR for ${cacheKey.take(12)}... (${t.message}); deleting entry"
            )
            diskFile.delete()
            null
        }
    }

    /**
     * Stores analysis in both memory (L1) and persistent disk (L2) caches.
     * Uses atomic file replacement (.tmp -> .bin) to prevent partial read bugs.
     */
    fun put(cacheKey: String, analysis: TrackAnalysis, recordingIdentity: String? = null) {
        if (recordingIdentity != null) {
            recordingToCacheKeys.computeIfAbsent(recordingIdentity) { ConcurrentHashMap.newKeySet() }.add(cacheKey)
        }

        // 1. Put into Level 1 Memory Cache
        synchronized(memoryCache) {
            memoryCache.put(cacheKey, analysis)
        }

        // 2. Persist to Level 2 Disk Cache
        try {
            val finalFile = File(diskCacheDir, "$cacheKey.bin")
            val tmpFile = File(diskCacheDir, "$cacheKey.bin.tmp")

            BufferedOutputStream(FileOutputStream(tmpFile)).use { out ->
                analysis.write(out)
            }

            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }

            diagnostics?.record(
                category = "AnalysisCache",
                phase = SchedulerPhase.IDLE,
                message = "Cache STORED (L1+L2): ${cacheKey.take(12)}... (${finalFile.length()} bytes)"
            )
        } catch (t: Throwable) {
            diagnostics?.record(
                category = "AnalysisCache",
                phase = SchedulerPhase.IDLE,
                message = "Cache WRITE FAILED for ${cacheKey.take(12)}...: ${t.message}"
            )
        }
    }

    /**
     * Explicitly invalidates a specific cache key from L1 memory and L2 disk.
     */
    fun invalidate(cacheKey: String) {
        synchronized(memoryCache) {
            memoryCache.remove(cacheKey)
        }
        val diskFile = File(diskCacheDir, "$cacheKey.bin")
        if (diskFile.exists()) {
            diskFile.delete()
        }
        val tmpFile = File(diskCacheDir, "$cacheKey.bin.tmp")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
        diagnostics?.record(
            category = "AnalysisCache",
            phase = SchedulerPhase.IDLE,
            message = "Cache INVALIDATED key ${cacheKey.take(12)}..."
        )
    }

    /**
     * Invalidates all cache entries associated with a recording identity
     * (e.g. when audio fingerprint changes or a grid correction revision increments).
     */
    fun invalidateForRecording(recordingIdentity: String) {
        val keys = recordingToCacheKeys.remove(recordingIdentity) ?: return
        for (k in keys) {
            invalidate(k)
        }
        diagnostics?.record(
            category = "AnalysisCache",
            phase = SchedulerPhase.IDLE,
            message = "Cache INVALIDATED ${keys.size} entries for recording $recordingIdentity"
        )
    }

    /**
     * Clears all memory and disk cache entries.
     */
    fun clear() {
        synchronized(memoryCache) {
            memoryCache.clear()
        }
        recordingToCacheKeys.clear()
        diskCacheDir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".bin") || file.name.endsWith(".tmp"))) {
                file.delete()
            }
        }
        diagnostics?.record(
            category = "AnalysisCache",
            phase = SchedulerPhase.IDLE,
            message = "Cache CLEARED completely"
        )
    }

    companion object {
        const val DEFAULT_MEMORY_CAPACITY = 64

        private fun sha256Hex(bytes: ByteArray): String {
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
