/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.export

import app.setflow.domain.AudioErrorCode
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Streaming sample-accurate 16-bit stereo WAV file writer with 4GiB RIFF size capping,
 * non-finite sample sanitization, and headroom protection (Stage 11 Tasks 1, 3, 8).
 */
class WavWriter(
    val destinationFile: File,
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    val bitsPerSample: Int = 16
) : Closeable {

    private val raf: RandomAccessFile = RandomAccessFile(destinationFile, "rw")
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    val bytesPerSample: Int = bitsPerSample / 8
    val bytesPerFrame: Int = channels * bytesPerSample

    var totalFramesWritten: Long = 0L
        private set

    var peakAmplitude: Float = 0.0f
        private set

    var nonFiniteSamplesCount: Long = 0L
        private set

    private var isFinalized: Boolean = false
    private val byteBuffer = ByteBuffer.allocate(8192 * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)

    init {
        raf.setLength(0L)
        // Write placeholder 44-byte RIFF header; will be patched on close
        val placeholderHeader = ByteArray(HEADER_SIZE)
        raf.write(placeholderHeader)
    }

    /**
     * Streams a block of interleaved float samples [-1.0f, 1.0f].
     *
     * @param samples Interleaved float samples (length >= frames * channels).
     * @param frames Number of stereo frames to write.
     */
    @Synchronized
    fun writeFrames(samples: FloatArray, frames: Int) {
        check(!isFinalized) { "Cannot write frames to finalized WavWriter" }
        if (frames <= 0) return

        // 4GiB RIFF Limit Guard (Task 3, Bug Trap 40)
        val newTotalBytes = HEADER_SIZE + (totalFramesWritten + frames) * bytesPerFrame
        if (newTotalBytes > MAX_RIFF_BYTES) {
            throw AudioExportException(
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "WAV file exceeds 4GiB RIFF limit ($newTotalBytes bytes > $MAX_RIFF_BYTES max). Reduce set duration."
            )
        }

        var offset = 0
        var remaining = frames

        while (remaining > 0) {
            byteBuffer.clear()
            val framesToProcess = minOf(remaining, byteBuffer.capacity() / bytesPerFrame)
            val samplesToProcess = framesToProcess * channels

            for (i in 0 until samplesToProcess) {
                val raw = samples[offset + i]
                val sanitized = when {
                    raw.isNaN() || raw.isInfinite() -> {
                        nonFiniteSamplesCount++
                        0.0f
                    }
                    else -> raw.coerceIn(-1.0f, 1.0f)
                }

                val absVal = abs(sanitized)
                if (absVal > peakAmplitude) {
                    peakAmplitude = absVal
                }

                // 16-bit linear PCM conversion
                val pcmShort = (sanitized * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                byteBuffer.putShort(pcmShort)
            }

            val bytesToWrite = framesToProcess * bytesPerFrame
            raf.write(byteBuffer.array(), 0, bytesToWrite)
            digest.update(byteBuffer.array(), 0, bytesToWrite)

            offset += samplesToProcess
            remaining -= framesToProcess
            totalFramesWritten += framesToProcess
        }
    }

    /**
     * Finalizes the WAV header, patching exact chunk lengths, and flushes to disk.
     */
    @Synchronized
    fun finalizeHeader(): WavExportSummary {
        if (isFinalized) return summary()

        val dataSizeBytes = totalFramesWritten * bytesPerFrame
        val riffChunkSizeBytes = dataSizeBytes + 36L

        if (riffChunkSizeBytes > MAX_RIFF_BYTES) {
            throw AudioExportException(
                errorCode = AudioErrorCode.A12_TIMELINE_OVERFLOW,
                message = "WAV file exceeds 32-bit RIFF capacity"
            )
        }

        // Seek to beginning and write authoritative header
        raf.seek(0L)
        val headerBuf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        headerBuf.put("RIFF".toByteArray(Charsets.US_ASCII))
        headerBuf.putInt(riffChunkSizeBytes.toInt())
        headerBuf.put("WAVE".toByteArray(Charsets.US_ASCII))

        // "fmt " subchunk
        headerBuf.put("fmt ".toByteArray(Charsets.US_ASCII))
        headerBuf.putInt(16) // Subchunk1Size for PCM
        headerBuf.putShort(1) // AudioFormat: 1 = PCM
        headerBuf.putShort(channels.toShort())
        headerBuf.putInt(sampleRate)
        val byteRate = sampleRate * bytesPerFrame
        headerBuf.putInt(byteRate)
        headerBuf.putShort(bytesPerFrame.toShort())
        headerBuf.putShort(bitsPerSample.toShort())

        // "data" subchunk
        headerBuf.put("data".toByteArray(Charsets.US_ASCII))
        headerBuf.putInt(dataSizeBytes.toInt())

        raf.write(headerBuf.array())
        raf.fd.sync()
        isFinalized = true

        return summary()
    }

    private fun summary(): WavExportSummary {
        val hashHex = digest.digest().joinToString("") { "%02x".format(it) }
        val durationUs = (totalFramesWritten * 1_000_000L) / sampleRate
        return WavExportSummary(
            destinationPath = destinationFile.absolutePath,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            totalFrames = totalFramesWritten,
            durationUs = durationUs,
            fileSizeBytes = HEADER_SIZE + totalFramesWritten * bytesPerFrame,
            peakAmplitude = peakAmplitude,
            nonFiniteSamples = nonFiniteSamplesCount,
            sha256Hex = hashHex
        )
    }

    override fun close() {
        if (!isFinalized) {
            runCatching { finalizeHeader() }
        }
        raf.close()
    }

    companion object {
        const val HEADER_SIZE = 44
        const val MAX_RIFF_BYTES = 0xFFFFFFFFL - 8L // Max unsigned 32-bit RIFF chunk limit (~4 GiB)
    }
}

data class WavExportSummary(
    val destinationPath: String,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalFrames: Long,
    val durationUs: Long,
    val fileSizeBytes: Long,
    val peakAmplitude: Float,
    val nonFiniteSamples: Long,
    val sha256Hex: String
)

class AudioExportException(
    val errorCode: AudioErrorCode,
    override val message: String
) : RuntimeException(message)
