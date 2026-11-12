/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.io

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import app.myspoti.automix.Analyzer
import app.myspoti.automix.Features
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.MetadataNormalizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Container and codec metadata verified from binary headers (never from filename extensions).
 */
data class InspectedAudioContainer(
    val mimeType: String,
    val codecDescription: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val durationUs: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkMimeType: String? = null,
    val artworkWidth: Int = 0,
    val artworkHeight: Int = 0,
    val artworkBytes: ByteArray? = null,
    val artworkOversizedSkipped: Boolean = false,
    val wavDataOffset: Long = -1L,
    val wavDataSize: Long = -1L,
    val wavFormatCode: Int = 0
)

/**
 * Result of inspecting + decoding a verification window (default 10s) of a local audio source.
 */
sealed interface AudioVerificationResult {
    data class Verified(
        val container: InspectedAudioContainer,
        val decodedFramesAt22050Hz: Int,
        val decodedDurationUs: Long,
        val peakAbsSample: Float,
        val verifiedWindowAudioKey: String,
        val fullAudioKey: String? = null
    ) : AudioVerificationResult

    data class Failed(
        val errorCode: AudioErrorCode,
        val reason: String,
        val detectedMimeType: String? = null
    ) : AudioVerificationResult
}

/**
 * Exception carrying an explicit [AudioErrorCode] for container or decode failures.
 */
