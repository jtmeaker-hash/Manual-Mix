/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.export

import app.setflow.domain.AudioErrorCode
import app.setflow.playback.DecodedPcmBuffer
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Interface for streaming audio frames from a track source in bounded blocks (Stage 11 Task 1 & 3).
 * Guarantees bounded O(1) heap allocation during multi-hour set rendering.
 */
interface StreamingTrackAudioReader : Closeable {
    val sampleRateHz: Int
    val channels: Int
    val totalFrames: Long

    /**
     * Reads up to [framesToRead] frames starting at [startFrame] into [destinationBuffer].
     * Interleaved float samples [-1.0f, 1.0f] are written starting at [destinationOffset].
     *
     * @return Number of frames actually read.
     */
    fun readFrames(
        startFrame: Long,
        framesToRead: Int,
        destinationBuffer: FloatArray,
        destinationOffset: Int = 0
    ): Int

    override fun close() {}
}

/**
 * Streaming reader backed by an in-memory [DecodedPcmBuffer].
 */
class InMemoryPcmTrackReader(
    private val pcm: DecodedPcmBuffer
) : StreamingTrackAudioReader {
    override val sampleRateHz: Int = pcm.sampleRateHz
    override val channels: Int = pcm.channels
    override val totalFrames: Long = pcm.totalFrames.toLong()

    override fun readFrames(
        startFrame: Long,
        framesToRead: Int,
        destinationBuffer: FloatArray,
        destinationOffset: Int
    ): Int {
        if (startFrame >= totalFrames || framesToRead <= 0) return 0
        val clampedStart = startFrame.toInt()
        val available = (pcm.totalFrames - clampedStart).coerceAtLeast(0)
        val toCopy = minOf(framesToRead, available)
        val sampleOffsetSrc = clampedStart * channels
        val samplesCount = toCopy * channels

        System.arraycopy(
            pcm.interleavedSamples,
            sampleOffsetSrc,
            destinationBuffer,
            destinationOffset,
            samplesCount
        )
        return toCopy
    }
}

/**
 * Streaming reader that directly streams frames from a standard uncompressed PCM WAV file
 * on disk without loading the whole file into RAM (Task 1).
 */
