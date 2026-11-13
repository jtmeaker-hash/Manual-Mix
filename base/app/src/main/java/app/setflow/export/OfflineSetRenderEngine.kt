/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.export

import app.myspoti.automix.segue.LaneCurve
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SegueDeck
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.Target
import app.setflow.db.ExportJobEntity
import app.setflow.db.ProjectDetailSnapshot
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import app.setflow.domain.TrackAvailabilityState
import app.setflow.playback.DualDeckPcmEngine
import app.setflow.playback.SetTimeline
import app.setflow.playback.SetTimelineResolution
import app.setflow.playback.SetTimelineResolver
import app.setflow.playback.TimelineEdgeSlot
import app.setflow.playback.TimelineOccurrenceSlot
import com.music.spotui.di.CrossfadeRamp
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Result of an offline full-set export job (Stage 11).
 */
sealed interface ExportResult {
    data class Success(
        val jobId: String,
        val destinationPath: String,
        val metadata: ExportJobMetadata,
        val summary: WavExportSummary,
        val validation: WavValidationResult.Valid,
        val isSnapshotOutdated: Boolean = false,
        val currentProjectRevision: Long? = null
    ) : ExportResult

    data class Cancelled(
        val jobId: String,
        val progress: Double,
        val framesRendered: Long
    ) : ExportResult

    data class Error(
        val jobId: String,
        val errorCode: AudioErrorCode,
        val reason: String
    ) : ExportResult
}

/**
 * Metadata snapshot captured BEFORE render starts (Stage 11 Task 2).
 */
data class ExportJobMetadata(
    val jobId: String,
    val projectId: String,
    val capturedProjectRevision: Long,
    val capturedOccurrenceIds: List<String>,
    val capturedSourceFingerprints: List<String>,
    val capturedPlanHashes: List<String>,
    val format: String = "WAV_16BIT",
    val sampleRateHz: Int = 44_100,
    val channels: Int = 2
)

/**
 * Internal timeline segment: either a solo track segment or a dual-deck transition overlap.
 */
private sealed interface TimelineSegment {
    val startGlobalFrame: Long
    val endGlobalFrame: Long
    val frameCount: Long get() = endGlobalFrame - startGlobalFrame

    data class Solo(
        val occurrenceIndex: Int,
        val occurrenceSlot: TimelineOccurrenceSlot,
        override val startGlobalFrame: Long,
        override val endGlobalFrame: Long,
        val initialLocalFrame: Long
    ) : TimelineSegment

    data class Overlap(
        val edgeIndex: Int,
        val edgeSlot: TimelineEdgeSlot,
        val outgoingSlot: TimelineOccurrenceSlot,
        val incomingSlot: TimelineOccurrenceSlot,
        override val startGlobalFrame: Long,
        override val endGlobalFrame: Long,
        val exitAnchorFrame: Long,
        val entryAnchorFrame: Long
    ) : TimelineSegment
}

/**
 * Sample-streaming offline renderer with bounded PCM blocks and exact DSP parity with live playback
 * (Stage 11 STAGES/11_OFFLINE_EXPORT.txt).
 *
 * Key guarantees:
 * - Constant O(1) heap memory regardless of set duration (streams in 4096-frame blocks).
 * - Snapshot revision locking: captures project revision, occurrence IDs, source keys, and plan hashes before render.
 * - Enforces 4GiB RIFF size limit capping with explicit [AudioErrorCode.A12_TIMELINE_OVERFLOW].
 * - Exact DSP parity: reuses upstream [SegueDeck], [LaneCurve], and [DualDeckPcmEngine.applySoftKneeHeadroomLimiter].
 * - Clean cancellation and disk I/O error recovery (partial file deleted, DB uncorrupted, state CANCELLED/FAILED).
 * - Independent read-back validation via [WavReaderValidator] before marking COMPLETED or progress = 1.0.
 */