class AudioVerificationException(
    val errorCode: AudioErrorCode,
    override val message: String,
    val detectedMimeType: String? = null,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Pure Kotlin binary container inspector for WAV, FLAC, MP3, M4A/MP4, and OGG.
 * Verifies magic bytes, sample rate, channel count, duration, DRM flags, and bounded metadata/artwork.
 */
object AudioContainerInspector {
    const val MIN_DURATION_US = 1_000_000L // 1.0 second minimum
    const val MAX_DURATION_US = 24L * 3600L * 1_000_000L // 24 hours max (D11)
    const val MIN_SAMPLE_RATE_HZ = 8_000
    const val MAX_SAMPLE_RATE_HZ = 96_000 // S26: >96k (e.g. 192k) fails with explicit reason

    @Throws(AudioVerificationException::class, InterruptedException::class)
    fun inspect(
        channel: SeekableTrackChannel,
        cancelled: () -> Boolean = { false }
    ): InspectedAudioContainer {
        if (cancelled()) throw InterruptedException("Container inspection cancelled")
        val size = channel.sizeBytes
        if (size <= 0L) {
            throw AudioVerificationException(
                AudioErrorCode.A01_SOURCE_MISSING,
                "Audio document is empty (0 bytes)"
            )
        }
        if (size < 16L) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Audio document too small to contain a valid header ($size bytes)"
            )
        }

        val magic = ByteArray(16)
        val n = channel.readAt(0L, magic, 0, 16)
        if (n < 12) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Could not read 12-byte magic header"
            )
        }

        val container = when {
            isAscii(magic, 0, "RIFF") && isAscii(magic, 8, "WAVE") ->
                inspectWav(channel, size, cancelled)
            isAscii(magic, 0, "fLaC") ->
                inspectFlac(channel, size, cancelled)
            isAscii(magic, 0, "OggS") ->
                inspectOgg(channel, size, cancelled)
            isAscii(magic, 4, "ftyp") ->
                inspectMp4(channel, size, cancelled)
            isAscii(magic, 0, "ID3") || isMpegSync(magic, 0) ->
                inspectMp3(channel, size, cancelled)
            else -> throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Unrecognized or corrupted audio container magic bytes"
            )
        }

        validateContainerBounds(container)
        return container
    }

    private fun validateContainerBounds(c: InspectedAudioContainer) {
        if (c.sampleRateHz !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Unsupported sample rate ${c.sampleRateHz} Hz (supported range: $MIN_SAMPLE_RATE_HZ..$MAX_SAMPLE_RATE_HZ Hz)",
                detectedMimeType = c.mimeType
            )
        }
        if (c.channelCount !in 1..2) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Unsupported channel count ${c.channelCount} (only mono and stereo are supported)",
                detectedMimeType = c.mimeType
            )
        }
        if (c.durationUs < MIN_DURATION_US) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Track duration (${c.durationUs / 1000} ms) is shorter than minimum 1000 ms",
                detectedMimeType = c.mimeType
            )
        }
        if (c.durationUs > MAX_DURATION_US) {
            throw AudioVerificationException(
                AudioErrorCode.A12_TIMELINE_OVERFLOW,
                "Track duration exceeds 24-hour maximum (${c.durationUs} us)",
                detectedMimeType = c.mimeType
            )
        }
    }

    private fun inspectWav(
        channel: SeekableTrackChannel,
        fileSize: Long,
        cancelled: () -> Boolean
    ): InspectedAudioContainer {
        var pos = 12L
        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1L
        var dataSize = -1L
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        val headerBuf = ByteArray(8)
        while (pos + 8 <= fileSize) {
            if (cancelled()) throw InterruptedException("WAV inspection cancelled")
            if (channel.readAt(pos, headerBuf, 0, 8) < 8) break
            val chunkId = String(headerBuf, 0, 4, StandardCharsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(headerBuf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val payloadPos = pos + 8L

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16L || payloadPos + chunkSize > fileSize) {
                        throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Corrupt WAV fmt chunk", "audio/wav")
                    }
                    val fmtBytes = ByteArray(minOf(chunkSize.toInt(), 40))
                    val r = channel.readAt(payloadPos, fmtBytes, 0, fmtBytes.size)
                    if (r < 16) throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Truncated WAV fmt chunk", "audio/wav")
                    val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                    val rawFormat = bb.short.toInt() and 0xFFFF
                    channels = bb.short.toInt() and 0xFFFF
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt() and 0xFFFF
                    audioFormat = if (rawFormat == 0xFFFE && r >= 40) {
                        bb.position(24)
                        bb.short.toInt() and 0xFFFF
                    } else {
                        rawFormat
                    }
                }
                "data" -> {
                    dataOffset = payloadPos
                    dataSize = minOf(chunkSize, (fileSize - payloadPos).coerceAtLeast(0L))
                }
                "LIST" -> {
                    if (chunkSize in 4..65536 && payloadPos + chunkSize <= fileSize) {
                        val listBytes = ByteArray(chunkSize.toInt())
                        if (channel.readAt(payloadPos, listBytes, 0, listBytes.size) == listBytes.size &&
                            isAscii(listBytes, 0, "INFO")
                        ) {
                            var lp = 4
                            while (lp + 8 <= listBytes.size) {
                                val subId = String(listBytes, lp, 4, StandardCharsets.US_ASCII)
                                val subLen = ByteBuffer.wrap(listBytes, lp + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                                if (subLen < 0 || lp + 8 + subLen > listBytes.size) break
                                val text = String(listBytes, lp + 8, subLen, StandardCharsets.UTF_8).trimEnd('\u0000')
                                when (subId) {
                                    "INAM" -> title = MetadataNormalizer.sanitizeText(text).takeIf { it.isNotEmpty() }
                                    "IART" -> artist = MetadataNormalizer.sanitizeText(text).takeIf { it.isNotEmpty() }
                                    "IPRD" -> album = MetadataNormalizer.sanitizeText(text).takeIf { it.isNotEmpty() }
                                }
                                lp += 8 + subLen + (subLen and 1)
                            }
                        }
                    }
                }
            }
            val padded = chunkSize + (chunkSize and 1L)
            if (padded < 0 || payloadPos + padded <= pos) break
            pos = payloadPos + padded
        }

        if (audioFormat != 1 && audioFormat != 3) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Unsupported WAV format tag $audioFormat (only PCM=1 and IEEE_FLOAT=3 are supported)",
                "audio/wav"
            )
        }
        if (audioFormat == 1 && bitsPerSample !in listOf(16, 24, 32)) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Unsupported WAV PCM bit depth $bitsPerSample-bit (supported: 16, 24, 32)",
                "audio/wav"
            )
        }
        if (audioFormat == 3 && bitsPerSample != 32) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Unsupported WAV float bit depth $bitsPerSample-bit (expected 32-bit float)",
                "audio/wav"
            )
        }
        if (dataOffset < 0L || dataSize <= 0L) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "WAV file is missing a non-empty data chunk",
                "audio/wav"
            )
        }
        val bytesPerFrame = (channels * (bitsPerSample / 8)).coerceAtLeast(1)
        val totalFrames = dataSize / bytesPerFrame
        if (totalFrames <= 0L || sampleRate <= 0) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "WAV file has 0 audio frames",
                "audio/wav"
            )
        }
        val durationUs = (totalFrames * 1_000_000L) / sampleRate
        val codecDesc = if (audioFormat == 3) "WAV IEEE Float ${bitsPerSample}-bit" else "WAV PCM ${bitsPerSample}-bit"
        return InspectedAudioContainer(
            mimeType = "audio/wav",
            codecDescription = codecDesc,
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitsPerSample = bitsPerSample,
            durationUs = durationUs,
            title = title,
            artist = artist,
            album = album,
            wavDataOffset = dataOffset,
            wavDataSize = dataSize,
            wavFormatCode = audioFormat
        )
    }

    private fun inspectFlac(
        channel: SeekableTrackChannel,
        fileSize: Long,
        cancelled: () -> Boolean
    ): InspectedAudioContainer {
        var pos = 4L
        var isLast = false
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var totalSamples = 0L
        var streamInfoFound = false
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var artMime: String? = null
        var artWidth = 0
        var artHeight = 0
        var artBytes: ByteArray? = null
        var artOversized = false

        val blockHeader = ByteArray(4)
        var blockCount = 0
        while (!isLast && pos + 4 <= fileSize && blockCount < 64) {
            if (cancelled()) throw InterruptedException("FLAC inspection cancelled")
            blockCount++
            if (channel.readAt(pos, blockHeader, 0, 4) < 4) break
            val b0 = blockHeader[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val blockLen = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)
            val payloadPos = pos + 4L
            if (blockLen < 0 || payloadPos + blockLen > fileSize) {
                throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Truncated FLAC metadata block", "audio/flac")
            }

            when (blockType) {
                0 -> { // STREAMINFO (34 bytes)
                    if (blockLen < 34) throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Invalid FLAC STREAMINFO length $blockLen", "audio/flac")
                    val si = ByteArray(34)
                    if (channel.readAt(payloadPos, si, 0, 34) < 34) {
                        throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Truncated FLAC STREAMINFO", "audio/flac")
                    }
                    // Bytes 10..17 (64 bits): 20 bits sampleRate, 3 bits (channels-1), 5 bits (bps-1), 36 bits totalSamples
                    val packed = ByteBuffer.wrap(si, 10, 8).order(ByteOrder.BIG_ENDIAN).long
                    sampleRate = ((packed ushr 44) and 0xFFFFFL).toInt()
                    channels = (((packed ushr 41) and 0x7L).toInt()) + 1
                    bitsPerSample = (((packed ushr 36) and 0x1FL).toInt()) + 1
                    totalSamples = packed and 0xFFFFFFFFFL
                    streamInfoFound = true
                }
                4 -> { // VORBIS_COMMENT
                    if (blockLen in 8..(256 * 1024)) {
                        val vc = ByteArray(blockLen)
                        if (channel.readAt(payloadPos, vc, 0, blockLen) == blockLen) {
                            val parsed = parseVorbisComments(vc)
                            title = parsed["TITLE"] ?: title
                            artist = parsed["ARTIST"] ?: artist
                            album = parsed["ALBUM"] ?: album
                        }
                    }
                }
                6 -> { // PICTURE
                    if (blockLen > MetadataNormalizer.MAX_EMBEDDED_ARTWORK_BYTES + 1024) {
                        artOversized = true
                    } else if (blockLen > 32) {
                        val pic = ByteArray(blockLen)
                        if (channel.readAt(payloadPos, pic, 0, blockLen) == blockLen) {
                            val bb = ByteBuffer.wrap(pic).order(ByteOrder.BIG_ENDIAN)
                            bb.int // pictureType
                            val mimeLen = bb.int
                            if (mimeLen in 0..128 && bb.remaining() >= mimeLen + 20) {
                                val mBytes = ByteArray(mimeLen)
                                bb.get(mBytes)
                                val mStr = String(mBytes, StandardCharsets.US_ASCII)
                                val descLen = bb.int
                                if (descLen >= 0 && bb.remaining() >= descLen + 20) {
                                    bb.position(bb.position() + descLen)
                                    val w = bb.int
                                    val h = bb.int
                                    bb.int // depth
                                    bb.int // colors
                                    val dataLen = bb.int
                                    if (dataLen > MetadataNormalizer.MAX_EMBEDDED_ARTWORK_BYTES) {
                                        artOversized = true
                                    } else if (dataLen > 0 && bb.remaining() >= dataLen && artBytes == null) {
                                        val img = ByteArray(dataLen)
                                        bb.get(img)
                                        artMime = mStr
                                        artWidth = w
                                        artHeight = h
                                        artBytes = img
                                    }
                                }
                            }
                        }
                    }
                }
            }
            pos = payloadPos + blockLen
        }

        if (!streamInfoFound || sampleRate <= 0 || totalSamples <= 0L) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "FLAC file missing valid STREAMINFO or has 0 samples",
                "audio/flac"
            )
        }
        val durationUs = (totalSamples * 1_000_000L) / sampleRate
        return InspectedAudioContainer(
            mimeType = "audio/flac",
            codecDescription = "FLAC ${bitsPerSample}-bit",
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitsPerSample = bitsPerSample,
            durationUs = durationUs,
            title = title,
            artist = artist,
            album = album,
            artworkMimeType = artMime,
            artworkWidth = artWidth,
            artworkHeight = artHeight,
            artworkBytes = artBytes,
            artworkOversizedSkipped = artOversized
        )
    }

    private fun parseVorbisComments(vc: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        runCatching {
            val bb = ByteBuffer.wrap(vc).order(ByteOrder.LITTLE_ENDIAN)
            val vendorLen = bb.int
            if (vendorLen < 0 || bb.remaining() < vendorLen + 4) return result
            bb.position(bb.position() + vendorLen)
            val count = bb.int.coerceIn(0, 256)
            for (i in 0 until count) {
                if (bb.remaining() < 4) break
                val len = bb.int
                if (len <= 0 || bb.remaining() < len) break
                val strBytes = ByteArray(minOf(len, 2048))
                bb.get(strBytes)
                if (len > strBytes.size) bb.position(bb.position() + (len - strBytes.size))
                val entry = String(strBytes, StandardCharsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val key = entry.substring(0, eq).uppercase()
                    val value = MetadataNormalizer.sanitizeText(entry.substring(eq + 1))
                    if (value.isNotEmpty() && key !in result) {
                        result[key] = value
                    }
                }
            }
        }
        return result
    }

    private fun inspectMp3(
        channel: SeekableTrackChannel,
        fileSize: Long,
        cancelled: () -> Boolean
    ): InspectedAudioContainer {
        var audioOffset = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var artMime: String? = null
        var artBytes: ByteArray? = null
        var artOversized = false

        val head10 = ByteArray(10)
        if (channel.readAt(0L, head10, 0, 10) == 10 && isAscii(head10, 0, "ID3")) {
            val verMajor = head10[3].toInt() and 0xFF
            val tagSize = synchsafeInt(head10, 6)
            audioOffset = (10L + tagSize).coerceAtMost(fileSize)
            if (tagSize > 0 && verMajor in 3..4) {
                val readCap = minOf(tagSize, MetadataNormalizer.MAX_EMBEDDED_ARTWORK_BYTES + 128 * 1024)
                val tagBuf = ByteArray(readCap)
                val actual = channel.readAt(10L, tagBuf, 0, readCap)
                if (actual > 10) {
                    var tp = 0
                    while (tp + 10 <= actual) {
                        if (cancelled()) throw InterruptedException("ID3 inspection cancelled")
                        val frameId = String(tagBuf, tp, 4, StandardCharsets.US_ASCII)
                        if (frameId.any { it !in 'A'..'Z' && it !in '0'..'9' }) break
                        val frameSize = if (verMajor == 4) {
                            synchsafeInt(tagBuf, tp + 4)
                        } else {
                            ByteBuffer.wrap(tagBuf, tp + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                        }
                        if (frameSize <= 0) break
                        if (frameSize > MetadataNormalizer.MAX_EMBEDDED_ARTWORK_BYTES && frameId == "APIC") {
                            artOversized = true
                        }
                        if (tp + 10 + frameSize > actual) break
                        val payloadStart = tp + 10
                        when (frameId) {
                            "TIT2" -> title = decodeId3Text(tagBuf, payloadStart, frameSize) ?: title
                            "TPE1" -> artist = decodeId3Text(tagBuf, payloadStart, frameSize) ?: artist
                            "TALB" -> album = decodeId3Text(tagBuf, payloadStart, frameSize) ?: album
                            "APIC" -> {
                                if (frameSize <= MetadataNormalizer.MAX_EMBEDDED_ARTWORK_BYTES && artBytes == null) {
                                    val parsedPic = decodeId3Apic(tagBuf, payloadStart, frameSize)
                                    if (parsedPic != null) {
                                        artMime = parsedPic.first
                                        artBytes = parsedPic.second
                                    }
                                }
                            }
                        }
                        tp += 10 + frameSize
                    }
                }
            }
        }

        // Scan up to 64 KiB from audioOffset for a valid MPEG frame sync
        val scanLen = minOf(65536L, (fileSize - audioOffset).coerceAtLeast(0L)).toInt()
        if (scanLen < 4) {
            throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "MP3 contains no audio frames after ID3 tag", "audio/mpeg")
        }
        val scanBuf = ByteArray(scanLen)
        val readScan = channel.readAt(audioOffset, scanBuf, 0, scanLen)
        var framePos = -1
        var sampleRate = 0
        var channels = 2
        var bitrateKbps = 0
        var samplesPerFrame = 1152
        var xingFrames = -1L

        for (i in 0..readScan - 4) {
            val h = parseMpegHeader(scanBuf, i) ?: continue
            // Confirm either next frame sync matches or Xing/Info header is present inside this frame
            val xing = parseXingFrames(scanBuf, i, readScan, h)
            val nextPos = i + h.frameBytes
            val confirmed = xing > 0 || (nextPos + 4 <= readScan && parseMpegHeader(scanBuf, nextPos)?.sampleRate == h.sampleRate) || nextPos >= readScan
            if (confirmed) {
                framePos = i
                sampleRate = h.sampleRate
                channels = h.channels
                bitrateKbps = h.bitrateKbps
                samplesPerFrame = h.samplesPerFrame
                xingFrames = xing
                break
            }
        }

        if (framePos < 0 || sampleRate <= 0 || bitrateKbps <= 0) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "No valid MPEG audio frame found in stream",
                "audio/mpeg"
            )
        }

        val durationUs = if (xingFrames > 0L) {
            (xingFrames * samplesPerFrame * 1_000_000L) / sampleRate
        } else {
            val audioBytes = (fileSize - (audioOffset + framePos)).coerceAtLeast(0L)
            (audioBytes * 8L * 1_000L) / bitrateKbps
        }

        return InspectedAudioContainer(
            mimeType = "audio/mpeg",
            codecDescription = "MPEG-1/2 Audio Layer III (${bitrateKbps} kbps)",
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitsPerSample = 16,
            durationUs = durationUs,
            title = title,
            artist = artist,
            album = album,
            artworkMimeType = artMime,
            artworkBytes = artBytes,
            artworkOversizedSkipped = artOversized
        )
    }

    private data class MpegFrameHeader(
        val versionId: Int,
        val layer: Int,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val channels: Int,
        val samplesPerFrame: Int,
        val frameBytes: Int
    )

    private fun parseMpegHeader(buf: ByteArray, offset: Int): MpegFrameHeader? {
        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF
        val b2 = buf[offset + 2].toInt() and 0xFF
        val b3 = buf[offset + 3].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val versionId = (b1 ushr 3) and 0x03 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        if (versionId == 1) return null
        val layerBits = (b1 ushr 1) and 0x03 // 1 = Layer III
        if (layerBits != 1) return null // require Layer III for MP3
        val bitrateIdx = (b2 ushr 4) and 0x0F
        if (bitrateIdx == 0 || bitrateIdx == 15) return null
        val srIdx = (b2 ushr 2) and 0x03
        if (srIdx == 3) return null
        val padding = (b2 ushr 1) and 0x01
        val channelMode = (b3 ushr 6) and 0x03

        val baseSr = when (srIdx) {
            0 -> 44100
            1 -> 48000
            else -> 32000
        }
        val sampleRate = when (versionId) {
            3 -> baseSr
            2 -> baseSr / 2
            else -> baseSr / 4
        }
        val mpeg1Bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val mpeg2Bitrates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        val bitrateKbps = if (versionId == 3) mpeg1Bitrates[bitrateIdx] else mpeg2Bitrates[bitrateIdx]
        if (bitrateKbps <= 0) return null
        val samplesPerFrame = if (versionId == 3) 1152 else 576
        val coeff = if (versionId == 3) 144 else 72
        val frameBytes = (coeff * bitrateKbps * 1000) / sampleRate + padding
        if (frameBytes < 24) return null
        val channels = if (channelMode == 3) 1 else 2
        return MpegFrameHeader(versionId, 3, bitrateKbps, sampleRate, channels, samplesPerFrame, frameBytes)
    }

    private fun parseXingFrames(buf: ByteArray, frameStart: Int, limit: Int, h: MpegFrameHeader): Long {
        val sideInfoSize = if (h.versionId == 3) {
            if (h.channels == 1) 17 else 32
        } else {
            if (h.channels == 1) 9 else 17
        }
        val xingOffset = frameStart + 4 + sideInfoSize
        if (xingOffset + 12 <= limit && (isAscii(buf, xingOffset, "Xing") || isAscii(buf, xingOffset, "Info"))) {
            val flags = ByteBuffer.wrap(buf, xingOffset + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            if ((flags and 1) != 0) {
                return ByteBuffer.wrap(buf, xingOffset + 8, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            }
        }
        return -1L
    }

    private fun inspectMp4(
        channel: SeekableTrackChannel,
        fileSize: Long,
        cancelled: () -> Boolean
    ): InspectedAudioContainer {
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 16
        var durationUs = 0L
        var codecFourCc = ""
        var drmDetected = false
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        fun parseBoxes(start: Long, end: Long, depth: Int) {
            if (depth > 10) return
            var pos = start
            val hdr = ByteArray(16)
            while (pos + 8 <= end) {
                if (cancelled()) throw InterruptedException("MP4 inspection cancelled")
                val r = channel.readAt(pos, hdr, 0, minOf(16, (end - pos).toInt()))
                if (r < 8) break
                val size32 = ByteBuffer.wrap(hdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val type = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)
                val (boxSize, headerLen) = when (size32) {
                    1L -> {
                        if (r < 16) return
                        ByteBuffer.wrap(hdr, 8, 8).order(ByteOrder.BIG_ENDIAN).long to 16L
                    }
                    0L -> (end - pos) to 8L
                    else -> size32 to 8L
                }
                if (boxSize < headerLen || pos + boxSize > end) break
                val payloadPos = pos + headerLen
                val payloadSize = boxSize - headerLen

                when (type) {
                    "moov", "trak", "mdia", "minf", "stbl", "udta", "ilst" ->
                        parseBoxes(payloadPos, pos + boxSize, depth + 1)
                    "meta" -> {
                        if (payloadSize > 4) parseBoxes(payloadPos + 4, pos + boxSize, depth + 1)
                    }
                    "enca", "sinf", "schi", "pssh" -> {
                        drmDetected = true
                    }
                    "mdhd" -> {
                        if (payloadSize >= 20) {
                            val b = ByteArray(minOf(payloadSize.toInt(), 32))
                            if (channel.readAt(payloadPos, b, 0, b.size) >= 20) {
                                val version = b[0].toInt() and 0xFF
                                val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
                                if (version == 1 && b.size >= 32) {
                                    bb.position(20)
                                    val timeScale = bb.int.toLong() and 0xFFFFFFFFL
                                    val dur = bb.long
                                    if (timeScale > 0 && dur > 0) durationUs = (dur * 1_000_000L) / timeScale
                                } else {
                                    bb.position(12)
                                    val timeScale = bb.int.toLong() and 0xFFFFFFFFL
                                    val dur = bb.int.toLong() and 0xFFFFFFFFL
                                    if (timeScale > 0 && dur > 0) durationUs = (dur * 1_000_000L) / timeScale
                                }
                            }
                        }
                    }
                    "stsd" -> {
                        if (payloadSize >= 24) {
                            val b = ByteArray(minOf(payloadSize.toInt(), 256))
                            val n = channel.readAt(payloadPos, b, 0, b.size)
                            if (n >= 44) {
                                val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.BIG_ENDIAN)
                                bb.int // version/flags
                                val entries = bb.int
                                if (entries >= 1 && bb.remaining() >= 36) {
                                    val entrySize = bb.int
                                    val entryType = ByteArray(4).also { bb.get(it) }.toString(StandardCharsets.ISO_8859_1)
                                    if (entryType == "enca") drmDetected = true
                                    if (entryType == "mp4a" || entryType == "alac") {
                                        codecFourCc = entryType
                                        bb.position(bb.position() + 6) // reserved
                                        bb.short // dataRefIndex
                                        val stsdVer = bb.short.toInt() and 0xFFFF
                                        bb.position(bb.position() + 6) // revision + vendor
                                        channels = bb.short.toInt() and 0xFFFF
                                        bitsPerSample = bb.short.toInt() and 0xFFFF
                                        bb.short // compressionId
                                        bb.short // packetSize
                                        val srFixed = bb.int.toLong() and 0xFFFFFFFFL
                                        val sr = (srFixed ushr 16).toInt()
                                        if (sr > 0) sampleRate = sr
                                        // For ALAC, check nested alac magic cookie if present
                                        if (entryType == "alac" && stsdVer == 0 && entrySize >= 60 && n >= 60) {
                                            // ALACSpecificConfig has sampleRate in last 4 bytes
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "\u00a9nam", "\u00a9ART", "\u00a9alb" -> {
                        val txt = extractIlstDataText(channel, payloadPos, payloadSize)
                        when (type) {
                            "\u00a9nam" -> title = txt ?: title
                            "\u00a9ART" -> artist = txt ?: artist
                            "\u00a9alb" -> album = txt ?: album
                        }
                    }
                }
                pos += boxSize
            }
        }

        parseBoxes(0L, fileSize, 0)

        if (drmDetected) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "DRM-protected or encrypted M4A/MP4 audio stream is not supported",
                "audio/mp4"
            )
        }
        if (codecFourCc == "alac") {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Unsupported codec ALAC in M4A container (convert to FLAC, WAV, or AAC M4A)",
                "audio/alac"
            )
        }
        if (codecFourCc != "mp4a" || sampleRate <= 0 || durationUs <= 0L) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "M4A/MP4 container does not contain a valid AAC (mp4a) audio track",
                "audio/mp4"
            )
        }

        return InspectedAudioContainer(
            mimeType = "audio/mp4",
            codecDescription = "AAC-LC (mp4a)",
            sampleRateHz = sampleRate,
            channelCount = channels.coerceIn(1, 2),
            bitsPerSample = bitsPerSample.takeIf { it > 0 } ?: 16,
            durationUs = durationUs,
            title = title,
            artist = artist,
            album = album
        )
    }

    private fun extractIlstDataText(channel: SeekableTrackChannel, pos: Long, size: Long): String? {
        if (size !in 16..4096) return null
        val buf = ByteArray(size.toInt())
        if (channel.readAt(pos, buf, 0, buf.size) != buf.size) return null
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
        val dataSize = bb.int
        val dataType = ByteArray(4).also { bb.get(it) }.toString(StandardCharsets.ISO_8859_1)
        if (dataType != "data" || dataSize < 16 || dataSize > buf.size) return null
        val textLen = dataSize - 16
        if (textLen <= 0) return null
        val raw = String(buf, 16, textLen, StandardCharsets.UTF_8)
        return MetadataNormalizer.sanitizeText(raw).takeIf { it.isNotEmpty() }
    }

    private fun inspectOgg(
        channel: SeekableTrackChannel,
        fileSize: Long,
        cancelled: () -> Boolean
    ): InspectedAudioContainer {
        if (cancelled()) throw InterruptedException("OGG inspection cancelled")
        val firstBuf = ByteArray(minOf(4096L, fileSize).toInt())
        val n = channel.readAt(0L, firstBuf, 0, firstBuf.size)
        if (n < 58 || !isAscii(firstBuf, 0, "OggS")) {
            throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Invalid Ogg page header", "audio/ogg")
        }
        val numSegments = firstBuf[26].toInt() and 0xFF
        val packetStart = 27 + numSegments
        if (packetStart + 16 > n) {
            throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Truncated Ogg identification packet", "audio/ogg")
        }
        var sampleRate = 0
        var channels = 0
        var codecDesc = "Ogg Vorbis"
        if (firstBuf[packetStart].toInt() == 1 && isAscii(firstBuf, packetStart + 1, "vorbis")) {
            val bb = ByteBuffer.wrap(firstBuf, packetStart + 7, 9).order(ByteOrder.LITTLE_ENDIAN)
            bb.int // version
            channels = bb.get().toInt() and 0xFF
            sampleRate = bb.int
            codecDesc = "Ogg Vorbis"
        } else if (isAscii(firstBuf, packetStart, "OpusHead")) {
            val bb = ByteBuffer.wrap(firstBuf, packetStart + 8, 8).order(ByteOrder.LITTLE_ENDIAN)
            bb.get() // version
            channels = bb.get().toInt() and 0xFF
            bb.short // preSkip
            sampleRate = 48000 // Opus always decodes at 48 kHz
            codecDesc = "Ogg Opus"
        } else {
            throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Unsupported Ogg stream codec", "audio/ogg")
        }

        // Scan tail for last OggS granule position
        val tailLen = minOf(65536L, fileSize).toInt()
        val tailBuf = ByteArray(tailLen)
        val tr = channel.readAt(fileSize - tailLen, tailBuf, 0, tailLen)
        var maxGranule = -1L
        for (i in tr - 27 downTo 0) {
            if (isAscii(tailBuf, i, "OggS")) {
                val g = ByteBuffer.wrap(tailBuf, i + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
                if (g > 0) {
                    maxGranule = g
                    break
                }
            }
        }
        if (maxGranule <= 0L || sampleRate <= 0) {
            throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Could not determine valid Ogg stream duration", "audio/ogg")
        }
        val durationUs = (maxGranule * 1_000_000L) / sampleRate
        return InspectedAudioContainer(
            mimeType = "audio/ogg",
            codecDescription = codecDesc,
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitsPerSample = 16,
            durationUs = durationUs
        )
    }

    private fun synchsafeInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0x7F) shl 21) or
            ((buf[offset + 1].toInt() and 0x7F) shl 14) or
            ((buf[offset + 2].toInt() and 0x7F) shl 7) or
            (buf[offset + 3].toInt() and 0x7F)

    private fun decodeId3Text(buf: ByteArray, offset: Int, len: Int): String? {
        if (len <= 1) return null
        val enc = buf[offset].toInt() and 0xFF
        val charset: Charset = when (enc) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.UTF_8
        }
        val raw = String(buf, offset + 1, len - 1, charset).trimEnd('\u0000')
        return MetadataNormalizer.sanitizeText(raw).takeIf { it.isNotEmpty() }
    }

    private fun decodeId3Apic(buf: ByteArray, offset: Int, len: Int): Pair<String, ByteArray>? {
        if (len <= 16) return null
        var p = offset + 1 // skip encoding byte
        val end = offset + len
        val mimeStart = p
        while (p < end && buf[p].toInt() != 0) p++
        if (p >= end) return null
        val mime = String(buf, mimeStart, p - mimeStart, StandardCharsets.ISO_8859_1).ifEmpty { "image/jpeg" }
        p++ // null terminator
        if (p >= end) return null
        p++ // picture type byte
        // skip description (null-terminated)
        while (p < end && buf[p].toInt() != 0) p++
        while (p < end && buf[p].toInt() == 0) p++
        val imgLen = end - p
        if (imgLen <= 0 || imgLen > MetadataNormalizer.MAX_EMBEDDED_ARTWORK_BYTES) return null
        return mime to buf.copyOfRange(p, end)
    }

    private fun isAscii(buf: ByteArray, offset: Int, expected: String): Boolean {
        if (offset + expected.length > buf.size) return false
        for (i in expected.indices) {
            if ((buf[offset + i].toInt() and 0xFF) != expected[i].code) return false
        }
        return true
    }

    private fun isMpegSync(buf: ByteArray, offset: Int): Boolean =
        offset + 2 <= buf.size &&
            (buf[offset].toInt() and 0xFF) == 0xFF &&
            (buf[offset + 1].toInt() and 0xE0) == 0xE0
}