class WavFileStreamingTrackReader(
    private val wavFile: File
) : StreamingTrackAudioReader {
    private val raf = RandomAccessFile(wavFile, "r")
    override val sampleRateHz: Int
    override val channels: Int
    override val totalFrames: Long
    private val bitsPerSample: Int
    private val bytesPerFrame: Int
    private val dataOffset: Long

    init {
        val header = ByteArray(44)
        raf.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        val riff = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        if (riff != "RIFF") throw AudioExportException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Invalid RIFF in $wavFile")
        buf.int // riff size
        val wave = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        if (wave != "WAVE") throw AudioExportException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Invalid WAVE in $wavFile")

        // Parse chunks until fmt and data found
        var fmtFound = false
        var sRate = 44100
        var ch = 2
        var bps = 16
        var dOffset = 44L
        var dSize = 0L

        raf.seek(12L)
        while (raf.filePointer + 8 <= raf.length()) {
            val chunkHdr = ByteArray(8)
            raf.readFully(chunkHdr)
            val cBuf = ByteBuffer.wrap(chunkHdr).order(ByteOrder.LITTLE_ENDIAN)
            val cId = ByteArray(4).also { cBuf.get(it) }.toString(Charsets.US_ASCII)
            val cSz = cBuf.int.toLong() and 0xFFFFFFFFL

            if (cId == "fmt ") {
                val fmtBytes = ByteArray(cSz.toInt())
                raf.readFully(fmtBytes)
                val fB = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                val formatCode = fB.short.toInt() and 0xFFFF
                if (formatCode != 1) throw AudioExportException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Only PCM supported: $formatCode")
                ch = fB.short.toInt() and 0xFFFF
                sRate = fB.int
                fB.int // byteRate
                fB.short // align
                bps = fB.short.toInt() and 0xFFFF
                fmtFound = true
            } else if (cId == "data") {
                dOffset = raf.filePointer
                dSize = cSz
                break
            } else {
                raf.seek(raf.filePointer + cSz + (cSz and 1L))
            }
        }

        if (!fmtFound || dSize <= 0) {
            throw AudioExportException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Missing fmt or data in $wavFile")
        }

        sampleRateHz = sRate
        channels = ch
        bitsPerSample = bps
        bytesPerFrame = channels * (bitsPerSample / 8)
        dataOffset = dOffset
        totalFrames = dSize / bytesPerFrame
    }

    override fun readFrames(
        startFrame: Long,
        framesToRead: Int,
        destinationBuffer: FloatArray,
        destinationOffset: Int
    ): Int {
        if (startFrame >= totalFrames || framesToRead <= 0) return 0
        val framesAvailable = (totalFrames - startFrame).coerceAtLeast(0L)
        val frames = minOf(framesToRead.toLong(), framesAvailable).toInt()
        val bytesToRead = frames * bytesPerFrame

        val filePos = dataOffset + startFrame * bytesPerFrame
        raf.seek(filePos)

        val rawBytes = ByteArray(bytesToRead)
        val read = raf.read(rawBytes)
        if (read <= 0) return 0

        val actualFrames = read / bytesPerFrame
        val bb = ByteBuffer.wrap(rawBytes, 0, actualFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        var destIdx = destinationOffset

        for (f in 0 until actualFrames) {
            for (c in 0 until channels) {
                val sample = when (bitsPerSample) {
                    16 -> bb.short / 32768.0f
                    24 -> {
                        val b0 = bb.get().toInt() and 0xFF
                        val b1 = bb.get().toInt() and 0xFF
                        val b2 = bb.get().toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608.0f
                    }
                    32 -> bb.int / 2147483648.0f
                    else -> 0.0f
                }
                destinationBuffer[destIdx++] = sample
            }
        }

        return actualFrames
    }

    override fun close() {
        raf.close()
    }
}

/**
 * Procedural streaming audio generator that produces synthetic audio (sine sub + click)
 * strictly on demand in bounded blocks, requiring zero memory regardless of duration (e.g. 3 hours).
 */
class ProceduralSyntheticTrackReader(
    val bpm: Double = 120.0,
    val durationSeconds: Double = 3600.0,
    override val sampleRateHz: Int = 44_100,
    override val channels: Int = 2,
    private val subFreqHz: Double = 60.0
) : StreamingTrackAudioReader {

    override val totalFrames: Long = (durationSeconds * sampleRateHz).toLong()
    private val beatIntervalFrames = ((60.0 / bpm) * sampleRateHz).toLong()

    override fun readFrames(
        startFrame: Long,
        framesToRead: Int,
        destinationBuffer: FloatArray,
        destinationOffset: Int
    ): Int {
        if (startFrame >= totalFrames || framesToRead <= 0) return 0
        val framesAvailable = (totalFrames - startFrame).coerceAtLeast(0L)
        val frames = minOf(framesToRead.toLong(), framesAvailable).toInt()

        var destIdx = destinationOffset
        for (i in 0 until frames) {
            val currentFrame = startFrame + i
            val timeSec = currentFrame.toDouble() / sampleRateHz

            // Sub bass sine wave
            val sub = (0.25 * sin(2.0 * PI * subFreqHz * timeSec)).toFloat()

            // Beat click at downbeat
            val posInBeat = if (beatIntervalFrames > 0) currentFrame % beatIntervalFrames else 0
            val click = if (posInBeat in 0..80) {
                (0.6f * (1.0f - posInBeat / 80.0f))
            } else {
                0.0f
            }

            val mixed = (sub + click).coerceIn(-1.0f, 1.0f)
            for (c in 0 until channels) {
                destinationBuffer[destIdx++] = mixed
            }
        }

        return frames
    }
}
