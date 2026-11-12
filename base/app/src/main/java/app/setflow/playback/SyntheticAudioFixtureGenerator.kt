/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Metadata and exact expected frame positions for a synthesized click/kick fixture
 * (TESTS/01_FIXTURES_AND_GENERATOR.txt).
 */
data class ClickFixtureMetadata(
    val name: String,
    val bpm: Double,
    val sampleRateHz: Int,
    val channels: Int,
    val totalFrames: Long,
    val durationUs: Long,
    val beatPeriodFrames: Double,
    val beatFrameIndices: LongArray,
    val downbeatFrameIndices: LongArray,
    val phraseFrameIndices: LongArray,
    val wavBytes: ByteArray
)

data class DecodedPcmBuffer(
    val sampleRateHz: Int,
    val channels: Int,
    val totalFrames: Int,
    val interleavedSamples: FloatArray
)

/**
 * Deterministic synthetic audio fixture generator (SEED = 0x5E7F1040L) producing original,
 * copyright-free click, sub-bass, and hard-kick WAV fixtures with exact frame-level ground truth.
 */
object SyntheticAudioFixtureGenerator {
    const val GENERATOR_SEED: Long = 0x5E7F1040L

    /**
     * Generates a 4/4 click + sinusoidal sub-bass fixture (e.g., T01 155 BPM, T02 158 BPM, T05 80 BPM).
     */
    fun generateClickAndSubWav(
        name: String,
        bpm: Double,
        durationSec: Double,
        sampleRateHz: Int = 44_100,
        channels: Int = 2,
        phaseOffsetMs: Double = 0.0,
        clickAmplitude: Float = 0.65f,
        subAmplitude: Float = 0.20f,
        subHz: Double = 55.0
    ): ClickFixtureMetadata {
        require(bpm > 0.0) { "BPM must be positive" }
        require(durationSec > 0.0) { "durationSec must be positive" }
        require(sampleRateHz in 8_000..192_000) { "Invalid sampleRateHz: $sampleRateHz" }
        require(channels in 1..2) { "channels must be 1 or 2" }

        val totalFrames = (durationSec * sampleRateHz).roundToLong().toInt()
        val samples = FloatArray(totalFrames * channels)
        val beatPeriodSec = 60.0 / bpm
        val beatPeriodFrames = beatPeriodSec * sampleRateHz
        val offsetFrames = (phaseOffsetMs * sampleRateHz / 1_000.0).roundToLong()

        val beatFrames = mutableListOf<Long>()
        val downbeatFrames = mutableListOf<Long>()
        val phraseFrames = mutableListOf<Long>()

        var beatIndex = 0
        while (true) {
            val frame = offsetFrames + (beatIndex * beatPeriodFrames).roundToLong()
            if (frame >= totalFrames) break
            if (frame >= 0L) {
                beatFrames.add(frame)
                if (beatIndex % 4 == 0) downbeatFrames.add(frame)
                if (beatIndex % 32 == 0) phraseFrames.add(frame)
            }
            beatIndex++
        }

        // Continuous low-level sub-bass so the track is audibly non-zero between clicks
        val twoPiSub = 2.0 * PI * subHz / sampleRateHz
        for (f in 0 until totalFrames) {
            val subSample = (subAmplitude * sin(twoPiSub * f)).toFloat()
            for (c in 0 until channels) {
                samples[f * channels + c] = subSample
            }
        }

        // Superimpose exponentially decaying click bursts at each beat frame
        val clickFramesLen = (0.012 * sampleRateHz).toInt().coerceAtLeast(1) // 12 ms click transient
        for ((idx, bFrameL) in beatFrames.withIndex()) {
            val bFrame = bFrameL.toInt()
            val isDownbeat = idx % 4 == 0
            val isPhrase = idx % 32 == 0
            val freqHz = when {
                isPhrase -> 2_400.0
                isDownbeat -> 1_600.0
                else -> 1_000.0
            }
            val amp = when {
                isPhrase -> clickAmplitude * 1.15f
                isDownbeat -> clickAmplitude * 1.05f
                else -> clickAmplitude
            }
            for (k in 0 until clickFramesLen) {
                val targetFrame = bFrame + k
                if (targetFrame >= totalFrames) break
                val env = exp(-5.0 * k.toDouble() / clickFramesLen)
                val v = (amp * env * sin(2.0 * PI * freqHz * k / sampleRateHz)).toFloat()
                for (c in 0 until channels) {
                    val pos = targetFrame * channels + c
                    samples[pos] = (samples[pos] + v).coerceIn(-0.99f, 0.99f)
                }
            }
        }

        val wavBytes = encodePcm16ToWavBytes(samples, sampleRateHz, channels)
        val durationUs = (totalFrames * 1_000_000L) / sampleRateHz
        return ClickFixtureMetadata(
            name = name,
            bpm = bpm,
            sampleRateHz = sampleRateHz,
            channels = channels,
            totalFrames = totalFrames.toLong(),
            durationUs = durationUs,
            beatPeriodFrames = beatPeriodFrames,
            beatFrameIndices = beatFrames.toLongArray(),
            downbeatFrameIndices = downbeatFrames.toLongArray(),
            phraseFrameIndices = phraseFrames.toLongArray(),
            wavBytes = wavBytes
        )
    }