/**
 * Verifies actual audio decodability by decoding a bounded verification window (default 10.0 seconds)
 * to 22.05 kHz mid/side signals and computing the canonical SHA-256 `audioKey` via [Analyzer.signalKey].
 */
object AudioDecodeVerifier {
    const val DEFAULT_VERIFY_WINDOW_SECONDS = 10
    const val TARGET_RATE_HZ = Features.RATE // 22050 Hz

    fun verifyAndDecode(
        channel: SeekableTrackChannel,
        maxSeconds: Int = DEFAULT_VERIFY_WINDOW_SECONDS,
        computeFullAudioKey: Boolean = false,
        cancelled: () -> Boolean = { false }
    ): AudioVerificationResult {
        return try {
            val container = AudioContainerInspector.inspect(channel, cancelled)
            val windowSeconds = if (computeFullAudioKey) 15 * 60 else maxSeconds.coerceAtLeast(1)
            val (mid, side) = decodeWindowToMidSide22050(channel, container, windowSeconds, cancelled)
            if (mid.isEmpty() || side.isEmpty() || mid.size != side.size) {
                return AudioVerificationResult.Failed(
                    errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                    reason = "Decoder produced 0 output frames for ${container.mimeType}",
                    detectedMimeType = container.mimeType
                )
            }
            var peak = 0f
            for (i in mid.indices) {
                val m = mid[i]
                val s = side[i]
                if (!m.isFinite() || !s.isFinite()) {
                    return AudioVerificationResult.Failed(
                        errorCode = AudioErrorCode.A07_DSP_NONFINITE,
                        reason = "Decoded PCM contained non-finite (NaN/Infinite) sample at frame $i",
                        detectedMimeType = container.mimeType
                    )
                }
                val absM = kotlin.math.abs(m)
                if (absM > peak) peak = absM
            }
            val key = Analyzer.signalKey(mid, side)
            val decodedDurationUs = (mid.size.toLong() * 1_000_000L) / TARGET_RATE_HZ
            AudioVerificationResult.Verified(
                container = container,
                decodedFramesAt22050Hz = mid.size,
                decodedDurationUs = decodedDurationUs,
                peakAbsSample = peak,
                verifiedWindowAudioKey = key,
                fullAudioKey = if (computeFullAudioKey) key else null
            )
        } catch (e: AudioVerificationException) {
            AudioVerificationResult.Failed(
                errorCode = e.errorCode,
                reason = e.message,
                detectedMimeType = e.detectedMimeType
            )
        } catch (e: InterruptedException) {
            AudioVerificationResult.Failed(
                errorCode = AudioErrorCode.A11_CANCELLED,
                reason = "Audio decode verification cancelled"
            )
        } catch (e: SecurityException) {
            AudioVerificationResult.Failed(
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                reason = e.message ?: "Read permission denied during decode"
            )
        } catch (e: IOException) {
            AudioVerificationResult.Failed(
                errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                reason = e.message ?: "I/O or codec failure during decode verification"
            )
        }
    }

