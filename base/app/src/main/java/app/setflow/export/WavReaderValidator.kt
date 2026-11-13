/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.export

import app.setflow.domain.AudioErrorCode
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Result of independent WAV read-back validation (Stage 11 Tasks 8 & Bug Trap 40).
 */
sealed interface WavValidationResult {
    data class Valid(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalFrames: Long,
        val durationUs: Long,
        val fileSizeBytes: Long,
        val peakAmplitude: Float,
        val sha256Hex: String
    ) : WavValidationResult

    data class Invalid(
        val errorCode: AudioErrorCode,
        val reason: String
    ) : WavValidationResult
}

/**
 * Independent binary parser and validator for exported WAV audio files.
 * Verifies RIFF/WAVE container integrity, format chunk parameters, data chunk bounds,
 * absence of NaN/Inf samples, and calculates the canonical data hash without logging music bytes.
 */
object WavReaderValidator {

    /**
     * Validates an exported WAV file by reading and verifying its headers and sample data.
     *
     * @param wavFile The audio file on disk to validate.
     * @param checkAllSamples If true, streams through the data chunk to verify finite samples and peak amplitude.
     */
    fun validate(wavFile: File, checkAllSamples: Boolean = true): WavValidationResult {
        if (!wavFile.exists() || !wavFile.isFile) {
            return WavValidationResult.Invalid(
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                reason = "File does not exist or is not a regular file: ${wavFile.absolutePath}"
            )
        }

        val fileSize = wavFile.length()
        if (fileSize < 44L) {
            return WavValidationResult.Invalid(
                errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                reason = "File too small to contain a standard WAV header ($fileSize bytes < 44 bytes)"
            )
        }

        try {
            RandomAccessFile(wavFile, "r").use { raf ->
                val headerBytes = ByteArray(44)
                raf.readFully(headerBytes)

                val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

                // 1. RIFF Header Magic
                val riffMagic = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
                if (riffMagic != "RIFF") {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Invalid RIFF magic descriptor: '$riffMagic' (expected 'RIFF')"
                    )
                }

                // 2. RIFF Chunk Size (file size - 8)
                val riffChunkSize = buf.int.toLong() and 0xFFFFFFFFL
                val expectedRiffSize = fileSize - 8L
                if (riffChunkSize != expectedRiffSize) {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Mismatched RIFF chunk size: header reports $riffChunkSize bytes, file indicates $expectedRiffSize bytes"
                    )
                }

                // 3. WAVE Format
                val waveMagic = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
                if (waveMagic != "WAVE") {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Invalid WAVE format descriptor: '$waveMagic' (expected 'WAVE')"
                    )
                }