    /**
     * Generates a synthetic hard-kick + click fixture (T03 160 BPM kick transient 130->45 Hz,
     * or T04 170 BPM near-0 dBFS kick to probe overlap headroom limiting).
     */
    fun generateHardKickAndClickWav(
        name: String,
        bpm: Double = 160.0,
        durationSec: Double = 16.0,
        sampleRateHz: Int = 44_100,
        channels: Int = 2,
        peakAmplitude: Float = 0.85f
    ): ClickFixtureMetadata {
        val totalFrames = (durationSec * sampleRateHz).roundToLong().toInt()
        val samples = FloatArray(totalFrames * channels)
        val beatPeriodSec = 60.0 / bpm
        val beatPeriodFrames = beatPeriodSec * sampleRateHz

        val beatFrames = mutableListOf<Long>()
        val downbeatFrames = mutableListOf<Long>()
        val phraseFrames = mutableListOf<Long>()

        var beatIndex = 0
        while (true) {
            val frame = (beatIndex * beatPeriodFrames).roundToLong()
            if (frame >= totalFrames) break
            beatFrames.add(frame)
            if (beatIndex % 4 == 0) downbeatFrames.add(frame)
            if (beatIndex % 32 == 0) phraseFrames.add(frame)
            beatIndex++
        }

        val kickLenFrames = beatPeriodFrames.roundToLong().toInt().coerceAtLeast(1) // Full-beat hardstyle kick + sub tail
        for (bFrameL in beatFrames) {
            val bFrame = bFrameL.toInt()
            var phase = 0.0
            for (k in 0 until kickLenFrames) {
                val targetFrame = bFrame + k
                if (targetFrame >= totalFrames) break
                val progress = k.toDouble() / kickLenFrames
                // Pitch envelope sweeps from 130 Hz punch down to 45 Hz sustained sub-bass tail
                val instHz = 45.0 + 85.0 * exp(-12.0 * progress)
                phase += 2.0 * PI * instHz / sampleRateHz
                val bodyEnv = 0.22 + 0.78 * exp(-3.2 * progress)
                val clickTransient = if (k < (0.008 * sampleRateHz).toInt()) {
                    0.35 * exp(-k.toDouble() / (0.002 * sampleRateHz)) * sin(2.0 * PI * 1800.0 * k / sampleRateHz)
                } else {
                    0.0
                }
                val sample = (peakAmplitude * (bodyEnv * sin(phase) + clickTransient)).toFloat().coerceIn(-0.995f, 0.995f)
                for (c in 0 until channels) {
                    samples[targetFrame * channels + c] = sample
                }
            }
        }

        val wavBytes = encodePcm16ToWavBytes(samples, sampleRateHz, channels)
        val durationUs = (totalFrames * 1_000_000L) / sampleRateHz
        return ClickFixtureMetadata(
            name = name,
            bpm = bpm,
            sampleRateHz = sampleRateHz,
            channels = channels,
            totalFrames = totalFrames.toLong(),
            durationUs = durationUs,
            beatPeriodFrames = beatPeriodFrames,
            beatFrameIndices = beatFrames.toLongArray(),
            downbeatFrameIndices = downbeatFrames.toLongArray(),
            phraseFrameIndices = phraseFrames.toLongArray(),
            wavBytes = wavBytes
        )
    }

