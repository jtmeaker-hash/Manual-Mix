/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui.waveform

import app.myspoti.automix.TrackAnalysis
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Decimated multi-resolution peak energy data for a discrete time window of an audio track (Stage 08, Task 4).
 * Prevents loading raw full-track PCM arrays into UI memory or Compose state (Bug Trap 39).
 */
data class WaveformPeakTile(
    val trackId: String,
    val startUs: Long,
    val endUs: Long,
    val resolutionPointsPerSec: Int,
    val peakAmplitudes: FloatArray,
    val bassAmplitudes: FloatArray? = null,
    val highAmplitudes: FloatArray? = null
) {
    val durationUs: Long get() = max(0L, endUs - startUs)
    val pointCount: Int get() = peakAmplitudes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformPeakTile) return false
        return trackId == other.trackId &&
                startUs == other.startUs &&
                endUs == other.endUs &&
                resolutionPointsPerSec == other.resolutionPointsPerSec &&
                peakAmplitudes.contentEquals(other.peakAmplitudes) &&
                (bassAmplitudes?.contentEquals(other.bassAmplitudes) ?: (other.bassAmplitudes == null)) &&
                (highAmplitudes?.contentEquals(other.highAmplitudes) ?: (other.highAmplitudes == null))
    }

    override fun hashCode(): Int {
        var result = trackId.hashCode()
        result = 31 * result + startUs.hashCode()
        result = 31 * result + endUs.hashCode()
        result = 31 * result + resolutionPointsPerSec
        result = 31 * result + peakAmplitudes.contentHashCode()
        result = 31 * result + (bassAmplitudes?.contentHashCode() ?: 0)
        result = 31 * result + (highAmplitudes?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Visual loading / availability state of a waveform tile window.
 */
sealed interface WaveformTileState {
    data object Loading : WaveformTileState
    data class Ready(val tile: WaveformPeakTile) : WaveformTileState
    data class Error(val message: String) : WaveformTileState
}

/**
 * Standard decimation resolutions (points per second) for waveform rendering at different zoom levels.
 */
enum class WaveformResolution(val pointsPerSec: Int) {
    OVERVIEW(10),   // Full-song overview (~1800 points for 3m track)
    MEDIUM(40),     // Standard editor view (~40 points/sec)
    DETAILED(100)   // High-zoom beat alignment view (~100 points/sec)
}

/**
 * Pure Kotlin decimator generating genuine peak and band envelopes from analyzed audio data or PCM buffers.
 * Never generates random or fake decorative waveforms representing real audio (Task 4 & Bug Trap 39).
 */
object WaveformDecimator {

    /**
     * Decimates energy envelope from [TrackAnalysis] across [startUs..endUs].
     * Uses measured [barDb], [barLowDb], [barHighDb], and [beats] without generating random values.
     */
    fun decimateFromAnalysis(
        trackId: String,
        analysis: TrackAnalysis,
        startUs: Long,
        endUs: Long,
        resolution: WaveformResolution = WaveformResolution.MEDIUM
    ): WaveformPeakTile {
        val safeStartUs = max(0L, startUs)
        val safeEndUs = max(safeStartUs, endUs)
        val durationSec = (safeEndUs - safeStartUs) / 1_000_000.0
        val pointCount = max(1, (durationSec * resolution.pointsPerSec).toInt())

        val peaks = FloatArray(pointCount)
        val bass = FloatArray(pointCount)
        val high = FloatArray(pointCount)

        val barStarts = analysis.barStarts
        val barDb = analysis.barDb
        val barLowDb = analysis.barLowDb
        val barHighDb = analysis.barHighDb
        val beats = analysis.beats

        val stepSec = if (pointCount > 1) durationSec / pointCount else durationSec
        val startSec = safeStartUs / 1_000_000.0

        for (i in 0 until pointCount) {
            val t = startSec + i * stepSec

            // 1. Locate current musical bar
            val barIndex = findBarIndex(barStarts, t)
            val db = if (barIndex in barDb.indices) barDb[barIndex] else -60.0f
            val lowDb = if (barIndex in barLowDb.indices) barLowDb[barIndex] else -60.0f
            val topDb = if (barIndex in barHighDb.indices) barHighDb[barIndex] else -60.0f

            // Convert dB (typically -60 dB .. 0 dB) to normalized linear amplitude [0.0 .. 1.0]
            val linearAmp = dbToLinear(db).coerceIn(0.02f, 1.0f)
            val linearBass = dbToLinear(lowDb).coerceIn(0.01f, 1.0f)
            val linearHigh = dbToLinear(topDb).coerceIn(0.01f, 1.0f)

            // 2. Modulate with physical beat transient onset if beats are present
            val beatPulse = if (beats.isNotEmpty()) {
                val dist = distanceToNearestBeat(beats, t)
                // Transient decay: exp(-dist / 0.08) drops sharply after ~80ms (typical drum transient)
                exp(-dist / 0.08f).coerceIn(0.0f, 1.0f) * 0.4f
            } else {
                0.0f
            }

            // Envelope composition: base bar level + beat pulse weighted by bass energy
            val totalPeak = (linearAmp * 0.65f + beatPulse * linearBass).coerceIn(0.05f, 1.0f)

            peaks[i] = totalPeak
            bass[i] = linearBass
            high[i] = linearHigh
        }

        return WaveformPeakTile(
            trackId = trackId,
            startUs = safeStartUs,
            endUs = safeEndUs,
            resolutionPointsPerSec = resolution.pointsPerSec,
            peakAmplitudes = peaks,
            bassAmplitudes = bass,
            highAmplitudes = high
        )
    }

    /**
     * Decimates raw single-channel PCM samples [-1.0f..1.0f] into peak envelopes.
     */
    fun decimateFromPcm(
        trackId: String,
        pcmSamples: FloatArray,
        sampleRate: Int,
        startUs: Long = 0L,
        resolution: WaveformResolution = WaveformResolution.MEDIUM
    ): WaveformPeakTile {
        val totalDurationUs = (pcmSamples.size.toDouble() / sampleRate * 1_000_000.0).toLong()
        val endUs = startUs + totalDurationUs
        val durationSec = totalDurationUs / 1_000_000.0
        val pointCount = max(1, (durationSec * resolution.pointsPerSec).toInt())

        val peaks = FloatArray(pointCount)
        val samplesPerPoint = max(1, pcmSamples.size / pointCount)

        for (i in 0 until pointCount) {
            val chunkStart = i * samplesPerPoint
            val chunkEnd = min(pcmSamples.size, chunkStart + samplesPerPoint)
            var maxAmp = 0.0f
            for (s in chunkStart until chunkEnd) {
                val absVal = kotlin.math.abs(pcmSamples[s])
                if (absVal > maxAmp) maxAmp = absVal
            }
            peaks[i] = maxAmp.coerceIn(0.02f, 1.0f)
        }

        return WaveformPeakTile(
            trackId = trackId,
            startUs = startUs,
            endUs = endUs,
            resolutionPointsPerSec = resolution.pointsPerSec,
            peakAmplitudes = peaks,
            bassAmplitudes = null,
            highAmplitudes = null
        )
    }

    private fun findBarIndex(barStarts: DoubleArray, t: Double): Int {
        if (barStarts.isEmpty()) return -1
        var idx = barStarts.binarySearch(t)
        if (idx < 0) {
            idx = -idx - 2
        }
        return idx.coerceIn(0, barStarts.lastIndex)
    }

    private fun distanceToNearestBeat(beats: DoubleArray, t: Double): Float {
        if (beats.isEmpty()) return 1000.0f
        var idx = beats.binarySearch(t)
        if (idx >= 0) return 0.0f
        val insertion = -idx - 1
        var minDist = Float.MAX_VALUE
        if (insertion < beats.size) {
            val d = kotlin.math.abs(beats[insertion] - t).toFloat()
            if (d < minDist) minDist = d
        }
        if (insertion > 0) {
            val d = kotlin.math.abs(beats[insertion - 1] - t).toFloat()
            if (d < minDist) minDist = d
        }
        return minDist
    }

    private fun dbToLinear(db: Float): Float {
        // dB scale: 0 dB = 1.0, -20 dB = 0.1, -40 dB = 0.01, -60 dB = 0.001
        return 10.0.pow(db / 20.0).toFloat()
    }
}
