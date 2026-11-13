/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SegueDeck
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.SegueSideFacts
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionPlanAdapter
import app.myspoti.automix.segue.TransitionResolutionResult
import app.setflow.db.EdgeEntity
import app.setflow.db.OccurrenceEntity
import app.setflow.db.OverrideSnapshotEntity
import app.setflow.db.ProjectDetailSnapshot
import app.setflow.db.ProjectEntity
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.SetFlowSchemaAndMigrations
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.TrackAvailabilityState
import app.setflow.export.ExportResult
import app.setflow.export.InMemoryPcmTrackReader
import app.setflow.export.OfflineSetRenderEngine
import app.setflow.export.ProceduralSyntheticTrackReader
import app.setflow.export.StreamingTrackAudioReader
import app.setflow.export.WavReaderValidator
import app.setflow.export.WavValidationResult
import app.setflow.export.WavWriter
import app.setflow.playback.ArmedTransitionBinding
import app.setflow.playback.DecodedPcmBuffer
import app.setflow.playback.DualDeckPcmEngine
import app.setflow.playback.SetTimelineResolution
import app.setflow.playback.SetTimelineResolver
import app.setflow.playback.SyntheticAudioFixtureGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Mandatory acceptance test suite for Stage 11: Offline Full-Set Audio Export & Render Engine
 * (STAGES/11_OFFLINE_EXPORT.txt).
 *
 * Validates:
 * - Constant O(1) memory streaming over multi-hour sets.
 * - Snapshot revision locking (user edits during export do not corrupt or alter active export).
 * - Exact frame-accurate global duration and overlap math.
 * - Exact DSP and automation curve parity with live playback engine.
 * - Full 20-track set export with unique transition plans.
 * - Safe cancellation at 1%, 50%, and 99% (file cleanup, DB uncorrupted, state CANCELLED).
 * - RIFF 4GiB limit capping with explicit [AudioErrorCode.A12_TIMELINE_OVERFLOW].
 * - Clean disk I/O error recovery and database preservation.
 * - Independent WAV read-back validation (RIFF, fmt, data, sample finiteness, peak <= 1.0f).
 * - Non-finite sample sanitization and headroom limiting.
 */
class OfflineExportStage11Test {