    /**
     * Generates a tempo ramp fixture transitioning from [startBpm] to [endBpm] (T06 in TESTS/01_FIXTURES_AND_GENERATOR.txt).
     * Tests segmented head and tail grid analysis where intro and outro tempos differ.
     */
    fun generateTempoRampWav(
        name: String,
        startBpm: Double = 155.0,
        endBpm: Double = 160.0,
        durationSec: Double = 24.0,
        sampleRateHz: Int = 44_100,
        channels: Int = 2
    ): ClickFixtureMetadata {
        val totalFrames = (durationSec * sampleRateHz).roundToLong().toInt()
        val samples = FloatArray(totalFrames * channels)
        val beatFrames = mutableListOf<Long>()
        val downbeatFrames = mutableListOf<Long>()
        val phraseFrames = mutableListOf<Long>()

        // Continuous sub-bass so the track has non-zero audible bounds and onset flux
        val subHz = 55.0
        val twoPiSub = 2.0 * PI * subHz / sampleRateHz
        for (f in 0 until totalFrames) {
            val subSample = (0.20f * sin(twoPiSub * f)).toFloat()
            for (c in 0 until channels) {
                samples[f * channels + c] = subSample
            }
        }

        var currentSec = 0.0
        var beatIndex = 0
        while (currentSec < durationSec) {
            val progress = currentSec / durationSec
            val currentBpm = startBpm + (endBpm - startBpm) * progress
            val beatPeriodSec = 60.0 / currentBpm
            val frame = (currentSec * sampleRateHz).roundToLong()
            if (frame >= totalFrames) break
            beatFrames.add(frame)
            if (beatIndex % 4 == 0) downbeatFrames.add(frame)
            if (beatIndex % 32 == 0) phraseFrames.add(frame)

            // Click burst matching generateClickAndSubWav
            val clickLen = (0.012 * sampleRateHz).toInt().coerceAtLeast(1)
            val freqHz = if (beatIndex % 4 == 0) 1600.0 else 1000.0
            val amp = if (beatIndex % 4 == 0) 0.75f else 0.65f
            for (k in 0 until clickLen) {
                val targetFrame = frame.toInt() + k
                if (targetFrame >= totalFrames) break
                val env = exp(-5.0 * k.toDouble() / clickLen)
                val s = (amp * env * sin(2.0 * PI * freqHz * k / sampleRateHz)).toFloat()
                for (c in 0 until channels) {
                    val pos = targetFrame * channels + c
                    samples[pos] = (samples[pos] + s).coerceIn(-0.99f, 0.99f)
                }
            }

            currentSec += beatPeriodSec
            beatIndex++
        }

        val wavBytes = encodePcm16ToWavBytes(samples, sampleRateHz, channels)
        val durationUs = (totalFrames * 1_000_000L) / sampleRateHz
        return ClickFixtureMetadata(
            name = name,
            bpm = (startBpm + endBpm) / 2.0,
            sampleRateHz = sampleRateHz,
            channels = channels,
            totalFrames = totalFrames.toLong(),
            durationUs = durationUs,
            beatPeriodFrames = (sampleRateHz * 60.0 / ((startBpm + endBpm) / 2.0)),
            beatFrameIndices = beatFrames.toLongArray(),
            downbeatFrameIndices = downbeatFrames.toLongArray(),
            phraseFrameIndices = phraseFrames.toLongArray(),
            wavBytes = wavBytes
        )
    }

