/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.analysis

import app.myspoti.automix.Analyzer
import app.myspoti.automix.TrackAnalysis
import app.setflow.db.BeatGridCorrectionEntity
import app.setflow.db.EdgeValidationStatus
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import app.setflow.io.AudioContainerInspector
import app.setflow.io.AudioDecodeVerifier
import app.setflow.io.AudioVerificationException
import app.setflow.io.TrackSourceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates audio decoding, Beat This! ONNX inference with pure-Kotlin [app.myspoti.automix.BeatTracker] fallback,
 * segmented head/tail grid fitting, two-tier caching, in-flight request deduplication, and persisted manual corrections
 * (Stage 06, STAGES/06_ANALYSIS_GRID_CACHE.txt).
 *
 * Guarantees:
 * - Threading: All analysis runs on [dispatcher] ([Dispatchers.Default]), never blocking Compose main thread.
 * - Concurrency: Bounded by [maxConcurrent] semaphore (default 2) to preserve audio playback headroom.
 * - Deduplication: In-flight analyses for the same recording identity share a single [Deferred].
 * - Invalidation: User corrections increment revision, invalidate caches, and flag affected edges as STALE_ANALYSIS (D10).
 */
class TrackAnalysisManager(
    private val database: SetFlowProjectDatabase,
    private val sourceProvider: TrackSourceProvider,
    private val modelProvider: BeatThisModelProvider,
    private val cache: TrackAnalysisCache,
    private val diagnostics: DiagnosticsRingBuffer? = null,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    parentScope: CoroutineScope? = null
) : TrackAnalysisEngine {

    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val concurrencySemaphore = Semaphore(maxConcurrent)

    // TrackId -> AnalysisStatus for UI observation
    private val _states = MutableStateFlow<Map<String, AnalysisStatus>>(emptyMap())
    override val states: StateFlow<Map<String, AnalysisStatus>> = _states.asStateFlow()

    // Deduplication map: recordingKey -> in-flight Deferred<AnalysisStatus>
    private val inFlight = ConcurrentHashMap<String, Deferred<AnalysisStatus>>()

    override fun getStatus(trackId: String): AnalysisStatus {
        return _states.value[trackId] ?: AnalysisStatus.Idle
    }

    override suspend fun analyze(
        trackId: String,
        priority: AnalysisPriority,
        forceReanalysis: Boolean
    ): AnalysisStatus = withContext(dispatcher) {
        val track = database.getTrack(trackId) ?: run {
            val failed = AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Track not found in database: $trackId"
            )
            updateState(trackId, failed)
            return@withContext failed
        }

        val recordingKey = "${track.trackId}:${track.contentFingerprint}"

        if (!forceReanalysis) {
            // Check in-flight deduplication
            inFlight[recordingKey]?.let { existingDeferred ->
                diagnostics?.record(
                    category = "AnalysisManager",
                    phase = SchedulerPhase.IDLE,
                    message = "Deduplicated in-flight analysis request for track $trackId"
                )
                return@withContext existingDeferred.await()
            }
        }

        val deferred = async(dispatcher) {
            try {
                concurrencySemaphore.withPermit {
                    executeAnalysis(track, forceReanalysis)
                }
            } finally {
                inFlight.remove(recordingKey)
            }
        }

        inFlight[recordingKey] = deferred
        deferred.await()
    }

    private suspend fun executeAnalysis(
        track: TrackEntity,
        forceReanalysis: Boolean
    ): AnalysisStatus = withContext(dispatcher) {
        val trackId = track.trackId
        val recordingIdentity = track.contentFingerprint.ifBlank { track.trackId }
        val correction = database.getBeatGridCorrection(trackId)
        val correctionRevision = correction?.revision ?: 0L

        // 1. Check cache (unless forceReanalysis)
        val knownAudioKey = track.audioFingerprint.orEmpty()
        val cacheKey = cache.computeKey(
            recordingIdentity = recordingIdentity,
            audioKey = knownAudioKey,
            modelFingerprint = modelProvider.modelFingerprint,
            correctionRevision = correctionRevision
        )

        if (!forceReanalysis) {
            val cached = cache.get(cacheKey)
            if (cached != null) {
                val effectiveAnalysis = if (correction != null) {
                    ManualGridCorrector.applyCorrection(cached, correction)
                } else {
                    cached
                }
                val status = classifyStatus(effectiveAnalysis, isManual = correction != null, cacheHit = true)
                updateState(trackId, status)
                return@withContext status
            }
        }

        updateState(trackId, AnalysisStatus.InProgress(0.1f))

        // 2. Open audio channel and decode 22.05k mid/side
        val channel = try {
            sourceProvider.openReadOnlyChannel(track.contentUri)
        } catch (e: SecurityException) {
            val failed = AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
                message = e.message ?: "Permission denied reading ${track.contentUri}"
            )
            updateState(trackId, failed)
            return@withContext failed
        } catch (e: java.io.FileNotFoundException) {
            val failed = AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Source file missing: ${track.contentUri}"
            )
            updateState(trackId, failed)
            return@withContext failed
        } catch (e: IOException) {
            val failed = AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                message = "Failed to open audio channel: ${e.message}"
            )
            updateState(trackId, failed)
            return@withContext failed
        }

        try {
            channel.use { ch ->
                val container = AudioContainerInspector.inspect(ch) { !isActive }
                updateState(trackId, AnalysisStatus.InProgress(0.35f))

                val (mid, side) = AudioDecodeVerifier.decodeToMidSide22050(
                    channel = ch,
                    container = container,
                    maxSeconds = Int.MAX_VALUE,
                    cancelled = { !isActive }
                )

                if (mid.isEmpty()) {
                    val failed = AnalysisStatus.Failed(
                        errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                        message = "Decoder produced 0 frames for ${track.displayName}"
                    )
                    updateState(trackId, failed)
                    return@withContext failed
                }

                updateState(trackId, AnalysisStatus.InProgress(0.65f))

                // 3. Compute signal key and execute ML / Kotlin Analyzer
                val audioKey = Analyzer.signalKey(mid, side)
                val model = modelProvider.createBeatThis(threads = 1)
                val rawAnalysis = try {
                    Analyzer.analyze(
                        mid = mid,
                        side = side.takeIf { container.channelCount > 1 },
                        model = model,
                        cancelled = { !isActive },
                        threads = 1
                    )
                } finally {
                    model?.close()
                }

                updateState(trackId, AnalysisStatus.InProgress(0.9f))

                // 4. Update track's audioFingerprint in DB if previously absent
                if (track.audioFingerprint != audioKey) {
                    database.upsertTrack(track.copy(audioFingerprint = audioKey))
                }

                // 5. Store raw analysis in cache
                val freshCacheKey = cache.computeKey(
                    recordingIdentity = recordingIdentity,
                    audioKey = audioKey,
                    modelFingerprint = modelProvider.modelFingerprint,
                    correctionRevision = 0L
                )
                cache.put(freshCacheKey, rawAnalysis, recordingIdentity)

                // 6. Apply manual correction if present
                val finalAnalysis = if (correction != null) {
                    val corrected = ManualGridCorrector.applyCorrection(rawAnalysis, correction)
                    val correctedCacheKey = cache.computeKey(
                        recordingIdentity = recordingIdentity,
                        audioKey = audioKey,
                        modelFingerprint = modelProvider.modelFingerprint,
                        correctionRevision = correctionRevision
                    )
                    cache.put(correctedCacheKey, corrected, recordingIdentity)
                    corrected
                } else {
                    rawAnalysis
                }

                val status = classifyStatus(finalAnalysis, isManual = correction != null, cacheHit = false)
                updateState(trackId, status)
                status
            }
        } catch (e: CancellationException) {
            val failed = AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A11_CANCELLED,
                message = "Analysis cancelled for ${track.displayName}"
            )
            updateState(trackId, failed)
            throw e
        } catch (e: InterruptedException) {
            val failed = AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A11_CANCELLED,
                message = "Analysis interrupted for ${track.displayName}"
            )
            updateState(trackId, failed)
            failed
        } catch (e: AudioVerificationException) {
            val failed = AnalysisStatus.Failed(
                errorCode = e.errorCode,
                message = e.message
            )
            updateState(trackId, failed)
            failed
        } catch (e: IOException) {
            val failed = AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
                message = "I/O failure during analysis: ${e.message}"
            )
            updateState(trackId, failed)
            failed
        } catch (t: Throwable) {
            val failed = AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A04_BAD_ANALYSIS,
                message = "Analysis error: ${t.javaClass.simpleName} - ${t.message}"
            )
            updateState(trackId, failed)
            failed
        }
    }

    override suspend fun applyManualCorrection(
        trackId: String,
        params: BeatGridCorrectionParams
    ): AnalysisStatus = withContext(dispatcher) {
        val track = database.getTrack(trackId) ?: run {
            return@withContext AnalysisStatus.Failed(
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Track not found: $trackId"
            )
        }

        val existingCorrection = database.getBeatGridCorrection(trackId)
        val newRevision = (existingCorrection?.revision ?: 0L) + 1L

        // Determine effective BPM
        val baseAnalysis = (getStatus(trackId) as? AnalysisStatus.Confident)?.analysis
            ?: (getStatus(trackId) as? AnalysisStatus.Unreliable)?.analysis
        val currentBpm = baseAnalysis?.bpm ?: 120.0

        val effectiveBpm = when {
            params.halfDoubleAction != null -> ManualGridCorrector.resolveHalfDoubleBpm(currentBpm, params.halfDoubleAction)
            params.bpmOverride != null -> params.bpmOverride
            else -> existingCorrection?.bpmOverride ?: currentBpm
        }

        val newCorrection = BeatGridCorrectionEntity(
            trackId = trackId,
            revision = newRevision,
            bpmOverride = effectiveBpm,
            phaseAnchorUs = params.phaseAnchorUs ?: existingCorrection?.phaseAnchorUs,
            beatsPerBar = params.beatsPerBar ?: existingCorrection?.beatsPerBar ?: 4,
            regionStartUs = params.regionStartUs ?: existingCorrection?.regionStartUs,
            regionEndUs = params.regionEndUs ?: existingCorrection?.regionEndUs,
            confidenceOverride = existingCorrection?.confidenceOverride, // Do NOT fake neural confidence!
            createdAtUtc = System.currentTimeMillis()
        )

        // 1. Persist correction in SQLite
        database.saveBeatGridCorrection(newCorrection)

        // 2. Invalidate caches for this recording
        val recordingIdentity = track.contentFingerprint.ifBlank { track.trackId }
        cache.invalidateForRecording(recordingIdentity)

        // 3. Mark affected edges as STALE_ANALYSIS without erasing user overrides (D10)
        val invalidatedEdges = database.invalidateEdgesForTrack(trackId, EdgeValidationStatus.STALE_ANALYSIS)
        diagnostics?.record(
            category = "AnalysisManager",
            phase = SchedulerPhase.IDLE,
            message = "Manual grid correction rev $newRevision saved for track $trackId; flagged ${invalidatedEdges.size} edges as STALE_ANALYSIS"
        )

        // 4. Re-run analysis with fresh correction
        analyze(trackId, priority = AnalysisPriority.PLAYBACK_ARMED, forceReanalysis = false)
    }

    override fun cancelAnalysis(trackId: String) {
        // Handled via coroutine scope / caller job cancellation
        updateState(trackId, AnalysisStatus.Failed(AudioErrorCode.A11_CANCELLED, "Cancelled by user"))
    }

    override fun clearCache() {
        cache.clear()
        _states.value = emptyMap()
    }

    private fun classifyStatus(
        analysis: TrackAnalysis,
        isManual: Boolean,
        cacheHit: Boolean
    ): AnalysisStatus {
        val headReliable = analysis.head?.reliable == true
        val tailReliable = analysis.tail?.reliable == true
        val isSteady = analysis.steady

        return if ((headReliable || tailReliable) && analysis.bpm.isFinite() && analysis.bpm > 0.0) {
            AnalysisStatus.Confident(
                analysis = analysis,
                isManualCorrection = isManual,
                bpm = analysis.bpm,
                key = analysis.key,
                steady = isSteady,
                cacheHit = cacheHit
            )
        } else {
            AnalysisStatus.Unreliable(
                analysis = analysis,
                isManualCorrection = isManual,
                bpm = analysis.bpm,
                reason = if (analysis.note.isNotBlank()) analysis.note else "Unsteady beat-grid or high tempo drift",
                cacheHit = cacheHit
            )
        }
    }

    private fun updateState(trackId: String, status: AnalysisStatus) {
        val current = _states.value.toMutableMap()
        current[trackId] = status
        _states.value = current
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 2
    }
}
