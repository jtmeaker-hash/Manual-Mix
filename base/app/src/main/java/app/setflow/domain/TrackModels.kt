/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.domain

import kotlinx.serialization.Serializable
import java.text.Normalizer

/**
 * Availability state of an imported local audio track (Stage 03).
 * Tracks are never silently deleted when missing or when URI grants are revoked.
 */
@Serializable
enum class TrackAvailabilityState {
    AVAILABLE,
    MISSING_OR_REVOKED,
    CLOUD_NOT_OFFLINE,
    SOURCE_MUTATED,
    UNSUPPORTED_OR_CORRUPT
}

/**
 * Status of the Android SAF persistable read permission grant for a track URI.
 */
@Serializable
enum class PersistableGrantStatus {
    PERSISTED,
    SESSION_ONLY,
    REVOKED_OR_DENIED
}

/**
 * Intentional user entry point used to import the track.
 */
@Serializable
enum class TrackImportSource {
    SAF_DOCUMENT_PICKER,
    MEDIA_STORE_BROWSE
}

/**
 * Two-level track identity (REFERENCE/04_DATA_MODEL_AND_PERSISTENCE.txt):
 * 1) Permission/path identity: persisted content URI, provider authority, size, modified timestamp.
 * 2) Recording identity: fast sampled contentFingerprint (sf-fp-v1) + verified decoded audioKey (SHA-256 of 22.05 kHz mid/side).
 */
@Serializable
data class TrackIdentity(
    val contentUri: String,
    val providerAuthority: String? = null,
    val mediaStoreId: Long? = null,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val grantStatus: PersistableGrantStatus,
    val contentFingerprint: String,
    val fingerprintVersion: Int = FINGERPRINT_VERSION_V1,
    val verified10sAudioKey: String? = null,
    val audioKey: String? = null
) {
    companion object {
        const val FINGERPRINT_VERSION_V1 = 1
    }
}

/**
 * Persisted local audio track record in Stage 03.
 * Durations are stored in integer microseconds (`durationUs`), never floating-point seconds.
 */
@Serializable
data class LocalTrackRecord(
    val trackId: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationUs: Long,
    val mimeType: String,
    val codecDescription: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val identity: TrackIdentity,
    val importSource: TrackImportSource = TrackImportSource.SAF_DOCUMENT_PICKER,
    val cachedArtworkKey: String? = null,
    val artworkByteSize: Int = 0,
    val importedAtUtcMs: Long,
    val lastVerifiedUtcMs: Long,
    val availabilityState: TrackAvailabilityState = TrackAvailabilityState.AVAILABLE,
    val lastErrorCode: AudioErrorCode? = null,
    val statusDetail: String = ""
)

/**
 * Classification of an individual file import attempt.
 */
@Serializable
enum class ImportOutcomeType {
    IMPORTED_NEW,
    DUPLICATE_SAME_URI,
    DUPLICATE_SAME_CONTENT,
    RELINKED_EXISTING,
    REJECTED_CLOUD_NOT_OFFLINE,
    REJECTED_PERMISSION_DENIED,
    REJECTED_MISSING_OR_EMPTY,
    REJECTED_UNSUPPORTED_OR_CORRUPT,
    CANCELLED
}

/**
 * Per-URI outcome reported during or after an import batch.
 */
@Serializable
data class ImportItemOutcome(
    val uriString: String,
    val displayName: String,
    val outcomeType: ImportOutcomeType,
    val trackId: String? = null,
    val sameNameDifferentRecording: Boolean = false,
    val errorCode: AudioErrorCode? = null,
    val message: String
)

/**
 * Observable state of an active or completed track import batch.
 */
data class ImportProgressState(
    val isImporting: Boolean = false,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val currentDisplayName: String = "",
    val cancelled: Boolean = false,
    val outcomes: List<ImportItemOutcome> = emptyList()
)

/**
 * Result of evaluating a candidate replacement file for relinking a missing/revoked/mutated track.
 * Never automatically substitutes a different recording just because filenames match!
 */
sealed interface RelinkCandidateEvaluation {
    val trackId: String
    val candidateUri: String
    val candidateDisplayName: String

    data class ExactMatch(
        override val trackId: String,
        override val candidateUri: String,
        override val candidateDisplayName: String,
        val matchedByContentFingerprint: Boolean,
        val matchedByAudioKey: Boolean,
        val candidateIdentity: TrackIdentity,
        val durationUs: Long,
        val mimeType: String,
        val codecDescription: String,
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int
    ) : RelinkCandidateEvaluation

    data class UncertainMatchRequiresConfirmation(
        override val trackId: String,
        override val candidateUri: String,
        override val candidateDisplayName: String,
        val reason: String,
        val originalDurationUs: Long,
        val candidateDurationUs: Long,
        val durationDeltaUs: Long,
        val sameDisplayName: Boolean,
        val candidateIdentity: TrackIdentity,
        val mimeType: String,
        val codecDescription: String,
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int
    ) : RelinkCandidateEvaluation

    data class RejectedCandidate(
        override val trackId: String,
        override val candidateUri: String,
        override val candidateDisplayName: String,
        val errorCode: AudioErrorCode,
        val reason: String
    ) : RelinkCandidateEvaluation
}

/**
 * Safe metadata normalization and bounded artwork thumbnail cache (Stage 03 Task 3 & Task 4).
 */
