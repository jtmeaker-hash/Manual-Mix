/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui.waveform

import app.myspoti.automix.TrackAnalysis
import app.setflow.domain.DiagnosticsRingBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Thread-safe, memory-bounded LRU cache for decimated waveform peak tiles (Stage 08, Task 4).
 * Prevents memory exhaustion on 250+ track playlists and eliminates main-thread waveform calculation.
 */
class WaveformTileCache(
    val maxCapacity: Int = DEFAULT_CACHE_CAPACITY,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val diagnostics: DiagnosticsRingBuffer? = null
) {
    private val cache: MutableMap<String, WaveformPeakTile> = Collections.synchronizedMap(
        object : LinkedHashMap<String, WaveformPeakTile>(maxCapacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WaveformPeakTile>?): Boolean {
                val evict = size > maxCapacity
                if (evict && eldest != null) {
                    diagnostics?.record(
                        category = "WaveformCache",
                        message = "Evicted waveform tile key=${eldest.key} (capacity=$maxCapacity)"
                    )
                }
                return evict
            }
        }
    )

    fun getCachedTile(
        trackId: String,
        contentFingerprint: String,
        startUs: Long,
        endUs: Long,
        resolution: WaveformResolution = WaveformResolution.MEDIUM
    ): WaveformPeakTile? {
        val key = buildKey(trackId, contentFingerprint, startUs, endUs, resolution)
        return cache[key]
    }

    fun putTile(
        trackId: String,
        contentFingerprint: String,
        tile: WaveformPeakTile,
        resolution: WaveformResolution = WaveformResolution.MEDIUM
    ) {
        val key = buildKey(trackId, contentFingerprint, tile.startUs, tile.endUs, resolution)
        cache[key] = tile
    }

    /**
     * Loads or decimates a waveform tile asynchronously on [dispatcher] without blocking the main UI thread.
     */
    suspend fun getOrGenerateTile(
        trackId: String,
        contentFingerprint: String,
        startUs: Long,
        endUs: Long,
        resolution: WaveformResolution = WaveformResolution.MEDIUM,
        analysisProvider: () -> TrackAnalysis?
    ): WaveformTileState = withContext(dispatcher) {
        val key = buildKey(trackId, contentFingerprint, startUs, endUs, resolution)
        val existing = cache[key]
        if (existing != null) {
            return@withContext WaveformTileState.Ready(existing)
        }

        val analysis = analysisProvider()
        if (analysis == null) {
            return@withContext WaveformTileState.Loading
        }

        val tile = WaveformDecimator.decimateFromAnalysis(
            trackId = trackId,
            analysis = analysis,
            startUs = startUs,
            endUs = endUs,
            resolution = resolution
        )
        cache[key] = tile
        WaveformTileState.Ready(tile)
    }

    fun clear() {
        cache.clear()
    }

    val size: Int get() = cache.size

    private fun buildKey(
        trackId: String,
        contentFingerprint: String,
        startUs: Long,
        endUs: Long,
        resolution: WaveformResolution
    ): String = "$trackId:$contentFingerprint:${resolution.name}:$startUs:$endUs"

    companion object {
        const val DEFAULT_CACHE_CAPACITY = 128
    }
}