    /**
     * Generates a kickless build with a fake drop and full drop (T07 in TESTS/01_FIXTURES_AND_GENERATOR.txt).
     */
    fun generateKicklessBuildAndFakeDropWav(
        name: String,
        bpm: Double = 160.0,
        durationSec: Double = 24.0,
        sampleRateHz: Int = 44_100,
        channels: Int = 2
    ): ClickFixtureMetadata {
        val totalFrames = (durationSec * sampleRateHz).roundToLong().toInt()
        val samples = FloatArray(totalFrames * channels)
        val beatPeriodSec = 60.0 / bpm
        val beatPeriodFrames = beatPeriodSec * sampleRateHz

        val beatFrames = mutableListOf<Long>()
        val downbeatFrames = mutableListOf<Long>()
        val phraseFrames = mutableListOf<Long>()

        val introEndSec = 6.0
        val buildEndSec = 14.0
        val fakeDropEndSec = 16.0

        var currentSec = 0.0
        var beatIndex = 0
        while (currentSec < durationSec) {
            val frame = (currentSec * sampleRateHz).roundToLong()
            if (frame >= totalFrames) break

            val inBuild = currentSec in introEndSec..buildEndSec
            val inFakeDrop = currentSec in buildEndSec..fakeDropEndSec
            val inFullDrop = currentSec > fakeDropEndSec

            if (!inFakeDrop) {
                beatFrames.add(frame)
                if (beatIndex % 4 == 0) downbeatFrames.add(frame)
                if (beatIndex % 32 == 0) phraseFrames.add(frame)

                val clickLen = (0.015 * sampleRateHz).toInt().coerceAtLeast(1)
                val amp = if (inFullDrop) 0.85f else if (inBuild) 0.40f else 0.60f
                val freq = if (inFullDrop) 140.0 else 1400.0 // Low punch in drop, high click in intro
                for (k in 0 until clickLen) {
                    val targetFrame = frame.toInt() + k
                    if (targetFrame >= totalFrames) break
                    val env = exp(-5.0 * k.toDouble() / clickLen)
                    val s = (amp * env * sin(2.0 * PI * freq * k / sampleRateHz)).toFloat()
                    for (c in 0 until channels) {
                        samples[targetFrame * channels + c] = (samples[targetFrame * channels + c] + s).coerceIn(-0.99f, 0.99f)
                    }
                }
            }

            currentSec += beatPeriodSec
            beatIndex++
        }

        // Riser sweep during build (introEndSec to buildEndSec)
        val buildStartF = (introEndSec * sampleRateHz).toInt()
        val buildEndF = (buildEndSec * sampleRateHz).toInt().coerceAtMost(totalFrames)
        var riserPhase = 0.0
        for (f in buildStartF until buildEndF) {
            val progress = (f - buildStartF).toDouble() / (buildEndF - buildStartF)
            val instHz = 220.0 + 800.0 * progress * progress
            riserPhase += 2.0 * PI * instHz / sampleRateHz
            val riserAmp = (0.05 + 0.35 * progress).toFloat()
            val s = (riserAmp * sin(riserPhase)).toFloat()
            for (c in 0 until channels) {
                samples[f * channels + c] = (samples[f * channels + c] + s).coerceIn(-0.99f, 0.99f)
            }
        }

        val wavBytes = encodePcm16ToWavBytes(samples, sampleRateHz, channels)
        val durationUs = (totalFrames * 1_000_000L) / sampleRateHz
        return ClickFixtureMetadata(
            name = name,
            bpm = bpm,
            sampleRateHz = sampleRateHz,
            channels = channels,
            totalFrames = totalFrames.toLong(),
            durationUs = durationUs,
            beatPeriodFrames = beatPeriodFrames,
            beatFrameIndices = beatFrames.toLongArray(),
            downbeatFrameIndices = downbeatFrames.toLongArray(),
            phraseFrameIndices = phraseFrames.toLongArray(),
            wavBytes = wavBytes
        )
    }

    /**
     * Generates irregular, non-musical transients with no constant beat period (T16 in TESTS/01_FIXTURES_AND_GENERATOR.txt).
     * Used to test [AudioErrorCode.A04_BAD_ANALYSIS] detection and time-based crossfade fallback.
     */
    fun generateIrregularNonMusicalWav(
        name: String,
        durationSec: Double = 12.0,
        sampleRateHz: Int = 44_100,
        channels: Int = 2
    ): ClickFixtureMetadata {
        val totalFrames = (durationSec * sampleRateHz).roundToLong().toInt()
        val samples = FloatArray(totalFrames * channels)
        val prng = java.util.Random(GENERATOR_SEED)

        var f = 0
        while (f < totalFrames) {
            // Chaotic gap between 80 ms and 900 ms
            val gapSec = 0.08 + 0.82 * prng.nextDouble()
            f += (gapSec * sampleRateHz).toInt()
            if (f >= totalFrames) break

            val burstLen = (0.02 * sampleRateHz).toInt()
            val freqHz = 300.0 + 2000.0 * prng.nextDouble()
            for (k in 0 until burstLen) {
                val tf = f + k
                if (tf >= totalFrames) break
                val env = exp(-4.0 * k.toDouble() / burstLen)
                val s = (0.7f * env * sin(2.0 * PI * freqHz * k / sampleRateHz)).toFloat()
                for (c in 0 until channels) {
                    samples[tf * channels + c] = (samples[tf * channels + c] + s).coerceIn(-0.99f, 0.99f)
                }
            }
        }

        val wavBytes = encodePcm16ToWavBytes(samples, sampleRateHz, channels)
        val durationUs = (totalFrames * 1_000_000L) / sampleRateHz
        return ClickFixtureMetadata(
            name = name,
            bpm = 0.0,
            sampleRateHz = sampleRateHz,
            channels = channels,
            totalFrames = totalFrames.toLong(),
            durationUs = durationUs,
            beatPeriodFrames = 0.0,
            beatFrameIndices = longArrayOf(),
            downbeatFrameIndices = longArrayOf(),
            phraseFrameIndices = longArrayOf(),
            wavBytes = wavBytes
        )
    }