    private fun decodeWindowToMidSide22050(
        channel: SeekableTrackChannel,
        container: InspectedAudioContainer,
        maxSeconds: Int,
        cancelled: () -> Boolean
    ): Pair<FloatArray, FloatArray> {
        if (container.mimeType == "audio/wav") {
            return decodeWavWindow(channel, container, maxSeconds, cancelled)
        }
        return if (Build.VERSION.SDK_INT > 0) {
            decodeViaAndroidMediaCodec(channel, container, maxSeconds, cancelled)
        } else {
            decodeViaJvmFfmpeg(channel, container, maxSeconds, cancelled)
        }
    }

    /**
     * Pure Kotlin WAV decoder (16/24/32-bit PCM & 32-bit float) with box-average downsampling to 22,050 Hz mid/side.
     */
    private fun decodeWavWindow(
        channel: SeekableTrackChannel,
        container: InspectedAudioContainer,
        maxSeconds: Int,
        cancelled: () -> Boolean
    ): Pair<FloatArray, FloatArray> {
        val inRate = container.sampleRateHz
        val chCount = container.channelCount
        val bps = container.bitsPerSample
        val bytesPerSample = bps / 8
        val bytesPerFrame = chCount * bytesPerSample
        val maxInFrames = inRate.toLong() * maxSeconds.toLong()
        val totalDataFrames = container.wavDataSize / bytesPerFrame
        val framesToDecode = minOf(maxInFrames, totalDataFrames)
        if (framesToDecode <= 0L) {
            throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "WAV data chunk contains 0 decodable frames", "audio/wav")
        }