                // 4. fmt Subchunk
                val fmtMagic = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
                if (fmtMagic != "fmt ") {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Invalid fmt subchunk descriptor: '$fmtMagic' (expected 'fmt ')"
                    )
                }

                val subchunk1Size = buf.int
                if (subchunk1Size < 16) {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "fmt subchunk size too small for PCM: $subchunk1Size < 16"
                    )
                }

                val audioFormat = buf.short.toInt() and 0xFFFF
                if (audioFormat != 1) { // 1 = PCM
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Unsupported audio format: $audioFormat (only linear PCM format 1 is supported)"
                    )
                }

                val numChannels = buf.short.toInt() and 0xFFFF
                if (numChannels != 1 && numChannels != 2) {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Unsupported channel count: $numChannels (expected 1 or 2)"
                    )
                }

                val sampleRate = buf.int
                if (sampleRate !in 8_000..192_000) {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Invalid or unsupported sample rate: $sampleRate Hz"
                    )
                }

                val byteRate = buf.int
                val blockAlign = buf.short.toInt() and 0xFFFF
                val bitsPerSample = buf.short.toInt() and 0xFFFF

                val expectedBlockAlign = numChannels * (bitsPerSample / 8)
                val expectedByteRate = sampleRate * expectedBlockAlign

                if (blockAlign != expectedBlockAlign) {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Mismatched block align: $blockAlign (expected $expectedBlockAlign)"
                    )
                }

                if (byteRate != expectedByteRate) {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Mismatched byte rate: $byteRate (expected $expectedByteRate)"
                    )
                }

                if (bitsPerSample != 16 && bitsPerSample != 24 && bitsPerSample != 32) {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Unsupported bit depth: $bitsPerSample bits (expected 16, 24, or 32)"
                    )
                }

                // Seek to start of next subchunk after fmt (fmt chunk begins at byte 12; header is 8 bytes, so payload ends at 20 + subchunk1Size)
                raf.seek(20L + subchunk1Size)

                // 5. Locate data Subchunk
                var dataMagic = ""
                var dataChunkSize = 0L
                var dataOffset = raf.filePointer

                // Read next chunk header
                while (raf.filePointer + 8 <= fileSize) {
                    val chunkHeader = ByteArray(8)
                    raf.readFully(chunkHeader)
                    val cBuf = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)
                    val id = ByteArray(4).also { cBuf.get(it) }.toString(Charsets.US_ASCII)
                    val sz = cBuf.int.toLong() and 0xFFFFFFFFL

                    if (id == "data") {
                        dataMagic = id
                        dataChunkSize = sz
                        dataOffset = raf.filePointer
                        break
                    } else {
                        // Skip unhandled chunk
                        raf.seek(raf.filePointer + sz + (sz and 1L))
                    }
                }

                if (dataMagic != "data") {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Missing 'data' subchunk descriptor"
                    )
                }

                val expectedDataSize = fileSize - dataOffset
                if (dataChunkSize != expectedDataSize) {
                    return WavValidationResult.Invalid(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        reason = "Mismatched data chunk size: header reports $dataChunkSize bytes, file has $expectedDataSize bytes"
                    )
                }

                val bytesPerFrame = numChannels * (bitsPerSample / 8)
                val totalFrames = dataChunkSize / bytesPerFrame
                val durationUs = (totalFrames * 1_000_000L) / sampleRate

                var peak = 0.0f
                val digest = MessageDigest.getInstance("SHA-256")

                if (checkAllSamples) {
                    raf.seek(dataOffset)
                    val bufferSize = 8192 * bytesPerFrame
                    val readBuf = ByteArray(bufferSize)
                    var remainingBytes = dataChunkSize

                    while (remainingBytes > 0) {
                        val toRead = minOf(remainingBytes, bufferSize.toLong()).toInt()
                        val bytesRead = raf.read(readBuf, 0, toRead)
                        if (bytesRead <= 0) break

                        digest.update(readBuf, 0, bytesRead)

                        val bb = ByteBuffer.wrap(readBuf, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                        val sampleCount = bytesRead / (bitsPerSample / 8)

                        for (s in 0 until sampleCount) {
                            val sample = when (bitsPerSample) {
                                16 -> bb.short / 32768.0f
                                24 -> {
                                    val b0 = bb.get().toInt() and 0xFF
                                    val b1 = bb.get().toInt() and 0xFF
                                    val b2 = bb.get().toInt()
                                    val raw = (b2 shl 16) or (b1 shl 8) or b0
                                    raw / 8388608.0f
                                }
                                32 -> bb.int / 2147483648.0f
                                else -> 0.0f
                            }

                            if (!sample.isFinite()) {
                                return WavValidationResult.Invalid(
                                    errorCode = AudioErrorCode.A07_DSP_NONFINITE,
                                    reason = "Non-finite sample found in data chunk"
                                )
                            }

                            val absVal = abs(sample)
                            if (absVal > peak) {
                                peak = absVal
                            }
                        }

                        remainingBytes -= bytesRead
                    }
                }

                val hashHex = digest.digest().joinToString("") { "%02x".format(it) }

                return WavValidationResult.Valid(
                    sampleRate = sampleRate,
                    channels = numChannels,
                    bitsPerSample = bitsPerSample,
                    totalFrames = totalFrames,
                    durationUs = durationUs,
                    fileSizeBytes = fileSize,
                    peakAmplitude = peak,
                    sha256Hex = hashHex
                )
            }
        } catch (e: Exception) {
            return WavValidationResult.Invalid(
                errorCode = AudioErrorCode.A10_EXPORT_IO_FAILURE,
                reason = "I/O error during WAV validation: ${e.message}"
            )
        }
    }
}