object MetadataNormalizer {
    const val MAX_METADATA_CHARS = 256
    const val MAX_MIME_CHARS = 64
    const val MAX_EMBEDDED_ARTWORK_BYTES = 8 * 1024 * 1024 // 8 MiB hard cap on embedded picture payload
    const val THUMBNAIL_MAX_DIMENSION_PX = 128

    /**
     * Normalizes Unicode text to NFC, strips control characters, collapses runs of control whitespace,
     * and enforces [maxLength] characters.
     */
    fun sanitizeText(raw: String?, fallback: String = "", maxLength: Int = MAX_METADATA_CHARS): String {
        if (raw.isNullOrBlank()) return fallback
        val nfc = runCatching { Normalizer.normalize(raw, Normalizer.Form.NFC) }.getOrDefault(raw)
        val cleaned = buildString(nfc.length) {
            for (ch in nfc) {
                when {
                    ch == '\n' || ch == '\r' || ch == '\t' -> append(' ')
                    ch.code in 0x00..0x1F || ch.code == 0x7F -> Unit // strip control chars
                    else -> append(ch)
                }
            }
        }.trim().replace(Regex("\\s{2,}"), " ")
        if (cleaned.isEmpty()) return fallback
        return if (cleaned.length <= maxLength) cleaned else cleaned.substring(0, maxLength).trimEnd()
    }

    /**
     * Extracts a clean fallback track title from a filename while preserving Unicode characters.
     */
    fun titleFromDisplayName(displayName: String): String {
        val sanitizedName = sanitizeText(displayName, fallback = "Untitled Track")
        val dotIndex = sanitizedName.lastIndexOf('.')
        val stem = if (dotIndex > 0) sanitizedName.substring(0, dotIndex).trim() else sanitizedName
        return stem.ifEmpty { sanitizedName }
    }

    fun sanitizeMime(raw: String?, fallback: String = "application/octet-stream"): String =
        sanitizeText(raw?.lowercase(), fallback = fallback, maxLength = MAX_MIME_CHARS)

    /**
     * Computes power-of-two sample factor so decoded artwork thumbnails never exceed [maxDimensionPx].
     */
    fun computeInSampleSize(width: Int, height: Int, maxDimensionPx: Int = THUMBNAIL_MAX_DIMENSION_PX): Int {
        if (width <= 0 || height <= 0) return 1
        var inSampleSize = 1
        while (width / inSampleSize > maxDimensionPx || height / inSampleSize > maxDimensionPx) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }
}

/**
 * Bounded in-memory LRU cache for compact artwork thumbnail descriptors/bytes (never full-size raw Bitmaps).
 * Prevents OOM when importing or scrolling 100+ tracks with embedded artwork (Task 4 & S11).
 */
class ArtworkThumbnailCache(
    val maxCacheBytes: Int = 4 * 1024 * 1024, // 4 MiB total memory budget
    val maxSingleEntryBytes: Int = 64 * 1024   // 64 KiB max per compact thumbnail entry
) {
    data class ThumbnailEntry(
        val key: String,
        val mimeType: String,
        val originalWidth: Int,
        val originalHeight: Int,
        val sampleSize: Int,
        val thumbnailBytes: ByteArray
    ) {
        val byteSize: Int get() = thumbnailBytes.size
    }

    private val map = object : LinkedHashMap<String, ThumbnailEntry>(32, 0.75f, true) {}
    private var currentBytes: Int = 0

    @Synchronized
    fun put(
        key: String,
        mimeType: String,
        originalWidth: Int,
        originalHeight: Int,
        rawArtworkBytes: ByteArray
    ): ThumbnailEntry? {
        if (rawArtworkBytes.isEmpty() || rawArtworkBytes.size > MetadataNormalizer.MAX_EMBEDDED_ARTWORK_BYTES) {
            return null
        }
        val sampleSize = MetadataNormalizer.computeInSampleSize(originalWidth, originalHeight)
        // Store a bounded compact representation (capped at maxSingleEntryBytes) so 250+ tracks never exceed budget
        val cappedBytes = if (rawArtworkBytes.size <= maxSingleEntryBytes) {
            rawArtworkBytes.copyOf()
        } else {
            // Down-sample deterministically into maxSingleEntryBytes summary when Android BitmapFactory isn't active
            val out = ByteArray(maxSingleEntryBytes)
            val step = rawArtworkBytes.size.toDouble() / maxSingleEntryBytes
            for (i in 0 until maxSingleEntryBytes) {
                out[i] = rawArtworkBytes[(i * step).toInt().coerceIn(0, rawArtworkBytes.size - 1)]
            }
            out
        }
        val entry = ThumbnailEntry(
            key = key,
            mimeType = MetadataNormalizer.sanitizeMime(mimeType, "image/jpeg"),
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            sampleSize = sampleSize,
            thumbnailBytes = cappedBytes
        )
        map.remove(key)?.let { currentBytes -= it.byteSize }
        while (currentBytes + entry.byteSize > maxCacheBytes && map.isNotEmpty()) {
            val eldestKey = map.entries.iterator().next().key
            val removed = map.remove(eldestKey)
            if (removed != null) currentBytes -= removed.byteSize
        }
        if (entry.byteSize <= maxCacheBytes) {
            map[key] = entry
            currentBytes += entry.byteSize
        }
        return entry
    }

    @Synchronized
    fun get(key: String): ThumbnailEntry? = map[key]

    @Synchronized
    fun usedBytes(): Int = currentBytes

    @Synchronized
    fun entryCount(): Int = map.size

    @Synchronized
    fun clear() {
        map.clear()
        currentBytes = 0
    }
}