class OfflineSetRenderEngine(
    private val database: SetFlowProjectDatabase? = null,
    private val diagnostics: DiagnosticsRingBuffer = DiagnosticsRingBuffer()
) {

    /**
     * Renders an immutable [snapshot] of a set to [destinationFile].
     *
     * @param snapshot The immutable project detail snapshot captured before starting export.
     * @param destinationFile The target WAV file on disk.
     * @param jobId Unique identifier for this export job.
     * @param sampleRate Sampling rate in Hz (default 44100).
     * @param channels Channel count (default 2 stereo).
     * @param bitsPerSample Bit depth (default 16).
     * @param isCancelled Callback polled every block to check if user cancelled export.
     * @param onProgress Callback invoked monotonically during render (0.0 to 0.99 during render, 1.0 on validated completion).
     * @param readerProvider Factory providing a streaming reader for each occurrence. If null, resolves from track URI/file.
     */
    fun renderProject(
        snapshot: ProjectDetailSnapshot,
        destinationFile: File,
        jobId: String = UUID.randomUUID().toString(),
        sampleRate: Int = 44_100,
        channels: Int = 2,
        bitsPerSample: Int = 16,
        isCancelled: () -> Boolean = { false },
        onProgress: (progress: Double, framesWritten: Long, totalFrames: Long) -> Unit = { _, _, _ -> },
        readerProvider: ((occurrenceId: String, trackId: String) -> StreamingTrackAudioReader)? = null
    ): ExportResult {
        // 1. Task 2: Capture pre-render metadata snapshot
        val metadata = ExportJobMetadata(
            jobId = jobId,
            projectId = snapshot.project.projectId,
            capturedProjectRevision = snapshot.project.editRevision,
            capturedOccurrenceIds = snapshot.occurrences.sortedBy { it.occurrence.positionIndex }.map { it.occurrence.occurrenceId },
            capturedSourceFingerprints = snapshot.occurrences.sortedBy { it.occurrence.positionIndex }.map {
                it.track.audioFingerprint ?: it.track.contentFingerprint
            },
            capturedPlanHashes = snapshot.edges.map { edgeSnap ->
                edgeSnap.resolvedPlan?.planHashSha256
                    ?: edgeSnap.resolvedPlan?.canonicalSegueJson?.let { runCatching { SegueCodec.decode(it) }.getOrNull() }?.let { SegueCodec.fingerprint(it) }
                    ?: edgeSnap.autoPlan?.planCanonicalJson?.let { runCatching { SegueCodec.decode(it) }.getOrNull() }?.let { SegueCodec.fingerprint(it) }
                    ?: ""
            },
            format = if (bitsPerSample == 16) "WAV_16BIT" else "WAV_${bitsPerSample}BIT",
            sampleRateHz = sampleRate,
            channels = channels
        )

        // 2. Resolve Global Set Timeline (Task 4)
        val timeline = when (val res = SetTimelineResolver.resolve(snapshot)) {
            is SetTimelineResolution.Success -> res.timeline
            is SetTimelineResolution.Error -> {
                recordDiagnostic(jobId, res.violationCode, res.reason)
                updateJobInDb(jobId, metadata, "FAILED", 0.0, 0L, res.violationCode.name)
                return ExportResult.Error(jobId, res.violationCode, res.reason)
            }
        }

        val totalFrames = ((timeline.totalDurationUs.toDouble() * sampleRate) / 1_000_000.0).toLong()

        // 3. 4GiB RIFF Limit Pre-check (Task 3, Bug Trap 40)
        val bytesPerFrame = channels * (bitsPerSample / 8)
        val estimatedFileBytes = WavWriter.HEADER_SIZE + totalFrames * bytesPerFrame
        if (estimatedFileBytes > WavWriter.MAX_RIFF_BYTES) {
            val err = AudioErrorCode.A12_TIMELINE_OVERFLOW
            val msg = "Total planned export duration (${timeline.totalDurationUs / 1_000_000}s / $totalFrames frames) " +
                "would produce $estimatedFileBytes bytes, exceeding 4GiB RIFF limit (${WavWriter.MAX_RIFF_BYTES} bytes)"
            recordDiagnostic(jobId, err, msg)
            updateJobInDb(jobId, metadata, "FAILED", 0.0, totalFrames, err.name)
            return ExportResult.Error(jobId, err, msg)
        }

        // 4. Initial database registration: RUNNING at progress 0.0
        updateJobInDb(jobId, metadata, "RUNNING", 0.0, totalFrames, null)

        // 5. Pre-build contiguous timeline segments (Solo vs Overlap)
        val segments = buildTimelineSegments(timeline, sampleRate)

        // 6. Streaming render loop
        var wavWriter: WavWriter? = null
        val activeReaders = mutableMapOf<String, StreamingTrackAudioReader>()

        fun getReader(occurrenceSlot: TimelineOccurrenceSlot): StreamingTrackAudioReader {
            return activeReaders.getOrPut(occurrenceSlot.occurrenceId) {
                if (readerProvider != null) {
                    readerProvider(occurrenceSlot.occurrenceId, occurrenceSlot.trackId)
                } else {
                    resolveDefaultTrackReader(snapshot, occurrenceSlot)
                }
            }
        }

        fun closeReader(occurrenceId: String) {
            activeReaders.remove(occurrenceId)?.let { reader ->
                runCatching { reader.close() }
            }
        }

        var totalFramesRendered = 0L

        try {
            destinationFile.parentFile?.mkdirs()
            wavWriter = WavWriter(
                destinationFile = destinationFile,
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample
            )

            val chunkCapacity = 4096
            val outBuffer = FloatArray(chunkCapacity * channels)
            val scratchA = FloatArray(chunkCapacity * channels)
            val scratchB = FloatArray(chunkCapacity * channels)
            val frameScratchA = FloatArray(channels)
            val frameScratchB = FloatArray(channels)

            for (segmentIndex in segments.indices) {
                val seg = segments[segmentIndex]

                when (seg) {
                    is TimelineSegment.Solo -> {
                        val reader = getReader(seg.occurrenceSlot)
                        var frameOffsetInSeg = 0L
                        val segFrames = seg.frameCount

                        while (frameOffsetInSeg < segFrames) {
                            if (isCancelled()) {
                                return handleCancellation(jobId, metadata, destinationFile, wavWriter, activeReaders, totalFramesRendered, totalFrames)
                            }

                            val chunkFrames = minOf(chunkCapacity.toLong(), segFrames - frameOffsetInSeg).toInt()
                            val localSourceFrame = seg.initialLocalFrame + frameOffsetInSeg

                            val read = reader.readFrames(localSourceFrame, chunkFrames, outBuffer, 0)
                            if (read < chunkFrames) {
                                // Zero-pad if source ended prematurely
                                for (i in (read * channels) until (chunkFrames * channels)) {
                                    outBuffer[i] = 0.0f
                                }
                            }

                            wavWriter.writeFrames(outBuffer, chunkFrames)
                            totalFramesRendered += chunkFrames
                            frameOffsetInSeg += chunkFrames

                            val progress = if (totalFrames > 0) {
                                min(0.99, (totalFramesRendered.toDouble() / totalFrames.toDouble()) * 0.99)
                            } else 0.0
                            onProgress(progress, totalFramesRendered, totalFrames)
                            updateJobInDb(jobId, metadata, "RUNNING", progress, totalFrames, null)
                        }

                        // If next segment does not use this occurrence, close its reader to free memory
                        val nextSeg = segments.getOrNull(segmentIndex + 1)
                        val neededInNext = when (nextSeg) {
                            is TimelineSegment.Solo -> nextSeg.occurrenceSlot.occurrenceId == seg.occurrenceSlot.occurrenceId
                            is TimelineSegment.Overlap -> nextSeg.outgoingSlot.occurrenceId == seg.occurrenceSlot.occurrenceId ||
                                nextSeg.incomingSlot.occurrenceId == seg.occurrenceSlot.occurrenceId
                            null -> false
                        }
                        if (!neededInNext) {
                            closeReader(seg.occurrenceSlot.occurrenceId)
                        }
                    }

                    is TimelineSegment.Overlap -> {
                        val readerA = getReader(seg.outgoingSlot)
                        val readerB = getReader(seg.incomingSlot)
                        var frameOffsetInSeg = 0L
                        val segFrames = seg.frameCount

                        val plan = seg.edgeSlot.seguePlan
                        val segueDeckA = if (plan != null) SegueDeck(plan, LaneSide.OUTGOING, sampleRate, channels) else null
                        val segueDeckB = if (plan != null) SegueDeck(plan, LaneSide.INCOMING, sampleRate, channels) else null
                        val levelLaneA = plan?.lanes?.firstOrNull { it.side == LaneSide.OUTGOING && it.target == Target.LEVEL }
                        val levelLaneB = plan?.lanes?.firstOrNull { it.side == LaneSide.INCOMING && it.target == Target.LEVEL }
                        val levelCurveA = levelLaneA?.let(::LaneCurve)
                        val levelCurveB = levelLaneB?.let(::LaneCurve)
                        val effectiveRateB = seg.edgeSlot.ratePpmB.takeIf { it > 0L } ?: 1_000_000L

                        fun planGainA(sourceUsA: Long, fallback: Float): Float {
                            val curve = levelCurveA ?: return fallback
                            val spanUs = max(1L, seg.edgeSlot.spanUs)
                            val u = (sourceUsA - seg.edgeSlot.exitAnchorUs).toDouble() / spanUs * SegueFormat.T_MAX
                            return if (curve.isNull(u)) 0.0f else 10.0.pow(curve.value(u) / 20.0).toFloat()
                        }

                        fun planGainB(sourceUsB: Long, fallback: Float): Float {
                            val curve = levelCurveB ?: return fallback
                            val spanUs = max(1L, seg.edgeSlot.spanUs)
                            val u = (sourceUsB - seg.edgeSlot.entryAnchorUs).toDouble() * SegueFormat.RATE_NOMINAL / effectiveRateB / spanUs * SegueFormat.T_MAX
                            return if (curve.isNull(u)) 0.0f else 10.0.pow(curve.value(u) / 20.0).toFloat()
                        }

                        while (frameOffsetInSeg < segFrames) {
                            if (isCancelled()) {
                                return handleCancellation(jobId, metadata, destinationFile, wavWriter, activeReaders, totalFramesRendered, totalFrames)
                            }

                            val chunkFrames = minOf(chunkCapacity.toLong(), segFrames - frameOffsetInSeg).toInt()
                            val localSourceFrameA = seg.exitAnchorFrame + frameOffsetInSeg
                            val localSourceFrameB = seg.entryAnchorFrame + frameOffsetInSeg

                            val readA = readerA.readFrames(localSourceFrameA, chunkFrames, scratchA, 0)
                            if (readA < chunkFrames) {
                                for (i in (readA * channels) until (chunkFrames * channels)) scratchA[i] = 0.0f
                            }

                            val readB = readerB.readFrames(localSourceFrameB, chunkFrames, scratchB, 0)
                            if (readB < chunkFrames) {
                                for (i in (readB * channels) until (chunkFrames * channels)) scratchB[i] = 0.0f
                            }

                            for (i in 0 until chunkFrames) {
                                val currentOverlapFrame = frameOffsetInSeg + i
                                val progress = CrossfadeRamp.progress(currentOverlapFrame, max(1L, segFrames - 1L))
                                val gainA = CrossfadeRamp.outgoingGain(progress)
                                val gainB = CrossfadeRamp.incomingGain(progress)

                                val currentSourceFrameA = localSourceFrameA + i
                                val currentSourceFrameB = localSourceFrameB + i
                                val sourceUsA = (currentSourceFrameA * 1_000_000L) / sampleRate
                                val sourceUsB = (currentSourceFrameB * 1_000_000L) / sampleRate

                                for (c in 0 until channels) {
                                    frameScratchA[c] = scratchA[i * channels + c]
                                    frameScratchB[c] = scratchB[i * channels + c]
                                }

                                if (segueDeckA != null && segueDeckB != null) {
                                    segueDeckA.process(frameScratchA, 1, sourceUsA)
                                    segueDeckB.process(frameScratchB, 1, sourceUsB)
                                } else {
                                    for (c in 0 until channels) {
                                        frameScratchA[c] *= gainA
                                        frameScratchB[c] *= gainB
                                    }
                                }

                                for (c in 0 until channels) {
                                    val rawSum = frameScratchA[c] + frameScratchB[c]
                                    val sanitized = if (rawSum.isFinite()) rawSum else 0.0f
                                    val limited = DualDeckPcmEngine.applySoftKneeHeadroomLimiter(sanitized)
                                    outBuffer[i * channels + c] = limited
                                }
                            }

                            wavWriter.writeFrames(outBuffer, chunkFrames)
                            totalFramesRendered += chunkFrames
                            frameOffsetInSeg += chunkFrames

                            val progress = if (totalFrames > 0) {
                                min(0.99, (totalFramesRendered.toDouble() / totalFrames.toDouble()) * 0.99)
                            } else 0.0
                            onProgress(progress, totalFramesRendered, totalFrames)
                            updateJobInDb(jobId, metadata, "RUNNING", progress, totalFrames, null)
                        }

                        // Deck A is permanently finished after this overlap; close its reader!
                        closeReader(seg.outgoingSlot.occurrenceId)
                    }
                }
            }

            // Close all remaining readers
            activeReaders.values.forEach { runCatching { it.close() } }
            activeReaders.clear()

            if (isCancelled()) {
                return handleCancellation(jobId, metadata, destinationFile, wavWriter, activeReaders, totalFramesRendered, totalFrames)
            }

            // 7. Finalize WAV header (Task 6)
            val summary = wavWriter.finalizeHeader()
            wavWriter.close()
            wavWriter = null

            // 8. Independent Read-Back Validation (Task 8 & Bug Trap 40)
            val validation = WavReaderValidator.validate(destinationFile, checkAllSamples = true)
            when (validation) {
                is WavValidationResult.Invalid -> {
                    destinationFile.delete()
                    recordDiagnostic(jobId, validation.errorCode, "Read-back validation failed: ${validation.reason}")
                    updateJobInDb(jobId, metadata, "FAILED", 0.0, totalFrames, validation.errorCode.name)
                    return ExportResult.Error(jobId, validation.errorCode, validation.reason)
                }
                is WavValidationResult.Valid -> {
                    // Check if project was edited during export job (Task 2)
                    var isSnapshotOutdated = false
                    var currentRev: Long? = null
                    if (database != null) {
                        val currentDetail = database.getProjectDetail(snapshot.project.projectId)
                        if (currentDetail != null) {
                            currentRev = currentDetail.project.editRevision
                            if (currentRev != snapshot.project.editRevision) {
                                isSnapshotOutdated = true
                            }
                        }
                    }

                    // Progress STRICTLY reaches 1.0 ONLY AFTER header finalization and read-back validation pass!
                    onProgress(1.0, totalFramesRendered, totalFrames)
                    updateJobInDb(jobId, metadata, "COMPLETED", 1.0, totalFrames, null, completedAt = System.currentTimeMillis())

                    recordDiagnostic(
                        jobId = jobId,
                        code = null,
                        message = "Export completed: ${summary.totalFrames} frames, peak ${summary.peakAmplitude}, SHA-256 ${validation.sha256Hex}"
                    )

                    return ExportResult.Success(
                        jobId = jobId,
                        destinationPath = destinationFile.absolutePath,
                        metadata = metadata,
                        summary = summary,
                        validation = validation,
                        isSnapshotOutdated = isSnapshotOutdated,
                        currentProjectRevision = currentRev
                    )
                }
            }

        } catch (e: AudioExportException) {
            handleError(jobId, metadata, destinationFile, wavWriter, activeReaders, e.errorCode, e.message)
            return ExportResult.Error(jobId, e.errorCode, e.message)
        } catch (e: IOException) {
            handleError(jobId, metadata, destinationFile, wavWriter, activeReaders, AudioErrorCode.A10_EXPORT_IO_FAILURE, e.message ?: "I/O failure during export")
            return ExportResult.Error(jobId, AudioErrorCode.A10_EXPORT_IO_FAILURE, e.message ?: "I/O failure")
        } catch (e: Exception) {
            handleError(jobId, metadata, destinationFile, wavWriter, activeReaders, AudioErrorCode.A07_DSP_NONFINITE, e.message ?: "Unexpected export failure")
            return ExportResult.Error(jobId, AudioErrorCode.A07_DSP_NONFINITE, e.message ?: "Unexpected failure")
        }
    }

    private fun handleCancellation(
        jobId: String,
        metadata: ExportJobMetadata,
        destinationFile: File,
        wavWriter: WavWriter?,
        activeReaders: MutableMap<String, StreamingTrackAudioReader>,
        renderedFrames: Long,
        totalFrames: Long
    ): ExportResult.Cancelled {
        runCatching { wavWriter?.close() }
        activeReaders.values.forEach { runCatching { it.close() } }
        activeReaders.clear()
        runCatching { destinationFile.delete() }

        val progress = if (totalFrames > 0) (renderedFrames.toDouble() / totalFrames.toDouble()) * 0.99 else 0.0
        recordDiagnostic(jobId, AudioErrorCode.A11_CANCELLED, "Export cancelled by user at ${(progress * 100).toInt()}%")
        updateJobInDb(jobId, metadata, "CANCELLED", progress, totalFrames, AudioErrorCode.A11_CANCELLED.name)

        return ExportResult.Cancelled(jobId, progress, renderedFrames)
    }

    private fun handleError(
        jobId: String,
        metadata: ExportJobMetadata,
        destinationFile: File,
        wavWriter: WavWriter?,
        activeReaders: MutableMap<String, StreamingTrackAudioReader>,
        errorCode: AudioErrorCode,
        reason: String
    ) {
        runCatching { wavWriter?.close() }
        activeReaders.values.forEach { runCatching { it.close() } }
        activeReaders.clear()
        runCatching { destinationFile.delete() }

        recordDiagnostic(jobId, errorCode, reason)
        updateJobInDb(jobId, metadata, "FAILED", 0.0, 0L, errorCode.name)
    }

    private fun buildTimelineSegments(timeline: SetTimeline, sampleRate: Int): List<TimelineSegment> {
        val segments = mutableListOf<TimelineSegment>()
        val occs = timeline.occurrenceSlots
        val edges = timeline.edgeSlots

        if (occs.isEmpty()) return segments

        if (occs.size == 1) {
            val occ = occs.first()
            val totalFrames = ((occ.durationUs.toDouble() * sampleRate) / 1_000_000.0).toLong()
            segments.add(
                TimelineSegment.Solo(
                    occurrenceIndex = 0,
                    occurrenceSlot = occ,
                    startGlobalFrame = 0L,
                    endGlobalFrame = totalFrames,
                    initialLocalFrame = 0L
                )
            )
            return segments
        }

        var currentOccurrenceLocalStartFrame = 0L

        for (i in occs.indices) {
            val occ = occs[i]

            if (i < occs.size - 1) {
                val edge = edges[i]
                val overlapStartGlobalFrame = ((edge.overlapStartGlobalUs.toDouble() * sampleRate) / 1_000_000.0).toLong()
                val overlapEndGlobalFrame = ((edge.overlapEndGlobalUs.toDouble() * sampleRate) / 1_000_000.0).toLong()

                // 1. Solo playback of occurrence i before the transition overlap
                val soloStartGlobalFrame = if (i == 0) 0L else ((edges[i - 1].overlapEndGlobalUs.toDouble() * sampleRate) / 1_000_000.0).toLong()
                if (overlapStartGlobalFrame > soloStartGlobalFrame) {
                    segments.add(
                        TimelineSegment.Solo(
                            occurrenceIndex = i,
                            occurrenceSlot = occ,
                            startGlobalFrame = soloStartGlobalFrame,
                            endGlobalFrame = overlapStartGlobalFrame,
                            initialLocalFrame = currentOccurrenceLocalStartFrame
                        )
                    )
                }

                // 2. Overlap transition
                val exitAnchorFrame = ((edge.exitAnchorUs.toDouble() * sampleRate) / 1_000_000.0).toLong()
                val entryAnchorFrame = ((edge.entryAnchorUs.toDouble() * sampleRate) / 1_000_000.0).toLong()

                segments.add(
                    TimelineSegment.Overlap(
                        edgeIndex = i,
                        edgeSlot = edge,
                        outgoingSlot = occ,
                        incomingSlot = occs[i + 1],
                        startGlobalFrame = overlapStartGlobalFrame,
                        endGlobalFrame = overlapEndGlobalFrame,
                        exitAnchorFrame = exitAnchorFrame,
                        entryAnchorFrame = entryAnchorFrame
                    )
                )

                // The incoming track played 'overlapFrames' during overlap starting at entryAnchorFrame.
                // Its solo playback will resume from entryAnchorFrame + overlapFrames!
                val overlapFrames = overlapEndGlobalFrame - overlapStartGlobalFrame
                currentOccurrenceLocalStartFrame = entryAnchorFrame + overlapFrames

            } else {
                // Final occurrence solo playback until the end of the set
                val lastEdge = edges.last()
                val soloStartGlobalFrame = ((lastEdge.overlapEndGlobalUs.toDouble() * sampleRate) / 1_000_000.0).toLong()
                val totalFrames = ((timeline.totalDurationUs.toDouble() * sampleRate) / 1_000_000.0).toLong()

                if (totalFrames > soloStartGlobalFrame) {
                    segments.add(
                        TimelineSegment.Solo(
                            occurrenceIndex = i,
                            occurrenceSlot = occ,
                            startGlobalFrame = soloStartGlobalFrame,
                            endGlobalFrame = totalFrames,
                            initialLocalFrame = currentOccurrenceLocalStartFrame
                        )
                    )
                }
            }
        }

        return segments
    }

    private fun resolveDefaultTrackReader(
        snapshot: ProjectDetailSnapshot,
        slot: TimelineOccurrenceSlot
    ): StreamingTrackAudioReader {
        val occ = snapshot.occurrences.firstOrNull { it.occurrence.occurrenceId == slot.occurrenceId }
            ?: throw AudioExportException(AudioErrorCode.A01_SOURCE_MISSING, "Occurrence ${slot.occurrenceId} not found")

        val contentUri = occ.track.contentUri
        val file = if (contentUri.startsWith("/")) {
            File(contentUri)
        } else if (contentUri.startsWith("file://")) {
            File(java.net.URI(contentUri).path)
        } else null

        if (file != null && file.exists() && file.isFile) {
            return WavFileStreamingTrackReader(file)
        }

        // If contentUri is not a direct file, or null, throw explicit error
        throw AudioExportException(
            AudioErrorCode.A01_SOURCE_MISSING,
            "No readable audio source file for track ${occ.track.title} (${occ.track.trackId})"
        )
    }

    private fun updateJobInDb(
        jobId: String,
        metadata: ExportJobMetadata,
        status: String,
        progress: Double,
        plannedFrames: Long,
        failureCode: String?,
        completedAt: Long? = null
    ) {
        val db = database ?: return
        val entity = ExportJobEntity(
            jobId = jobId,
            projectId = metadata.projectId,
            projectRevision = metadata.capturedProjectRevision,
            format = metadata.format,
            destinationUri = "",
            status = status,
            progress = progress,
            plannedDurationFrames = plannedFrames,
            failureCode = failureCode,
            createdAt = System.currentTimeMillis(),
            completedAt = completedAt
        )
        runCatching { db.saveExportJob(entity) }
    }

    private fun recordDiagnostic(jobId: String, code: AudioErrorCode?, message: String) {
        diagnostics.record(
            category = "OfflineExport",
            phase = SchedulerPhase.SAFE_FALLBACK,
            errorCode = code,
            message = "Job $jobId: $message"
        )
    }
}