    private lateinit var dbFile: File
    private lateinit var db: SetFlowProjectDatabase
    private lateinit var diagnostics: DiagnosticsRingBuffer
    private lateinit var renderEngine: OfflineSetRenderEngine
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        dbFile = File.createTempFile("stage11_export_test_", ".sqlite").apply { deleteOnExit() }
        tempDir = java.nio.file.Files.createTempDirectory("stage11_export_dir_").toFile()
        diagnostics = DiagnosticsRingBuffer(capacity = 64)
        db = SetFlowProjectDatabase(
            driver = SetFlowSchemaAndMigrations.createDriver(dbFile),
            diagnostics = diagnostics
        )
        renderEngine = OfflineSetRenderEngine(
            database = db,
            diagnostics = diagnostics
        )
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        runCatching { dbFile.delete() }
        runCatching { tempDir.deleteRecursively() }
    }

    // --- Test Helpers ---

    private fun sampleTrack(
        trackId: String,
        title: String,
        durationUs: Long = 180_000_000L,
        audioKey: String = "ak_$trackId"
    ): TrackEntity = TrackEntity(
        trackId = trackId,
        displayName = "$title.flac",
        title = title,
        artist = "Test Artist",
        album = "Test Album",
        durationUs = durationUs,
        contentUri = "content://media/test/$trackId",
        providerAuthority = "app.setflow.test",
        mime = "audio/flac",
        codecDescription = "FLAC 16-bit 44.1kHz",
        sampleRateHz = 44100,
        channelCount = 2,
        bitsPerSample = 16,
        size = 12_000_000L,
        modifiedTime = System.currentTimeMillis(),
        contentFingerprint = "fp_$trackId",
        audioFingerprint = audioKey,
        lastVerified = System.currentTimeMillis(),
        availabilityState = TrackAvailabilityState.AVAILABLE.name
    )

    private fun saveResolvedPlan(
        database: SetFlowProjectDatabase,
        edgeId: String,
        plan: SeguePlan
    ) {
        database.saveEdgeOverrideAndResolvedPlan(
            edgeId = edgeId,
            expectedEdgePlanRevision = 1L,
            overrideSnapshot = OverrideSnapshotEntity(
                edgeId = edgeId,
                exitAnchorUs = plan.outgoing.anchorUs,
                entryAnchorUs = plan.incoming.anchorUs,
                spanUs = plan.span.lengthUs,
                editTimestampUtc = System.currentTimeMillis()
            ),
            canonicalSegueJson = SegueCodec.encode(plan)
        )
    }

    private fun buildPlan(
        keyA: String,
        keyB: String,
        exitUs: Long,
        entryUs: Long,
        spanUs: Long
    ): SeguePlan {
        val outFacts = SegueSideFacts(durationUs = exitUs + spanUs + 10_000_000L, audioKey = keyA, gridKey = null)
        val inFacts = SegueSideFacts(durationUs = entryUs + spanUs + 10_000_000L, audioKey = keyB, gridKey = null)
        val res = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(
                exitAnchorUs = exitUs,
                entryAnchorUs = entryUs,
                spanUs = spanUs,
                shape = SegueShape.EVEN_FADE
            ),
            outgoingFacts = outFacts,
            incomingFacts = inFacts
        )
        return (res as TransitionResolutionResult.Success).plan
    }

    // --- Tests ---

    /**
     * Test 1: Task 2 — Snapshot revision locking: user edits while export runs.
     * Export finishes rendering strictly from captured snapshot and reports revision change.
     */
    @Test
    fun test1_SnapshotRevisionLockingUserEditsDuringRender() {
        val t1 = sampleTrack("t1", "Track 1", durationUs = 5_000_000L)
        val t2 = sampleTrack("t2", "Track 2", durationUs = 5_000_000L)
        val t3 = sampleTrack("t3", "Track 3", durationUs = 5_000_000L)
        db.upsertTrack(t1)
        db.upsertTrack(t2)
        db.upsertTrack(t3)

        val project = db.createProject("Project Revision Test", projectId = "p1")
        db.addTracksToProject(project.projectId, project.editRevision, listOf("t1", "t2"))

        val detailBefore = db.getProjectDetail("p1")!!
        val plan = buildPlan("ak_t1", "ak_t2", exitUs = 4_000_000L, entryUs = 0L, spanUs = 1_000_000L)
        saveResolvedPlan(db, detailBefore.edges[0].edge.edgeId, plan)

        // Capture immutable snapshot BEFORE export
        val snapshot = db.getProjectDetail("p1")!!
        val initialRev = snapshot.project.editRevision

        val outFile = File(tempDir, "snapshot_test.wav")
        var editedDuringExport = false

        val result = renderEngine.renderProject(
            snapshot = snapshot,
            destinationFile = outFile,
            readerProvider = { _, _ ->
                ProceduralSyntheticTrackReader(durationSeconds = 5.0)
            },
            onProgress = { progress, _, _ ->
                if (progress >= 0.20 && !editedDuringExport) {
                    // Simulate user editing the project in database midway through export!
                    editedDuringExport = true
                    db.renameProject("p1", initialRev, "Renamed Mid-Export")
                    db.addTracksToProject("p1", initialRev + 1L, listOf("t3"))
                }
            }
        )

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals(initialRev, success.metadata.capturedProjectRevision)
        assertTrue("Expected snapshot to be reported outdated due to concurrent edit", success.isSnapshotOutdated)
        assertEquals(initialRev + 2L, success.currentProjectRevision)

        // Verify exported WAV header matches snapshot duration, not mutated DB
        val validation = WavReaderValidator.validate(outFile)
        assertTrue(validation is WavValidationResult.Valid)
        val valid = validation as WavValidationResult.Valid
        assertEquals(9_000_000L, valid.durationUs) // 5s + 5s - 1s overlap = 9s
    }

    /**
     * Test 2: Task 4 & Acceptance Gate — 3-track set export with exact frame-accurate duration.
     * Synthetic 155->160 BPM 16 bars verify exact output sample count and position.
     */
    @Test
    fun test2_ThreeTrackSetFrameAccurateDurationSynthetic155To160Bpm() {
        val t1 = sampleTrack("t1", "Track 155", durationUs = 30_000_000L)
        val t2 = sampleTrack("t2", "Track 160", durationUs = 30_000_000L)
        val t3 = sampleTrack("t3", "Track 158", durationUs = 30_000_000L)
        db.upsertTrack(t1)
        db.upsertTrack(t2)
        db.upsertTrack(t3)

        val project = db.createProject("3-Track Set", projectId = "p1")
        db.addTracksToProject(project.projectId, project.editRevision, listOf("t1", "t2", "t3"))

        val detailBefore = db.getProjectDetail("p1")!!
        val plan1 = buildPlan("ak_t1", "ak_t2", exitUs = 26_000_000L, entryUs = 0L, spanUs = 4_000_000L)
        val plan2 = buildPlan("ak_t2", "ak_t3", exitUs = 25_000_000L, entryUs = 1_000_000L, spanUs = 5_000_000L)

        saveResolvedPlan(db, detailBefore.edges[0].edge.edgeId, plan1)
        saveResolvedPlan(db, detailBefore.edges[1].edge.edgeId, plan2)

        val snapshot = db.getProjectDetail("p1")!!
        val timelineRes = SetTimelineResolver.resolve(snapshot) as SetTimelineResolution.Success
        val expectedDurationUs = timelineRes.timeline.totalDurationUs
        val expectedFrames = (expectedDurationUs * 44100L) / 1_000_000L

        val outFile = File(tempDir, "three_track_test.wav")
        val result = renderEngine.renderProject(
            snapshot = snapshot,
            destinationFile = outFile,
            readerProvider = { _, _ -> ProceduralSyntheticTrackReader(durationSeconds = 30.0) }
        )

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals(expectedFrames, success.summary.totalFrames)

        val validation = WavReaderValidator.validate(outFile, checkAllSamples = true)
        assertTrue(validation is WavValidationResult.Valid)
        val valid = validation as WavValidationResult.Valid
        assertEquals(expectedFrames, valid.totalFrames)
        assertEquals(44L + expectedFrames * 4L, outFile.length())
        assertTrue("Peak amplitude must be non-zero", valid.peakAmplitude > 0.05f)
        assertTrue("Peak amplitude must not clip 1.0f", valid.peakAmplitude <= 1.0f)
    }

    /**
     * Test 3: Task 5 & Acceptance Gate — DSP transition window parity with live playback engine.
     * Compares exported transition overlap against DualDeckPcmEngine output.
     */
    @Test
    fun test3_TransitionWindowParityWithLivePlaybackDsp() {
        val sampleRate = 44100
        val channels = 2
        val exitUs = 2_000_000L
        val entryUs = 1_000_000L
        val spanUs = 4_000_000L // 4 second transition

        val pcmA = SyntheticAudioFixtureGenerator.generateClickAndSubWav("trackA", 155.0, 10.0, sampleRate, channels)
        val pcmB = SyntheticAudioFixtureGenerator.generateClickAndSubWav("trackB", 160.0, 10.0, sampleRate, channels)

        val bufA = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(pcmA.wavBytes)
        val bufB = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(pcmB.wavBytes)

        val plan = buildPlan("ak_A", "ak_B", exitUs, entryUs, spanUs)

        // 1. Live playback transition render
        val binding = ArmedTransitionBinding(
            projectId = "p_dsp",
            projectRevision = 1L,
            edgeId = "e1",
            edgePlanRevision = 1L,
            outgoingOccurrenceId = "o1",
            incomingOccurrenceId = "o2",
            outgoingTrackId = "tA",
            incomingTrackId = "tB",
            audioKeyA = "ak_A",
            audioKeyB = "ak_B",
            exitAnchorUs = exitUs,
            entryAnchorUs = entryUs,
            spanUs = spanUs,
            ratePpmB = 1_000_000L,
            seguePlan = plan
        )

        val liveResult = DualDeckPcmEngine.renderTransitionWindow(
            binding = binding,
            sourceAProvider = { bufA },
            sourceBProvider = { bufB },
            preRollUs = 0L,
            postRollUs = 0L,
            useSegueDspIfAvailable = true
        )

        // 2. Offline export render of the identical two tracks
        val tA = sampleTrack("tA", "A", durationUs = 10_000_000L, audioKey = "ak_A")
        val tB = sampleTrack("tB", "B", durationUs = 10_000_000L, audioKey = "ak_B")
        db.upsertTrack(tA)
        db.upsertTrack(tB)

        val proj = db.createProject("DSP Parity", projectId = "p_dsp")
        db.addTracksToProject(proj.projectId, proj.editRevision, listOf("tA", "tB"))

        val detail = db.getProjectDetail("p_dsp")!!
        saveResolvedPlan(db, detail.edges[0].edge.edgeId, plan)

        val snapshot = db.getProjectDetail("p_dsp")!!
        val outFile = File(tempDir, "dsp_parity.wav")

        val exportResult = renderEngine.renderProject(
            snapshot = snapshot,
            destinationFile = outFile,
            readerProvider = { occId, _ ->
                if (occId == snapshot.occurrences[0].occurrence.occurrenceId) {
                    InMemoryPcmTrackReader(bufA)
                } else {
                    InMemoryPcmTrackReader(bufB)
                }
            }
        )

        assertTrue(exportResult is ExportResult.Success)

        // 3. Read back exported WAV and compare the overlap window
        val decodedExport = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(outFile.readBytes())
        val overlapStartFrame = ((exitUs * sampleRate) / 1_000_000L).toInt()
        val overlapFrames = ((spanUs * sampleRate) / 1_000_000L).toInt()

        var maxDiff = 0.0f
        var diffSumSq = 0.0

        for (i in 0 until overlapFrames) {
            val exportFrame = overlapStartFrame + i
            val liveFrame = i
            for (c in 0 until channels) {
                val sExport = decodedExport.interleavedSamples[exportFrame * channels + c]
                val sLive = liveResult.outputPcm.interleavedSamples[liveFrame * channels + c]
                val diff = abs(sExport - sLive)
                if (diff > maxDiff) maxDiff = diff
                diffSumSq += diff * diff
            }
        }

        val rmsDiff = kotlin.math.sqrt(diffSumSq / (overlapFrames * channels))
        // 16-bit PCM quantization error is ~1/32768 ≈ 0.00003f
        assertTrue("Max difference between offline export and live preview ($maxDiff) exceeds tolerance", maxDiff < 0.001f)
        assertTrue("RMS error ($rmsDiff) exceeds quantization floor", rmsDiff < 0.0001)
    }

    /**
     * Test 4: Task 9 — 20-track set export with unique transition plans and distinct boundaries.
     */
    @Test
    fun test4_TwentyTrackSetExportWithUniquePlans() {
        val trackCount = 20
        val pId = "p20"
        val project = db.createProject("20 Track Marathon", projectId = pId)

        val trackIds = (1..trackCount).map { i -> "track_$i" }

        for (i in 0 until trackCount) {
            val t = sampleTrack(trackIds[i], "Track $i", durationUs = 4_000_000L)
            db.upsertTrack(t)
        }

        db.addTracksToProject(project.projectId, project.editRevision, trackIds)
        val detailBeforePlans = db.getProjectDetail(pId)!!
        assertEquals(20, detailBeforePlans.occurrences.size)
        assertEquals(19, detailBeforePlans.edges.size)

        for (i in 0 until trackCount - 1) {
            val edge = detailBeforePlans.edges[i]
            val plan = buildPlan("ak_${trackIds[i]}", "ak_${trackIds[i + 1]}", exitUs = 3_000_000L, entryUs = 0L, spanUs = 1_000_000L)
            saveResolvedPlan(db, edge.edge.edgeId, plan)
        }

        val snapshot = db.getProjectDetail(pId)!!

        val outFile = File(tempDir, "twenty_track_export.wav")
        var progressUpdates = 0
        var maxProgressSeen = 0.0

        val result = renderEngine.renderProject(
            snapshot = snapshot,
            destinationFile = outFile,
            readerProvider = { _, _ -> ProceduralSyntheticTrackReader(durationSeconds = 4.0) },
            onProgress = { progress, _, _ ->
                progressUpdates++
                if (progress > maxProgressSeen) maxProgressSeen = progress
            }
        )

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertEquals(20, success.metadata.capturedOccurrenceIds.size)
        assertEquals(19, success.metadata.capturedPlanHashes.size)
        assertEquals(1.0, maxProgressSeen, 1e-6)
        assertTrue(progressUpdates >= 20)

        // Database record check
        val jobEntity = db.getExportJob(success.jobId)
        assertNotNull(jobEntity)
        assertEquals("COMPLETED", jobEntity!!.status)
        assertEquals(1.0, jobEntity.progress, 1e-6)

        // Read-back validation
        val validation = WavReaderValidator.validate(outFile)
        assertTrue(validation is WavValidationResult.Valid)
    }

    /**
     * Test 5: Task 1 & Acceptance Gate — 3-hour streaming generator with bounded constant memory.
     * Memory does not scale with audio length (streams in bounded blocks).
     */
    @Test
    fun test5_ThreeHourStreamingGeneratorConstantMemoryBoundedHeap() {
        val t1 = sampleTrack("t1", "Hour 1", durationUs = 3600_000_000L) // 1 hour
        val t2 = sampleTrack("t2", "Hour 2", durationUs = 3600_000_000L) // 1 hour
        val t3 = sampleTrack("t3", "Hour 3", durationUs = 3600_000_000L) // 1 hour
        db.upsertTrack(t1)
        db.upsertTrack(t2)
        db.upsertTrack(t3)

        val project = db.createProject("3-Hour Set", projectId = "p3h")
        db.addTracksToProject(project.projectId, project.editRevision, listOf("t1", "t2", "t3"))

        val detailBeforePlans = db.getProjectDetail("p3h")!!
        val plan1 = buildPlan("ak_t1", "ak_t2", exitUs = 3590_000_000L, entryUs = 0L, spanUs = 10_000_000L)
        val plan2 = buildPlan("ak_t2", "ak_t3", exitUs = 3590_000_000L, entryUs = 0L, spanUs = 10_000_000L)
        saveResolvedPlan(db, detailBeforePlans.edges[0].edge.edgeId, plan1)
        saveResolvedPlan(db, detailBeforePlans.edges[1].edge.edgeId, plan2)

        val snapshot = db.getProjectDetail("p3h")!!
        val timelineRes = SetTimelineResolver.resolve(snapshot) as SetTimelineResolution.Success
        val expectedDurationUs = timelineRes.timeline.totalDurationUs // ~2.99 hours = 10780s
        val expectedFrames = (expectedDurationUs * 44100L) / 1_000_000L

        // To test O(1) memory during a 3-hour streaming generator without waiting 30 minutes of disk I/O,
        // we stream 1,024,000 frames (~23 seconds of audio at 4096 blocks) and verify heap stability.
        val runtime = Runtime.getRuntime()
        System.gc()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()

        val outFile = File(tempDir, "stream_memory_test.wav")
        var blocksProcessed = 0
        var maxObservedHeap = 0L

        val result = renderEngine.renderProject(
            snapshot = snapshot,
            destinationFile = outFile,
            readerProvider = { _, _ -> ProceduralSyntheticTrackReader(durationSeconds = 3600.0) },
            isCancelled = {
                blocksProcessed++
                val heap = runtime.totalMemory() - runtime.freeMemory()
                if (heap > maxObservedHeap) maxObservedHeap = heap
                // Stop after 250 blocks (1,024,000 frames) to measure bounded heap growth
                blocksProcessed >= 250
            }
        )

        assertTrue(result is ExportResult.Cancelled)
        val memGrowth = (maxObservedHeap - memBefore).coerceAtLeast(0L)
        // Bounded memory guarantee: heap growth during streaming must be < 25 MB!
        assertTrue("Memory growth during 3-hour streaming generator was $memGrowth bytes (must be < 25MB)", memGrowth < 25 * 1024 * 1024)
    }

    /**
     * Test 6: Task 6 & Acceptance Gate — Safe cancellation at 1%, 50%, and 99%.
     * Partial output file deleted, state CANCELLED, failureCode A11_CANCELLED, DB intact.
     */
    @Test
    fun test6_CancellationSafetyAt1Percent50Percent99Percent() {
        val t1 = sampleTrack("t1", "Track 1", durationUs = 10_000_000L)
        val t2 = sampleTrack("t2", "Track 2", durationUs = 10_000_000L)
        db.upsertTrack(t1)
        db.upsertTrack(t2)

        val project = db.createProject("Cancel Test", projectId = "p_cancel")
        db.addTracksToProject(project.projectId, project.editRevision, listOf("t1", "t2"))

        val detailBefore = db.getProjectDetail("p_cancel")!!
        val plan = buildPlan("ak_t1", "ak_t2", exitUs = 8_000_000L, entryUs = 0L, spanUs = 2_000_000L)
        saveResolvedPlan(db, detailBefore.edges[0].edge.edgeId, plan)

        val snapshot = db.getProjectDetail("p_cancel")!!
        val cancelTargets = listOf(0.01, 0.50, 0.99)

        for (targetProgress in cancelTargets) {
            val outFile = File(tempDir, "cancel_${(targetProgress * 100).toInt()}.wav")
            val jobId = "job_cancel_${(targetProgress * 100).toInt()}"
            var cancelTriggered = false

            val result = renderEngine.renderProject(
                snapshot = snapshot,
                destinationFile = outFile,
                jobId = jobId,
                readerProvider = { _, _ -> ProceduralSyntheticTrackReader(durationSeconds = 10.0) },
                onProgress = { progress, _, _ ->
                    if (progress >= targetProgress) {
                        cancelTriggered = true
                    }
                },
                isCancelled = {
                    cancelTriggered
                }
            )

            assertTrue("Result must be Cancelled at $targetProgress", result is ExportResult.Cancelled)
            val cancelled = result as ExportResult.Cancelled
            assertTrue("Progress on cancel must be < 1.0", cancelled.progress < 1.0)

            // Crucial: partial file must be cleanly deleted!
            assertFalse("Partial WAV file must be deleted on cancellation", outFile.exists())

            // DB status check
            val dbJob = db.getExportJob(jobId)
            assertNotNull(dbJob)
            assertEquals("CANCELLED", dbJob!!.status)
            assertEquals(AudioErrorCode.A11_CANCELLED.name, dbJob.failureCode)
            assertTrue("Job progress in DB must match cancelled progress", dbJob.progress < 1.0)

            // Verify project database was not corrupted or modified
            val projectAfter = db.getProjectDetail("p_cancel")
            assertNotNull(projectAfter)
            assertEquals(2, projectAfter!!.occurrences.size)
            assertEquals(1, projectAfter.edges.size)
        }
    }

    /**
     * Test 7: Task 3 & Bug Trap 40 — RIFF 4GiB limit capping with explicit A12_TIMELINE_OVERFLOW.
     */
    @Test
    fun test7_FourGibRiffLimitRejectionA12TimelineOverflow() {
        // 7 hours of 44.1kHz 16-bit stereo PCM = 7 * 3600 * 44100 * 4 bytes ≈ 4,445,280,000 bytes (> 4GiB)
        val sevenHoursUs = 7L * 3600L * 1_000_000L
        val t1 = sampleTrack("t1", "Mega Track", durationUs = sevenHoursUs)
        db.upsertTrack(t1)

        val project = db.createProject("Mega Set", projectId = "p_huge")
        db.addTracksToProject(project.projectId, project.editRevision, listOf("t1"))

        val snapshot = db.getProjectDetail("p_huge")!!
        val outFile = File(tempDir, "overflow.wav")
        val jobId = "job_overflow"

        val result = renderEngine.renderProject(
            snapshot = snapshot,
            destinationFile = outFile,
            jobId = jobId,
            readerProvider = { _, _ -> ProceduralSyntheticTrackReader(durationSeconds = 7.0 * 3600.0) }
        )

        assertTrue("Result must be Error", result is ExportResult.Error)
        val err = result as ExportResult.Error
        assertEquals(AudioErrorCode.A12_TIMELINE_OVERFLOW, err.errorCode)
        assertTrue(err.reason.contains("4GiB RIFF limit"))

        assertFalse("Output file must not exist on overflow rejection", outFile.exists())

        val job = db.getExportJob(jobId)
        assertNotNull(job)
        assertEquals("FAILED", job!!.status)
        assertEquals(AudioErrorCode.A12_TIMELINE_OVERFLOW.name, job.failureCode)
    }

    /**
     * Test 8: Task 7 — Output disk I/O failure handling and database preservation.
     */
    @Test
    fun test8_OutputDiskIoFailureHandlingAndDatabasePreservation() {
        val t1 = sampleTrack("t1", "Track 1", durationUs = 2_000_000L)
        db.upsertTrack(t1)

        val project = db.createProject("IO Error Test", projectId = "p_io")
        db.addTracksToProject(project.projectId, project.editRevision, listOf("t1"))

        val snapshot = db.getProjectDetail("p_io")!!
        // Create an invalid destination file path (points inside a non-directory file)
        val blockerFile = File(tempDir, "blocker.txt").apply { writeText("blocking file") }
        val invalidOutFile = File(blockerFile, "impossible_output.wav")
        val jobId = "job_io_fail"

        val result = renderEngine.renderProject(
            snapshot = snapshot,
            destinationFile = invalidOutFile,
            jobId = jobId,
            readerProvider = { _, _ -> ProceduralSyntheticTrackReader(durationSeconds = 2.0) }
        )

        assertTrue(result is ExportResult.Error)
        val err = result as ExportResult.Error
        assertEquals(AudioErrorCode.A10_EXPORT_IO_FAILURE, err.errorCode)

        val job = db.getExportJob(jobId)
        assertNotNull(job)
        assertEquals("FAILED", job!!.status)
        assertEquals(AudioErrorCode.A10_EXPORT_IO_FAILURE.name, job.failureCode)

        // Database remains uncorrupted
        val projectAfter = db.getProjectDetail("p_io")
        assertNotNull(projectAfter)
        assertEquals(1, projectAfter!!.occurrences.size)
    }

    /**
     * Test 9: Task 8 & Bug Trap 40 — Independent WAV reader validator integrity check.
     */
    @Test
    fun test9_IndependentWavReaderValidatorIntegrity() {
        // 1. Valid WAV
        val validWav = File(tempDir, "valid.wav")
        WavWriter(validWav, 44100, 2, 16).use { writer ->
            writer.writeFrames(FloatArray(44100 * 2) { 0.25f }, 44100)
        }
        val v1 = WavReaderValidator.validate(validWav, checkAllSamples = true)
        assertTrue(v1 is WavValidationResult.Valid)
        val res1 = v1 as WavValidationResult.Valid
        assertEquals(44100, res1.sampleRate)
        assertEquals(2, res1.channels)
        assertEquals(16, res1.bitsPerSample)
        assertEquals(44100L, res1.totalFrames)
        assertEquals(1_000_000L, res1.durationUs)

        // 2. Corrupt RIFF magic
        val corruptMagicWav = File(tempDir, "corrupt_magic.wav").apply {
            writeBytes(validWav.readBytes().also { it[0] = 'B'.code.toByte(); it[1] = 'A'.code.toByte(); it[2] = 'D'.code.toByte(); it[3] = '!'.code.toByte() })
        }
        val v2 = WavReaderValidator.validate(corruptMagicWav)
        assertTrue(v2 is WavValidationResult.Invalid)
        assertEquals(AudioErrorCode.A03_UNSUPPORTED_CODEC, (v2 as WavValidationResult.Invalid).errorCode)

        // 3. Truncated header (< 44 bytes)
        val truncatedWav = File(tempDir, "truncated.wav").apply { writeBytes(ByteArray(20)) }
        val v3 = WavReaderValidator.validate(truncatedWav)
        assertTrue(v3 is WavValidationResult.Invalid)
        assertEquals(AudioErrorCode.A03_UNSUPPORTED_CODEC, (v3 as WavValidationResult.Invalid).errorCode)

        // 4. Mismatched file size (corrupted chunk length)
        val sizeMismatchWav = File(tempDir, "mismatched.wav").apply {
            val bytes = validWav.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(4, 999_999) // corrupt RIFF length
            writeBytes(bytes)
        }
        val v4 = WavReaderValidator.validate(sizeMismatchWav)
        assertTrue(v4 is WavValidationResult.Invalid)
        assertEquals(AudioErrorCode.A03_UNSUPPORTED_CODEC, (v4 as WavValidationResult.Invalid).errorCode)
    }

    /**
     * Test 10: Task 1, 3 & Acceptance Gate — Headroom limiter and non-finite sample protection.
     * Overlapping kicks with amplitude > 1.8f and NaN/Inf are clamped and soft-limited.
     */
    @Test
    fun test10_HeadroomLimiterAndNonFiniteSampleProtection() {
        val sampleRate = 44100
        val frames = 1000
        val dirtySamples = FloatArray(frames * 2) { i ->
            when (i) {
                10 -> Float.NaN
                20 -> Float.POSITIVE_INFINITY
                30 -> Float.NEGATIVE_INFINITY
                else -> 1.8f // Hot loud kick summing over ±1.0f
            }
        }

        val dirtyPcm = DecodedPcmBuffer(sampleRate, 2, frames, dirtySamples)
        val outFile = File(tempDir, "sanitized.wav")

        WavWriter(outFile, sampleRate, 2, 16).use { writer ->
            writer.writeFrames(dirtyPcm.interleavedSamples, frames)
            assertEquals(3L, writer.nonFiniteSamplesCount)
            assertTrue("Peak must not exceed 1.0f in writer", writer.peakAmplitude <= 1.0f)
        }

        val validation = WavReaderValidator.validate(outFile, checkAllSamples = true)
        assertTrue(validation is WavValidationResult.Valid)
        val valid = validation as WavValidationResult.Valid
        assertTrue("Output peak amplitude must be <= 1.0f", valid.peakAmplitude <= 1.0f)
    }
}
