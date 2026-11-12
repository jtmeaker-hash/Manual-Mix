/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.service

import android.content.Intent
import app.setflow.domain.ArtworkThumbnailCache
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.ImportItemOutcome
import app.setflow.domain.ImportOutcomeType
import app.setflow.domain.ImportProgressState
import app.setflow.domain.LocalTrackRecord
import app.setflow.domain.MetadataNormalizer
import app.setflow.domain.PersistableGrantStatus
import app.setflow.domain.RelinkCandidateEvaluation
import app.setflow.domain.SchedulerPhase
import app.setflow.domain.TrackAvailabilityState
import app.setflow.domain.TrackIdentity
import app.setflow.domain.TrackImportSource
import app.setflow.io.AudioDecodeVerifier
import app.setflow.io.AudioVerificationResult
import app.setflow.io.CloudDocumentNotOfflineException
import app.setflow.io.OpenResourceTracker
import app.setflow.io.TrackFingerprinter
import app.setflow.io.TrackSourceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Contract for Stage 03 local audio track import, identity verification, and relinking.
 */
interface TrackLibraryRepository : TrackResolver {
    val tracks: StateFlow<List<LocalTrackRecord>>
    val importProgress: StateFlow<ImportProgressState>
    val pendingRelinkEvaluation: StateFlow<RelinkCandidateEvaluation?>
    val artworkCache: ArtworkThumbnailCache
    val resourceTracker: OpenResourceTracker

    fun importUris(
        uriStrings: List<String>,
        intentFlags: Int = DEFAULT_SAF_GRANT_FLAGS,
        importSource: TrackImportSource = TrackImportSource.SAF_DOCUMENT_PICKER,
        verifyWindowSeconds: Int = AudioDecodeVerifier.DEFAULT_VERIFY_WINDOW_SECONDS,
        computeFullAudioKey: Boolean = false,
        cancelledOverride: (() -> Boolean)? = null
    ): List<ImportItemOutcome>

    fun cancelActiveImport()
    fun verifyAllTracks(): List<LocalTrackRecord>
    fun verifyTrack(trackId: String): LocalTrackRecord?
    fun evaluateRelinkCandidate(
        trackId: String,
        candidateUri: String,
        intentFlags: Int = DEFAULT_SAF_GRANT_FLAGS,
        autoApplyOnExactMatch: Boolean = true
    ): RelinkCandidateEvaluation

    fun confirmRelink(
        evaluation: RelinkCandidateEvaluation,
        userConfirmedUncertainMatch: Boolean = false
    ): LocalTrackRecord?

    fun clearPendingRelinkEvaluation()
    fun getTrack(trackId: String): LocalTrackRecord?
    fun reloadFromStorage()

    companion object {
        const val DEFAULT_SAF_GRANT_FLAGS: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    }
}

@Serializable
private data class PersistedTrackLibrarySnapshot(
    val schemaVersion: Int = 1,
    val tracks: List<LocalTrackRecord> = emptyList()
)

/**
 * Stage 03 implementation of [TrackLibraryRepository] and [TrackResolver].
 * Never mutates audio files, enforces bounded FD/artwork lifecycles, and preserves missing tracks for relinking.
 */