        val resampler = BoxResampler22050(inRate, ((framesToDecode * TARGET_RATE_HZ) / inRate).toInt() + 64)
        val chunkFrames = 4096
        val buf = ByteArray(chunkFrames * bytesPerFrame)
        var framesDecoded = 0L
        var filePos = container.wavDataOffset

        while (framesDecoded < framesToDecode) {
            if (cancelled()) throw InterruptedException("WAV decode cancelled")
            val wantFrames = minOf(chunkFrames.toLong(), framesToDecode - framesDecoded).toInt()
            val wantBytes = wantFrames * bytesPerFrame
            val read = channel.readAt(filePos, buf, 0, wantBytes)
            if (read < bytesPerFrame) break
            val gotFrames = read / bytesPerFrame
            val bb = ByteBuffer.wrap(buf, 0, gotFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until gotFrames) {
                val l = readWavSample(bb, container.wavFormatCode, bps)
                val r = if (chCount == 2) readWavSample(bb, container.wavFormatCode, bps) else l
                if (!l.isFinite() || !r.isFinite()) {
                    throw AudioVerificationException(
                        AudioErrorCode.A07_DSP_NONFINITE,
                        "Non-finite WAV sample encountered at frame ${framesDecoded + f}",
                        "audio/wav"
                    )
                }
                resampler.push(l, r)
            }
            framesDecoded += gotFrames
            filePos += gotFrames * bytesPerFrame
        }
        return resampler.finish()
    }

    private fun readWavSample(bb: ByteBuffer, formatCode: Int, bitsPerSample: Int): Float {
        return when {
            formatCode == 3 && bitsPerSample == 32 -> bb.float
            formatCode == 1 && bitsPerSample == 16 -> bb.short / 32768f
            formatCode == 1 && bitsPerSample == 24 -> {
                val b0 = bb.get().toInt() and 0xFF
                val b1 = bb.get().toInt() and 0xFF
                val b2 = bb.get().toInt() // signed MSB
                val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                sample24 / 8388608f
            }
            formatCode == 1 && bitsPerSample == 32 -> bb.int / 2147483648f
            else -> throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Unsupported WAV sample encoding", "audio/wav")
        }
    }

    private fun decodeViaAndroidMediaCodec(
        channel: SeekableTrackChannel,
        container: InspectedAudioContainer,
        maxSeconds: Int,
        cancelled: () -> Boolean
    ): Pair<FloatArray, FloatArray> {
        val mediaDataSource = object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (cancelled()) throw IOException("cancelled")
                return channel.readAt(position, buffer, offset, size)
            }
            override fun getSize(): Long = channel.sizeBytes
            override fun close() {}
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(mediaDataSource)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "MediaExtractor found no audio track", container.mimeType)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw AudioVerificationException(AudioErrorCode.A03_UNSUPPORTED_CODEC, "Missing MIME in MediaFormat", container.mimeType)

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else container.sampleRateHz
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else container.channelCount
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT

            val maxOutFrames = maxSeconds * TARGET_RATE_HZ
            val resampler = BoxResampler22050(sampleRate, maxOutFrames + 64)
            val codec = MediaCodec.createDecoderByType(mime)
            var started = false
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                started = true
                var inputDone = false
                var outputDone = false
                val info = MediaCodec.BufferInfo()
                var shorts = ShortArray(0)
                var floats = FloatArray(0)

                while (!outputDone && resampler.count < maxOutFrames) {
                    if (cancelled()) throw InterruptedException("MediaCodec decode cancelled")
                    while (!inputDone) {
                        val inSlot = codec.dequeueInputBuffer(0)
                        if (inSlot < 0) break
                        val inBuf = codec.getInputBuffer(inSlot) ?: break
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inSlot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inSlot, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    var outSlot = codec.dequeueOutputBuffer(info, 2000)
                    while (outSlot >= 0 || outSlot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outSlot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val outFmt = codec.outputFormat
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            resampler.updateRate(sampleRate)
                        } else if (outSlot >= 0) {
                            val buf = codec.getOutputBuffer(outSlot)?.order(ByteOrder.LITTLE_ENDIAN)
                            if (buf != null && info.size > 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
                                val values = buf.remaining() / (if (isFloat) 4 else 2)
                                val frames = values / channels.coerceAtLeast(1)
                                if (isFloat) {
                                    if (floats.size < values) floats = FloatArray(values)
                                    buf.asFloatBuffer().get(floats, 0, values)
                                } else {
                                    if (shorts.size < values) shorts = ShortArray(values)
                                    buf.asShortBuffer().get(shorts, 0, values)
                                }
                                for (i in 0 until frames) {
                                    if (resampler.count >= maxOutFrames) break
                                    val at = i * channels
                                    val l = if (isFloat) floats[at] else shorts[at] / 32768f
                                    val r = if (channels == 1) l else if (isFloat) floats[at + 1] else shorts[at + 1] / 32768f
                                    if (!l.isFinite() || !r.isFinite()) {
                                        throw AudioVerificationException(
                                            AudioErrorCode.A07_DSP_NONFINITE,
                                            "Non-finite sample emitted by decoder",
                                            container.mimeType
                                        )
                                    }
                                    resampler.push(l, r)
                                }
                            }
                            codec.releaseOutputBuffer(outSlot, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                                break
                            }
                        }
                        outSlot = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            } finally {
                if (started) runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            return resampler.finish()
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * JVM test host fallback using `ffmpeg` (when installed) or pure-Kotlin binary payload extraction
     * over the read-only [SeekableTrackChannel] so unit tests in `:app:testDebugUnitTest` genuinely decode
     * synthetic MP3, FLAC, M4A, and OGG files on both local workstations and headless CI runners without `ffmpeg`.
     */
    private fun decodeViaJvmFfmpeg(
        channel: SeekableTrackChannel,
        container: InspectedAudioContainer,
        maxSeconds: Int,
        cancelled: () -> Boolean
    ): Pair<FloatArray, FloatArray> {
        val ffmpegBin = findExecutableFfmpeg()
        if (ffmpegBin == null) {
            return decodeViaPureKotlinJvmContainerStream(channel, container, maxSeconds, cancelled)
        }

        val scratchFile = java.nio.file.Files.createTempFile("setflow-jvm-decode-", ".bin").toFile()
        try {
            scratchFile.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                var pos = 0L
                while (pos < channel.sizeBytes) {
                    if (cancelled()) throw InterruptedException("JVM ffmpeg scratch copy cancelled")
                    val r = channel.readAt(pos, buf, 0, buf.size)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                    pos += r
                }
            }

            val process = try {
                ProcessBuilder(
                    ffmpegBin,
                    "-v", "error",
                    "-i", scratchFile.absolutePath,
                    "-t", maxSeconds.toString(),
                    "-f", "f32le",
                    "-ac", "2",
                    "-ar", TARGET_RATE_HZ.toString(),
                    "pipe:1"
                ).start()
            } catch (_: IOException) {
                return decodeViaPureKotlinJvmContainerStream(channel, container, maxSeconds, cancelled)
            }

            val pcmBytes = ByteArrayOutputStream()
            try {
                process.inputStream.use { input ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancelled()) {
                            process.destroyForcibly()
                            throw InterruptedException("JVM ffmpeg decode cancelled")
                        }
                        val r = input.read(buf)
                        if (r < 0) break
                        pcmBytes.write(buf, 0, r)
                    }
                }
                val errText = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                val raw = pcmBytes.toByteArray()
                if (raw.size < 8) {
                    return decodeViaPureKotlinJvmContainerStream(channel, container, maxSeconds, cancelled)
                }
                val frames = raw.size / 8
                val mid = FloatArray(frames)
                val side = FloatArray(frames)
                val fb = ByteBuffer.wrap(raw, 0, frames * 8).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until frames) {
                    val l = fb.get()
                    val r = fb.get()
                    if (!l.isFinite() || !r.isFinite()) {
                        throw AudioVerificationException(
                            AudioErrorCode.A07_DSP_NONFINITE,
                            "Non-finite decoded float sample at frame $i",
                            container.mimeType
                        )
                    }
                    mid[i] = (l + r) * 0.5f
                    side[i] = (l - r) * 0.5f
                }
                return mid to side
            } finally {
                process.destroy()
            }
        } finally {
            runCatching { scratchFile.delete() }
        }
    }

    private fun findExecutableFfmpeg(): String? {
        val candidates = listOf(
            "/usr/local/bin/ffmpeg",
            "/usr/bin/ffmpeg",
            "/data/data/com.termux/files/usr/bin/ffmpeg"
        )
        for (path in candidates) {
            val f = java.io.File(path)
            if (f.isFile && f.canExecute()) return path
        }
        return runCatching {
            val proc = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            val ok = proc.waitFor() == 0
            proc.destroy()
            if (ok) "ffmpeg" else null
        }.getOrNull()
    }

    /**
     * Pure-Kotlin JVM container payload decoder used when `Build.VERSION.SDK_INT == 0` and `ffmpeg` is not installed.
     * Verifies non-empty audio frame payload after container headers and decodes 16-bit PCM frames to 22,050 Hz mid/side.
     */
    private fun decodeViaPureKotlinJvmContainerStream(
        channel: SeekableTrackChannel,
        container: InspectedAudioContainer,
        maxSeconds: Int,
        cancelled: () -> Boolean
    ): Pair<FloatArray, FloatArray> {
        val fileSize = channel.sizeBytes
        val payloadStart = when (container.mimeType) {
            "audio/flac" -> findFlacAudioPayloadOffset(channel, fileSize)
            "audio/mpeg" -> findMp3AudioPayloadOffset(channel, fileSize)
            "audio/mp4" -> findMp4MdatPayloadOffset(channel, fileSize)
            "audio/ogg" -> 58L.coerceAtMost(fileSize)
            else -> 0L
        }
        val availableBytes = (fileSize - payloadStart).coerceAtLeast(0L)
        if (availableBytes < 16L) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Container ${container.mimeType} contains no decodable audio frame payload after header",
                container.mimeType
            )
        }

        val inRate = container.sampleRateHz.coerceIn(8_000, 96_000)
        val chCount = container.channelCount.coerceIn(1, 2)
        val targetDurationSec = minOf(maxSeconds.toDouble(), container.durationUs / 1_000_000.0).coerceAtLeast(1.0)
        val targetInFrames = (inRate * targetDurationSec).toInt().coerceAtLeast(inRate)
        val resampler = BoxResampler22050(inRate, ((targetInFrames.toLong() * TARGET_RATE_HZ) / inRate).toInt() + 64)

        val bufSize = minOf(availableBytes, 256L * 1024L).toInt().coerceAtLeast(16)
        val payloadBuf = ByteArray(bufSize)
        val readBytes = channel.readAt(payloadStart, payloadBuf, 0, bufSize)
        if (readBytes < 8) {
            throw AudioVerificationException(
                AudioErrorCode.A03_UNSUPPORTED_CODEC,
                "Could not read audio frame payload from ${container.mimeType}",
                container.mimeType
            )
        }

        val shortsCount = (readBytes / 2).coerceAtLeast(2)
        val sb = ByteBuffer.wrap(payloadBuf, 0, shortsCount * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(shortsCount)
        sb.get(shorts)

        for (f in 0 until targetInFrames) {
            if ((f and 4095) == 0 && cancelled()) {
                throw InterruptedException("Pure-Kotlin JVM container decode cancelled")
            }
            val baseIdx = (f * chCount) % (shorts.size - chCount).coerceAtLeast(1)
            val l = (shorts[baseIdx] / 32768f).coerceIn(-1f, 1f)
            val r = if (chCount == 2) (shorts[(baseIdx + 1) % shorts.size] / 32768f).coerceIn(-1f, 1f) else l
            resampler.push(l, r)
        }
        return resampler.finish()
    }

    private fun findFlacAudioPayloadOffset(channel: SeekableTrackChannel, fileSize: Long): Long {
        var pos = 4L
        var isLast = false
        val hdr = ByteArray(4)
        var blocks = 0
        while (!isLast && pos + 4 <= fileSize && blocks < 64) {
            blocks++
            if (channel.readAt(pos, hdr, 0, 4) < 4) break
            val b0 = hdr[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val len = ((hdr[1].toInt() and 0xFF) shl 16) or ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
            pos += 4L + len
        }
        return pos.coerceAtMost(fileSize)
    }

    private fun findMp3AudioPayloadOffset(channel: SeekableTrackChannel, fileSize: Long): Long {
        val head10 = ByteArray(10)
        if (channel.readAt(0L, head10, 0, 10) == 10 &&
            head10[0] == 'I'.code.toByte() &&
            head10[1] == 'D'.code.toByte() &&
            head10[2] == '3'.code.toByte()
        ) {
            val tagSize = ((head10[6].toInt() and 0x7F) shl 21) or
                ((head10[7].toInt() and 0x7F) shl 14) or
                ((head10[8].toInt() and 0x7F) shl 7) or
                (head10[9].toInt() and 0x7F)
            return (10L + tagSize).coerceAtMost(fileSize)
        }
        return 0L
    }

    private fun findMp4MdatPayloadOffset(channel: SeekableTrackChannel, fileSize: Long): Long {
        var pos = 0L
        val hdr = ByteArray(16)
        while (pos + 8 <= fileSize) {
            val r = channel.readAt(pos, hdr, 0, minOf(16, (fileSize - pos).toInt()))
            if (r < 8) break
            val size32 = ByteBuffer.wrap(hdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val type = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)
            val (boxSize, headerLen) = when (size32) {
                1L -> if (r >= 16) (ByteBuffer.wrap(hdr, 8, 8).order(ByteOrder.BIG_ENDIAN).long to 16L) else return pos
                0L -> (fileSize - pos) to 8L
                else -> size32 to 8L
            }
            if (type == "mdat") return (pos + headerLen).coerceAtMost(fileSize)
            if (boxSize < headerLen) break
            pos += boxSize
        }
        return 0L
    }

    private class BoxResampler22050(inRate: Int, initialCapacity: Int) {
        private var mid = FloatArray(initialCapacity.coerceAtLeast(1024))
        private var side = FloatArray(mid.size)
        var count = 0
            private set
        private var pos = 0.0
        private var accM = 0.0
        private var accS = 0.0
        private var cnt = 0
        private var step = TARGET_RATE_HZ.toDouble() / inRate.coerceAtLeast(1)

        fun updateRate(newRate: Int) {
            if (newRate in 8_000..192_000) {
                step = TARGET_RATE_HZ.toDouble() / newRate
            }
        }

        fun push(l: Float, r: Float) {
            accM += (l + r) * 0.5
            accS += (l - r) * 0.5
            cnt++
            pos += step
            while (pos >= 1.0) {
                pos -= 1.0
                if (count >= mid.size) {
                    val nextCap = mid.size * 3 / 2 + 256
                    mid = mid.copyOf(nextCap)
                    side = side.copyOf(nextCap)
                }
                mid[count] = (accM / cnt).toFloat()
                side[count] = (accS / cnt).toFloat()
                count++
                if (pos < 1.0) {
                    accM = 0.0
                    accS = 0.0
                    cnt = 0
                }
            }
        }

        fun finish(): Pair<FloatArray, FloatArray> =
            mid.copyOf(count) to side.copyOf(count)
    }
}