    /**
     * Generates a completely silent audio fixture (T16 silent recording).
     */
    fun generateSilentWav(
        name: String,
        durationSec: Double = 8.0,
        sampleRateHz: Int = 44_100,
        channels: Int = 2
    ): ClickFixtureMetadata {
        val totalFrames = (durationSec * sampleRateHz).roundToLong().toInt()
        val samples = FloatArray(totalFrames * channels)
        val wavBytes = encodePcm16ToWavBytes(samples, sampleRateHz, channels)
        val durationUs = (totalFrames * 1_000_000L) / sampleRateHz
        return ClickFixtureMetadata(
            name = name,
            bpm = 0.0,
            sampleRateHz = sampleRateHz,
            channels = channels,
            totalFrames = totalFrames.toLong(),
            durationUs = durationUs,
            beatPeriodFrames = 0.0,
            beatFrameIndices = longArrayOf(),
            downbeatFrameIndices = longArrayOf(),
            phraseFrameIndices = longArrayOf(),
            wavBytes = wavBytes
        )
    }

    /**
     * Encodes interleaved float PCM [-1.0, 1.0] into a canonical 16-bit little-endian RIFF WAV byte array.
     */
    fun encodePcm16ToWavBytes(
        samplesInterleaved: FloatArray,
        sampleRateHz: Int,
        channels: Int
    ): ByteArray {
        val dataBytes = samplesInterleaved.size * 2
        val byteRate = sampleRateHz * channels * 2
        val blockAlign = (channels * 2).toShort()
        val out = ByteArrayOutputStream(44 + dataBytes)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRateHz)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(16) // bitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        out.write(header.array())

        val pcmBuf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samplesInterleaved) {
            val clamped = if (s.isFinite()) s.coerceIn(-1.0f, 1.0f) else 0.0f
            val pcm16 = (clamped * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            pcmBuf.putShort(pcm16)
        }
        out.write(pcmBuf.array())
        return out.toByteArray()
    }

    /**
     * Decodes a 16-bit PCM RIFF WAV byte array into interleaved float samples [-1.0, 1.0].
     */
    fun decodeWavBytesToFloatPcm(wavBytes: ByteArray): DecodedPcmBuffer {
        require(wavBytes.size >= 44) { "WAV too short (${wavBytes.size} bytes)" }
        val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        require(riff == "RIFF") { "Invalid RIFF header: $riff" }
        buf.int // file size
        val wave = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        require(wave == "WAVE") { "Invalid WAVE header: $wave" }

        var channels = 2
        var sampleRate = 44_100
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (buf.remaining() >= 8) {
            val chunkId = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val chunkSize = buf.int
            if (chunkSize < 0 || chunkSize > buf.remaining()) {
                throw IllegalArgumentException("Corrupt WAV chunk '$chunkId' size $chunkSize (remaining ${buf.remaining()})")
            }
            val chunkStart = buf.position()
            if (chunkId == "fmt ") {
                val formatTag = buf.short.toInt() and 0xFFFF
                require(formatTag == 1) { "Unsupported WAV format tag: $formatTag" }
                channels = buf.short.toInt() and 0xFFFF
                sampleRate = buf.int
                buf.int // byteRate
                buf.short // blockAlign
                bitsPerSample = buf.short.toInt() and 0xFFFF
                require(bitsPerSample == 16) { "Only 16-bit PCM supported in fixture decoder, found $bitsPerSample" }
            } else if (chunkId == "data") {
                dataOffset = chunkStart
                dataSize = chunkSize
                break
            }
            buf.position(chunkStart + chunkSize + (chunkSize and 1))
        }

        require(dataOffset >= 0) { "Missing data chunk in WAV" }
        buf.position(dataOffset)
        val sampleCount = dataSize / 2
        val totalFrames = sampleCount / channels
        val out = FloatArray(totalFrames * channels)
        for (i in out.indices) {
            out[i] = buf.short / 32768.0f
        }
        return DecodedPcmBuffer(
            sampleRateHz = sampleRate,
            channels = channels,
            totalFrames = totalFrames,
            interleavedSamples = out
        )
    }
}