class LocalTrackLibraryService(
    private val sourceProvider: TrackSourceProvider,
    private val diagnostics: DiagnosticsRingBuffer,
    private val storageDirectory: File? = null,
    override val artworkCache: ArtworkThumbnailCache = ArtworkThumbnailCache(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) : TrackLibraryRepository {

    override val resourceTracker: OpenResourceTracker
        get() = sourceProvider.resourceTracker

    private val _tracks = MutableStateFlow<List<LocalTrackRecord>>(emptyList())
    override val tracks: StateFlow<List<LocalTrackRecord>> = _tracks.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgressState())
    override val importProgress: StateFlow<ImportProgressState> = _importProgress.asStateFlow()

    private val _pendingRelinkEvaluation = MutableStateFlow<RelinkCandidateEvaluation?>(null)
    override val pendingRelinkEvaluation: StateFlow<RelinkCandidateEvaluation?> = _pendingRelinkEvaluation.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        reloadFromStorage()
    }

    override fun cancelActiveImport() {
        cancelRequested.set(true)
        diagnostics.record(
            category = "TrackImport",
            phase = SchedulerPhase.IDLE,
            errorCode = AudioErrorCode.A11_CANCELLED,
            message = "User requested cancellation of active local audio import batch"
        )
    }

    override fun importUris(
        uriStrings: List<String>,
        intentFlags: Int,
        importSource: TrackImportSource,
        verifyWindowSeconds: Int,
        computeFullAudioKey: Boolean,
        cancelledOverride: (() -> Boolean)?
    ): List<ImportItemOutcome> {
        cancelRequested.set(false)
        val isCancelled: () -> Boolean = {
            cancelRequested.get() || (cancelledOverride?.invoke() == true)
        }

        val outcomes = ArrayList<ImportItemOutcome>(uriStrings.size)
        _importProgress.value = ImportProgressState(
            isImporting = true,
            totalCount = uriStrings.size,
            completedCount = 0,
            currentDisplayName = "",
            cancelled = false,
            outcomes = emptyList()
        )

        for ((index, uriString) in uriStrings.withIndex()) {
            if (isCancelled()) {
                val cancelledOutcome = ImportItemOutcome(
                    uriString = uriString,
                    displayName = fallbackDisplayName(uriString),
                    outcomeType = ImportOutcomeType.CANCELLED,
                    errorCode = AudioErrorCode.A11_CANCELLED,
                    message = "Import cancelled before reading item ${index + 1} of ${uriStrings.size}"
                )
                outcomes.add(cancelledOutcome)
                _importProgress.value = ImportProgressState(
                    isImporting = true,
                    totalCount = uriStrings.size,
                    completedCount = outcomes.size,
                    currentDisplayName = cancelledOutcome.displayName,
                    cancelled = true,
                    outcomes = outcomes.toList()
                )
                continue
            }

            val outcome = importSingleUri(
                uriString = uriString,
                intentFlags = intentFlags,
                importSource = importSource,
                verifyWindowSeconds = verifyWindowSeconds,
                computeFullAudioKey = computeFullAudioKey,
                isCancelled = isCancelled
            )
            outcomes.add(outcome)
            _importProgress.value = ImportProgressState(
                isImporting = index + 1 < uriStrings.size,
                totalCount = uriStrings.size,
                completedCount = outcomes.size,
                currentDisplayName = outcome.displayName,
                cancelled = isCancelled(),
                outcomes = outcomes.toList()
            )
        }

        val finalCancelled = isCancelled()
        _importProgress.value = ImportProgressState(
            isImporting = false,
            totalCount = uriStrings.size,
            completedCount = outcomes.size,
            currentDisplayName = "",
            cancelled = finalCancelled,
            outcomes = outcomes.toList()
        )
        persistSnapshot()
        return outcomes
    }

    private fun importSingleUri(
        uriString: String,
        intentFlags: Int,
        importSource: TrackImportSource,
        verifyWindowSeconds: Int,
        computeFullAudioKey: Boolean,
        isCancelled: () -> Boolean
    ): ImportItemOutcome {
        val grantStatus = sourceProvider.takePersistableReadPermissionIfOffered(uriString, intentFlags)
        if (grantStatus == PersistableGrantStatus.REVOKED_OR_DENIED) {
            diagnostics.record(
                category = "TrackImport",
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                message = "Persistable read permission denied for $uriString"
            )
            return ImportItemOutcome(
                uriString = uriString,
                displayName = fallbackDisplayName(uriString),
                outcomeType = ImportOutcomeType.REJECTED_PERMISSION_DENIED,
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                message = "Read permission denied by provider for selected URI"
            )
        }

        val queried = try {
            sourceProvider.queryMetadata(uriString, grantStatus, importSource)
        } catch (e: CloudDocumentNotOfflineException) {
            diagnostics.record(
                category = "TrackImport",
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Cloud document not available offline: ${e.message}"
            )
            return ImportItemOutcome(
                uriString = uriString,
                displayName = fallbackDisplayName(uriString),
                outcomeType = ImportOutcomeType.REJECTED_CLOUD_NOT_OFFLINE,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Cloud-backed document is not downloaded. Make available offline first."
            )
        } catch (e: SecurityException) {
            diagnostics.record(
                category = "TrackImport",
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                message = "SecurityException querying metadata: ${e.message}"
            )
            return ImportItemOutcome(
                uriString = uriString,
                displayName = fallbackDisplayName(uriString),
                outcomeType = ImportOutcomeType.REJECTED_PERMISSION_DENIED,
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                message = "Read permission denied while querying document metadata"
            )
        } catch (e: FileNotFoundException) {
            diagnostics.record(
                category = "TrackImport",
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "FileNotFoundException querying metadata: ${e.message}"
            )
            return ImportItemOutcome(
                uriString = uriString,
                displayName = fallbackDisplayName(uriString),
                outcomeType = ImportOutcomeType.REJECTED_MISSING_OR_EMPTY,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Selected audio document is missing or unavailable"
            )
        } catch (e: IOException) {
            return ImportItemOutcome(
                uriString = uriString,
                displayName = fallbackDisplayName(uriString),
                outcomeType = ImportOutcomeType.REJECTED_MISSING_OR_EMPTY,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = e.message ?: "I/O error querying document metadata"
            )
        }

        val normalizedDisplayName = MetadataNormalizer.sanitizeText(
            queried.displayName,
            fallback = fallbackDisplayName(uriString)
        )

        if (queried.isCloudOnlyOrVirtual) {
            diagnostics.record(
                category = "TrackImport",
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Document is flagged virtual/cloud-only: $normalizedDisplayName"
            )
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.REJECTED_CLOUD_NOT_OFFLINE,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Document is cloud-backed or virtual; make available offline first"
            )
        }

        if (queried.sizeBytes == 0L) {
            diagnostics.record(
                category = "TrackImport",
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Rejected empty 0-byte document"
            )
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.REJECTED_MISSING_OR_EMPTY,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Document is empty (0 bytes)"
            )
        }

        val contentFingerprint: String
        val verification: AudioVerificationResult
        try {
            sourceProvider.openReadOnlyChannel(uriString).use { channel ->
                if (channel.sizeBytes <= 0L) {
                    return ImportItemOutcome(
                        uriString = uriString,
                        displayName = normalizedDisplayName,
                        outcomeType = ImportOutcomeType.REJECTED_MISSING_OR_EMPTY,
                        errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                        message = "Document is empty (0 bytes)"
                    )
                }
                contentFingerprint = TrackFingerprinter.computeFastContentFingerprint(channel, isCancelled)
                verification = AudioDecodeVerifier.verifyAndDecode(
                    channel = channel,
                    maxSeconds = verifyWindowSeconds,
                    computeFullAudioKey = computeFullAudioKey,
                    cancelled = isCancelled
                )
            }
        } catch (_: InterruptedException) {
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.CANCELLED,
                errorCode = AudioErrorCode.A11_CANCELLED,
                message = "Import cancelled while reading $normalizedDisplayName"
            )
        } catch (e: CloudDocumentNotOfflineException) {
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.REJECTED_CLOUD_NOT_OFFLINE,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Cloud document not available offline; make available offline first"
            )
        } catch (e: SecurityException) {
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.REJECTED_PERMISSION_DENIED,
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                message = "Read permission denied while reading audio bytes"
            )
        } catch (e: FileNotFoundException) {
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.REJECTED_MISSING_OR_EMPTY,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Audio file missing or unmounted during read"
            )
        } catch (e: IOException) {
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.REJECTED_UNSUPPORTED_OR_CORRUPT,
                errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                message = e.message ?: "Failed to read audio stream"
            )
        }

        if (verification is AudioVerificationResult.Failed) {
            if (verification.errorCode == AudioErrorCode.A11_CANCELLED) {
                return ImportItemOutcome(
                    uriString = uriString,
                    displayName = normalizedDisplayName,
                    outcomeType = ImportOutcomeType.CANCELLED,
                    errorCode = AudioErrorCode.A11_CANCELLED,
                    message = verification.reason
                )
            }
            diagnostics.record(
                category = "TrackImport",
                errorCode = verification.errorCode,
                message = "Rejected audio source ($normalizedDisplayName): ${verification.reason}"
            )
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.REJECTED_UNSUPPORTED_OR_CORRUPT,
                errorCode = verification.errorCode,
                message = verification.reason
            )
        }

        val verified = verification as AudioVerificationResult.Verified
        val container = verified.container
        val now = clockMs()

        synchronized(this) {
            val currentList = _tracks.value.toMutableList()

            // Duplicate check 1: Exact same URI + same contentFingerprint
            val sameUriIdx = currentList.indexOfFirst { it.identity.contentUri == uriString }
            if (sameUriIdx >= 0) {
                val existing = currentList[sameUriIdx]
                if (existing.identity.contentFingerprint == contentFingerprint) {
                    val refreshed = existing.copy(
                        identity = existing.identity.copy(
                            grantStatus = grantStatus,
                            lastModifiedMs = queried.lastModifiedMs,
                            verified10sAudioKey = verified.verifiedWindowAudioKey,
                            audioKey = verified.fullAudioKey ?: existing.identity.audioKey
                        ),
                        lastVerifiedUtcMs = now,
                        availabilityState = TrackAvailabilityState.AVAILABLE,
                        lastErrorCode = null,
                        statusDetail = "Verified existing track URI"
                    )
                    currentList[sameUriIdx] = refreshed
                    _tracks.value = currentList
                    return ImportItemOutcome(
                        uriString = uriString,
                        displayName = normalizedDisplayName,
                        outcomeType = ImportOutcomeType.DUPLICATE_SAME_URI,
                        trackId = refreshed.trackId,
                        message = "Duplicate URI skipped; refreshed verification timestamp"
                    )
                }
            }

            // Duplicate check 2: Identical contentFingerprint (or identical verified10sAudioKey + size + duration)
            val sameContentIdx = currentList.indexOfFirst {
                it.identity.contentFingerprint == contentFingerprint ||
                    (it.identity.sizeBytes == queried.sizeBytes &&
                        abs(it.durationUs - container.durationUs) <= 50_000L &&
                        it.identity.verified10sAudioKey != null &&
                        it.identity.verified10sAudioKey == verified.verifiedWindowAudioKey)
            }
            if (sameContentIdx >= 0) {
                val existing = currentList[sameContentIdx]
                if (existing.availabilityState != TrackAvailabilityState.AVAILABLE) {
                    val relinked = existing.copy(
                        displayName = normalizedDisplayName,
                        identity = existing.identity.copy(
                            contentUri = uriString,
                            providerAuthority = queried.providerAuthority,
                            mediaStoreId = queried.mediaStoreId,
                            sizeBytes = queried.sizeBytes,
                            lastModifiedMs = queried.lastModifiedMs,
                            grantStatus = grantStatus,
                            contentFingerprint = contentFingerprint,
                            verified10sAudioKey = verified.verifiedWindowAudioKey,
                            audioKey = verified.fullAudioKey ?: existing.identity.audioKey
                        ),
                        lastVerifiedUtcMs = now,
                        availabilityState = TrackAvailabilityState.AVAILABLE,
                        lastErrorCode = null,
                        statusDetail = "Relinked missing track to matching recording"
                    )
                    currentList[sameContentIdx] = relinked
                    _tracks.value = currentList
                    return ImportItemOutcome(
                        uriString = uriString,
                        displayName = normalizedDisplayName,
                        outcomeType = ImportOutcomeType.RELINKED_EXISTING,
                        trackId = relinked.trackId,
                        message = "Relinked existing missing track (${relinked.trackId}) with identical recording fingerprint"
                    )
                }
                return ImportItemOutcome(
                    uriString = uriString,
                    displayName = normalizedDisplayName,
                    outcomeType = ImportOutcomeType.DUPLICATE_SAME_CONTENT,
                    trackId = existing.trackId,
                    message = "Duplicate recording skipped (matches existing track '${existing.title}')"
                )
            }

            // Check if another track has the same displayName but DIFFERENT recording bytes (Task 3 / Gate 3)
            val sameNameDifferentBytes = currentList.any {
                it.displayName.equals(normalizedDisplayName, ignoreCase = true) &&
                    it.identity.contentFingerprint != contentFingerprint
            }

            val trackId = UUID.randomUUID().toString()
            var artworkKey: String? = null
            var artworkBytesLen = 0
            val rawArt = container.artworkBytes
            if (rawArt != null && rawArt.isNotEmpty() && !container.artworkOversizedSkipped) {
                val candidateKey = "art-$trackId"
                val entry = artworkCache.put(
                    key = candidateKey,
                    mimeType = container.artworkMimeType ?: "image/jpeg",
                    originalWidth = container.artworkWidth,
                    originalHeight = container.artworkHeight,
                    rawArtworkBytes = rawArt
                )
                if (entry != null) {
                    artworkKey = entry.key
                    artworkBytesLen = entry.byteSize
                }
            }

            val sanitizedTitle = MetadataNormalizer.sanitizeText(
                container.title,
                fallback = MetadataNormalizer.titleFromDisplayName(normalizedDisplayName)
            )
            val sanitizedArtist = MetadataNormalizer.sanitizeText(
                container.artist,
                fallback = "Unknown Artist"
            )
            val sanitizedAlbum = MetadataNormalizer.sanitizeText(
                container.album,
                fallback = ""
            )

            val newRecord = LocalTrackRecord(
                trackId = trackId,
                displayName = normalizedDisplayName,
                title = sanitizedTitle,
                artist = sanitizedArtist,
                album = sanitizedAlbum,
                durationUs = container.durationUs,
                mimeType = MetadataNormalizer.sanitizeMime(container.mimeType),
                codecDescription = MetadataNormalizer.sanitizeText(container.codecDescription, fallback = container.mimeType),
                sampleRateHz = container.sampleRateHz,
                channelCount = container.channelCount,
                bitsPerSample = container.bitsPerSample,
                identity = TrackIdentity(
                    contentUri = uriString,
                    providerAuthority = queried.providerAuthority,
                    mediaStoreId = queried.mediaStoreId,
                    sizeBytes = queried.sizeBytes,
                    lastModifiedMs = queried.lastModifiedMs,
                    grantStatus = grantStatus,
                    contentFingerprint = contentFingerprint,
                    verified10sAudioKey = verified.verifiedWindowAudioKey,
                    audioKey = verified.fullAudioKey
                ),
                importSource = importSource,
                cachedArtworkKey = artworkKey,
                artworkByteSize = artworkBytesLen,
                importedAtUtcMs = now,
                lastVerifiedUtcMs = now,
                availabilityState = TrackAvailabilityState.AVAILABLE,
                lastErrorCode = null,
                statusDetail = buildString {
                    append("Verified ${container.codecDescription}, ${container.sampleRateHz} Hz")
                    if (sameNameDifferentBytes) {
                        append(" • Distinct recording with shared filename")
                    }
                    if (container.artworkOversizedSkipped) {
                        append(" • Skipped oversized embedded artwork (>8 MiB)")
                    }
                }
            )

            currentList.add(newRecord)
            _tracks.value = currentList
            diagnostics.record(
                category = "TrackImport",
                phase = SchedulerPhase.IDLE,
                message = "Imported trackId=$trackId codec=${container.codecDescription} sr=${container.sampleRateHz} durationUs=${container.durationUs}"
            )
            return ImportItemOutcome(
                uriString = uriString,
                displayName = normalizedDisplayName,
                outcomeType = ImportOutcomeType.IMPORTED_NEW,
                trackId = trackId,
                sameNameDifferentRecording = sameNameDifferentBytes,
                message = newRecord.statusDetail
            )
        }
    }

    override fun verifyAllTracks(): List<LocalTrackRecord> {
        val currentIds = _tracks.value.map { it.trackId }
        for (id in currentIds) {
            verifyTrack(id)
        }
        persistSnapshot()
        return _tracks.value
    }

    override fun verifyTrack(trackId: String): LocalTrackRecord? {
        val existing = _tracks.value.firstOrNull { it.trackId == trackId } ?: return null
        val uriString = existing.identity.contentUri
        val now = clockMs()

        val updated = try {
            val queried = sourceProvider.queryMetadata(
                uriString = uriString,
                grantStatus = existing.identity.grantStatus,
                importSource = existing.importSource
            )
            if (queried.isCloudOnlyOrVirtual) {
                existing.copy(
                    lastVerifiedUtcMs = now,
                    availabilityState = TrackAvailabilityState.CLOUD_NOT_OFFLINE,
                    lastErrorCode = AudioErrorCode.A01_SOURCE_MISSING,
                    statusDetail = "Document is cloud-backed; make available offline first"
                )
            } else {
                sourceProvider.openReadOnlyChannel(uriString).use { channel ->
                    if (channel.sizeBytes <= 0L) {
                        existing.copy(
                            lastVerifiedUtcMs = now,
                            availabilityState = TrackAvailabilityState.MISSING_OR_REVOKED,
                            lastErrorCode = AudioErrorCode.A01_SOURCE_MISSING,
                            statusDetail = "Source file is empty (0 bytes) — relink required"
                        )
                    } else {
                        val currentFp = TrackFingerprinter.computeFastContentFingerprint(channel)
                        if (currentFp != existing.identity.contentFingerprint || channel.sizeBytes != existing.identity.sizeBytes) {
                            existing.copy(
                                lastVerifiedUtcMs = now,
                                availabilityState = TrackAvailabilityState.SOURCE_MUTATED,
                                lastErrorCode = AudioErrorCode.A05_SOURCE_CHANGED,
                                statusDetail = "Source file bytes or size changed since import — relink or review required"
                            )
                        } else {
                            existing.copy(
                                lastVerifiedUtcMs = now,
                                availabilityState = TrackAvailabilityState.AVAILABLE,
                                lastErrorCode = null,
                                statusDetail = "Source verified and readable"
                            )
                        }
                    }
                }
            }
        } catch (_: CloudDocumentNotOfflineException) {
            existing.copy(
                lastVerifiedUtcMs = now,
                availabilityState = TrackAvailabilityState.CLOUD_NOT_OFFLINE,
                lastErrorCode = AudioErrorCode.A01_SOURCE_MISSING,
                statusDetail = "Cloud-backed document not available offline; make available offline first"
            )
        } catch (_: SecurityException) {
            diagnostics.record(
                category = "TrackVerify",
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                message = "URI read permission revoked for trackId=$trackId"
            )
            existing.copy(
                identity = existing.identity.copy(grantStatus = PersistableGrantStatus.REVOKED_OR_DENIED),
                lastVerifiedUtcMs = now,
                availabilityState = TrackAvailabilityState.MISSING_OR_REVOKED,
                lastErrorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                statusDetail = "Read permission revoked — choose replacement file to relink"
            )
        } catch (_: FileNotFoundException) {
            diagnostics.record(
                category = "TrackVerify",
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Source audio file missing or SD card removed for trackId=$trackId"
            )
            existing.copy(
                lastVerifiedUtcMs = now,
                availabilityState = TrackAvailabilityState.MISSING_OR_REVOKED,
                lastErrorCode = AudioErrorCode.A01_SOURCE_MISSING,
                statusDetail = "Track missing or external storage removed — choose replacement file to relink"
            )
        } catch (e: IOException) {
            existing.copy(
                lastVerifiedUtcMs = now,
                availabilityState = TrackAvailabilityState.MISSING_OR_REVOKED,
                lastErrorCode = AudioErrorCode.A01_SOURCE_MISSING,
                statusDetail = "Source unreadable (${e.message ?: "I/O error"}) — relink required"
            )
        }

        synchronized(this) {
            val list = _tracks.value.toMutableList()
            val idx = list.indexOfFirst { it.trackId == trackId }
            if (idx >= 0) {
                list[idx] = updated
                _tracks.value = list
            }
        }
        return updated
    }

    override fun evaluateRelinkCandidate(
        trackId: String,
        candidateUri: String,
        intentFlags: Int,
        autoApplyOnExactMatch: Boolean
    ): RelinkCandidateEvaluation {
        val original = getTrack(trackId)
            ?: return RelinkCandidateEvaluation.RejectedCandidate(
                trackId = trackId,
                candidateUri = candidateUri,
                candidateDisplayName = fallbackDisplayName(candidateUri),
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                reason = "Unknown trackId=$trackId"
            )

        val grantStatus = sourceProvider.takePersistableReadPermissionIfOffered(candidateUri, intentFlags)
        if (grantStatus == PersistableGrantStatus.REVOKED_OR_DENIED) {
            val rejected = RelinkCandidateEvaluation.RejectedCandidate(
                trackId = trackId,
                candidateUri = candidateUri,
                candidateDisplayName = fallbackDisplayName(candidateUri),
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                reason = "Read permission denied for replacement URI"
            )
            _pendingRelinkEvaluation.value = rejected
            return rejected
        }

        val queried = try {
            sourceProvider.queryMetadata(candidateUri, grantStatus, TrackImportSource.SAF_DOCUMENT_PICKER)
        } catch (e: Exception) {
            val code = if (e is SecurityException) AudioErrorCode.A02_UNREADABLE_PERMISSION else AudioErrorCode.A01_SOURCE_MISSING
            val rejected = RelinkCandidateEvaluation.RejectedCandidate(
                trackId = trackId,
                candidateUri = candidateUri,
                candidateDisplayName = fallbackDisplayName(candidateUri),
                errorCode = code,
                reason = e.message ?: "Replacement file is missing or unreadable"
            )
            _pendingRelinkEvaluation.value = rejected
            return rejected
        }

        val candidateName = MetadataNormalizer.sanitizeText(queried.displayName, fallbackDisplayName(candidateUri))
        if (queried.isCloudOnlyOrVirtual) {
            val rejected = RelinkCandidateEvaluation.RejectedCandidate(
                trackId = trackId,
                candidateUri = candidateUri,
                candidateDisplayName = candidateName,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                reason = "Replacement document is cloud-backed; make available offline first"
            )
            _pendingRelinkEvaluation.value = rejected
            return rejected
        }

        val candidateFp: String
        val verification: AudioVerificationResult
        try {
            sourceProvider.openReadOnlyChannel(candidateUri).use { channel ->
                if (channel.sizeBytes <= 0L) {
                    val rejected = RelinkCandidateEvaluation.RejectedCandidate(
                        trackId = trackId,
                        candidateUri = candidateUri,
                        candidateDisplayName = candidateName,
                        errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                        reason = "Replacement file is empty (0 bytes)"
                    )
                    _pendingRelinkEvaluation.value = rejected
                    return rejected
                }
                candidateFp = TrackFingerprinter.computeFastContentFingerprint(channel)
                verification = AudioDecodeVerifier.verifyAndDecode(channel)
            }
        } catch (e: Exception) {
            val rejected = RelinkCandidateEvaluation.RejectedCandidate(
                trackId = trackId,
                candidateUri = candidateUri,
                candidateDisplayName = candidateName,
                errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                reason = e.message ?: "Failed to decode replacement file"
            )
            _pendingRelinkEvaluation.value = rejected
            return rejected
        }

        if (verification is AudioVerificationResult.Failed) {
            val rejected = RelinkCandidateEvaluation.RejectedCandidate(
                trackId = trackId,
                candidateUri = candidateUri,
                candidateDisplayName = candidateName,
                errorCode = verification.errorCode,
                reason = verification.reason
            )
            _pendingRelinkEvaluation.value = rejected
            return rejected
        }

        val verified = verification as AudioVerificationResult.Verified
        val c = verified.container
        val candidateIdentity = TrackIdentity(
            contentUri = candidateUri,
            providerAuthority = queried.providerAuthority,
            mediaStoreId = queried.mediaStoreId,
            sizeBytes = queried.sizeBytes,
            lastModifiedMs = queried.lastModifiedMs,
            grantStatus = grantStatus,
            contentFingerprint = candidateFp,
            verified10sAudioKey = verified.verifiedWindowAudioKey,
            audioKey = verified.fullAudioKey ?: if (candidateFp == original.identity.contentFingerprint) original.identity.audioKey else null
        )

        val fpMatch = candidateFp == original.identity.contentFingerprint
        val durationDeltaUs = abs(c.durationUs - original.durationUs)
        val audioKeyMatch = original.identity.verified10sAudioKey != null &&
            original.identity.verified10sAudioKey == verified.verifiedWindowAudioKey &&
            durationDeltaUs <= 50_000L

        return if (fpMatch || audioKeyMatch) {
            val exact = RelinkCandidateEvaluation.ExactMatch(
                trackId = trackId,
                candidateUri = candidateUri,
                candidateDisplayName = candidateName,
                matchedByContentFingerprint = fpMatch,
                matchedByAudioKey = audioKeyMatch,
                candidateIdentity = candidateIdentity,
                durationUs = c.durationUs,
                mimeType = c.mimeType,
                codecDescription = c.codecDescription,
                sampleRateHz = c.sampleRateHz,
                channelCount = c.channelCount,
                bitsPerSample = c.bitsPerSample
            )
            if (autoApplyOnExactMatch) {
                confirmRelink(exact, userConfirmedUncertainMatch = true)
                _pendingRelinkEvaluation.value = null
            } else {
                _pendingRelinkEvaluation.value = exact
            }
            exact
        } else {
            val sameName = candidateName.equals(original.displayName, ignoreCase = true)
            val reason = buildString {
                if (sameName) {
                    append("Same filename ('$candidateName'), but content fingerprint and decoded audio differ! ")
                } else {
                    append("Replacement file ('$candidateName') has a different content fingerprint. ")
                }
                append("Duration delta: ${durationDeltaUs / 1000} ms. Explicit confirmation is required to replace this track.")
            }
            val uncertain = RelinkCandidateEvaluation.UncertainMatchRequiresConfirmation(
                trackId = trackId,
                candidateUri = candidateUri,
                candidateDisplayName = candidateName,
                reason = reason,
                originalDurationUs = original.durationUs,
                candidateDurationUs = c.durationUs,
                durationDeltaUs = durationDeltaUs,
                sameDisplayName = sameName,
                candidateIdentity = candidateIdentity,
                mimeType = c.mimeType,
                codecDescription = c.codecDescription,
                sampleRateHz = c.sampleRateHz,
                channelCount = c.channelCount,
                bitsPerSample = c.bitsPerSample
            )
            _pendingRelinkEvaluation.value = uncertain
            uncertain
        }
    }

    override fun confirmRelink(
        evaluation: RelinkCandidateEvaluation,
        userConfirmedUncertainMatch: Boolean
    ): LocalTrackRecord? {
        val now = clockMs()
        synchronized(this) {
            val list = _tracks.value.toMutableList()
            val idx = list.indexOfFirst { it.trackId == evaluation.trackId }
            if (idx < 0) return null
            val existing = list[idx]

            val updated: LocalTrackRecord = when (evaluation) {
                is RelinkCandidateEvaluation.ExactMatch -> {
                    existing.copy(
                        displayName = evaluation.candidateDisplayName,
                        durationUs = evaluation.durationUs,
                        mimeType = evaluation.mimeType,
                        codecDescription = evaluation.codecDescription,
                        sampleRateHz = evaluation.sampleRateHz,
                        channelCount = evaluation.channelCount,
                        bitsPerSample = evaluation.bitsPerSample,
                        identity = evaluation.candidateIdentity,
                        lastVerifiedUtcMs = now,
                        availabilityState = TrackAvailabilityState.AVAILABLE,
                        lastErrorCode = null,
                        statusDetail = "Relinked (verified fingerprint match)"
                    )
                }
                is RelinkCandidateEvaluation.UncertainMatchRequiresConfirmation -> {
                    if (!userConfirmedUncertainMatch) {
                        return null
                    }
                    existing.copy(
                        displayName = evaluation.candidateDisplayName,
                        durationUs = evaluation.candidateDurationUs,
                        mimeType = evaluation.mimeType,
                        codecDescription = evaluation.codecDescription,
                        sampleRateHz = evaluation.sampleRateHz,
                        channelCount = evaluation.channelCount,
                        bitsPerSample = evaluation.bitsPerSample,
                        identity = evaluation.candidateIdentity,
                        lastVerifiedUtcMs = now,
                        availabilityState = TrackAvailabilityState.AVAILABLE,
                        lastErrorCode = AudioErrorCode.A05_SOURCE_CHANGED,
                        statusDetail = "Relinked with user confirmation (recording fingerprint changed; dependent transition plans marked stale)"
                    )
                }
                is RelinkCandidateEvaluation.RejectedCandidate -> return null
            }

            list[idx] = updated
            _tracks.value = list
            _pendingRelinkEvaluation.value = null
            persistSnapshot()
            diagnostics.record(
                category = "TrackRelink",
                message = "Relinked trackId=${updated.trackId} state=${updated.availabilityState}"
            )
            return updated
        }
    }

    override fun clearPendingRelinkEvaluation() {
        _pendingRelinkEvaluation.value = null
    }

    override fun getTrack(trackId: String): LocalTrackRecord? =
        _tracks.value.firstOrNull { it.trackId == trackId }

    override fun resolve(trackId: String): TrackResolutionResult {
        val verified = verifyTrack(trackId)
        if (verified == null) {
            diagnostics.record(
                category = "TrackResolver",
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Track not found in library: trackId=$trackId"
            )
            return TrackResolutionResult.RelinkRequired(
                trackId = trackId,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING
            )
        }
        return when (verified.availabilityState) {
            TrackAvailabilityState.AVAILABLE ->
                TrackResolutionResult.GrantedReadableUri(verified.identity.contentUri)
            TrackAvailabilityState.MISSING_OR_REVOKED,
            TrackAvailabilityState.CLOUD_NOT_OFFLINE ->
                TrackResolutionResult.RelinkRequired(
                    trackId = trackId,
                    errorCode = verified.lastErrorCode ?: AudioErrorCode.A01_SOURCE_MISSING
                )
            TrackAvailabilityState.SOURCE_MUTATED ->
                TrackResolutionResult.RelinkRequired(
                    trackId = trackId,
                    errorCode = AudioErrorCode.A05_SOURCE_CHANGED
                )
            TrackAvailabilityState.UNSUPPORTED_OR_CORRUPT ->
                TrackResolutionResult.DecodeUnsupported(
                    trackId = trackId,
                    mimeType = verified.mimeType,
                    errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC
                )
        }
    }

    override fun reloadFromStorage() {
        val dir = storageDirectory ?: return
        val file = File(dir, STORAGE_FILENAME)
        if (!file.exists()) return
        runCatching {
            val raw = file.readText(Charsets.UTF_8)
            val snap = json.decodeFromString<PersistedTrackLibrarySnapshot>(raw)
            synchronized(this) {
                _tracks.value = snap.tracks
            }
        }.onFailure { e ->
            diagnostics.record(
                category = "TrackStorage",
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Failed to load persisted track snapshot: ${e.message}"
            )
        }
    }

    private fun persistSnapshot() {
        val dir = storageDirectory ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val tmpFile = File(dir, "$STORAGE_FILENAME.tmp")
            val finalFile = File(dir, STORAGE_FILENAME)
            val snap = PersistedTrackLibrarySnapshot(
                schemaVersion = 1,
                tracks = _tracks.value
            )
            tmpFile.writeText(json.encodeToString(snap), Charsets.UTF_8)
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
        }
    }

    private fun fallbackDisplayName(uriString: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(uriString, "UTF-8") }.getOrDefault(uriString)
        val slash = decoded.lastIndexOf('/')
        val colon = decoded.lastIndexOf(':')
        val cut = maxOf(slash, colon)
        val tail = if (cut >= 0 && cut + 1 < decoded.length) decoded.substring(cut + 1) else decoded
        return MetadataNormalizer.sanitizeText(tail, fallback = "Untitled")
    }

    companion object {
        const val STORAGE_FILENAME = "stage03_tracks_v1.json"
    }
}
