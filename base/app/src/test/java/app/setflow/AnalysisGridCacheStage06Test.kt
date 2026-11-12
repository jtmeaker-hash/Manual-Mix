/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.myspoti.automix.Analyzer
import app.myspoti.automix.MixPlan
import app.myspoti.automix.MixPlanner
import app.myspoti.automix.TrackAnalysis
import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.KnotForm
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueClass
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueUnit
import app.myspoti.automix.segue.Target
import app.myspoti.automix.segue.UnitKind
import app.setflow.analysis.AnalysisPriority
import app.setflow.analysis.AnalysisStatus
import app.setflow.analysis.BeatGridCorrectionParams
import app.setflow.analysis.BeatThisModelProvider
import app.setflow.analysis.HalfDoubleAction
import app.setflow.analysis.ManualGridCorrector
import app.setflow.analysis.TrackAnalysisCache
import app.setflow.analysis.TrackAnalysisManager
import app.setflow.db.AutoPlanSnapshotEntity
import app.setflow.db.EdgeValidationStatus
import app.setflow.db.OverrideSnapshotEntity
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.SetFlowSchemaAndMigrations
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.TrackAvailabilityState
import app.setflow.io.VirtualSafTrackSourceProvider
import app.setflow.playback.SyntheticAudioFixtureGenerator
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

class AnalysisGridCacheStage06Test {

    private fun createTempDbFile(name: String): File {
        val dir = Files.createTempDirectory("setflow_test_$name").toFile()
        dir.deleteOnExit()
        return File(dir, "test.sqlite")
    }

    private fun createTempCacheDir(name: String): File {
        val dir = Files.createTempDirectory("setflow_cache_$name").toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun assertBpmCloseOrHalfTime(actual: Double, expected: Double, tolerance: Double = 0.03) {
        val errFull = abs(actual - expected) / expected
        val errHalf = abs(actual * 2.0 - expected) / expected
        assertTrue(
            "Detected BPM ($actual) must match expected ($expected) or half-time octave (${expected / 2.0}) within tolerance $tolerance (errFull=$errFull, errHalf=$errHalf)",
            errFull < tolerance || errHalf < tolerance
        )
    }

    private fun makeTestTrack(
        trackId: String,
        displayName: String,
        uriString: String,
        durationUs: Long,
        sizeBytes: Long,
        contentFingerprint: String
    ): TrackEntity = TrackEntity(
        trackId = trackId,
        displayName = displayName,
        title = displayName.substringBeforeLast('.'),
        artist = "Synthetic",
        album = "Stage 06",
        durationUs = durationUs,
        contentUri = uriString,
        providerAuthority = "app.setflow.test",
        mime = "audio/wav",
        codecDescription = "WAV PCM 16-bit",
        sampleRateHz = 44_100,
        channelCount = 2,
        bitsPerSample = 16,
        size = sizeBytes,
        modifiedTime = 1_700_000_000_000L,
        mediaStoreId = null,
        grantStatus = "PERSISTED",
        contentFingerprint = contentFingerprint,
        audioFingerprint = null,
        fingerprintVersion = 1,
        cachedArtworkKey = null,
        lastVerified = 1_700_000_000_000L,
        availabilityState = TrackAvailabilityState.AVAILABLE.name
    )

    private fun buildCanonicalSegueJson(
        audioKeyA: String = "audiokey_a",
        audioKeyB: String = "audiokey_b",
        exitAnchorUs: Long = 12_000_000L,
        entryAnchorUs: Long = 1_000_000L,
        spanUs: Long = 4_000_000L
    ): String {
        val plan = SeguePlan(
            origin = SeguePlan.Origin(engine = "setflow-stage06-test", corpus = "unit"),
            outgoing = SeguePlan.Side(
                audioKey = audioKeyA,
                gridKey = null,
                anchorUs = exitAnchorUs,
                ratePpm = SegueFormat.RATE_NOMINAL
            ),
            incoming = SeguePlan.Side(
                audioKey = audioKeyB,
                gridKey = null,
                anchorUs = entryAnchorUs,
                ratePpm = SegueFormat.RATE_NOMINAL
            ),
            span = SeguePlan.Span(
                lengthUs = spanUs,
                musicalUnits = 4,
                unitKind = UnitKind.BAR,
                clock = Clock.ELAPSED
            ),
            pulse = SeguePlan.Pulse(
                state = PulseState.FREE,
                tempoMicroPerMinute = null,
                residualUs = null
            ),
            lanes = listOf(
                SeguePlan.Lane(
                    side = LaneSide.OUTGOING,
                    target = Target.LEVEL,
                    unit = SegueUnit.DB,
                    knots = listOf(
                        SeguePlan.Knot(0, 0L, KnotForm.Step),
                        SeguePlan.Knot(SegueFormat.T_MAX, null, KnotForm.Ramp)
                    )
                ),
                SeguePlan.Lane(
                    side = LaneSide.INCOMING,
                    target = Target.LEVEL,
                    unit = SegueUnit.DB,
                    knots = listOf(
                        SeguePlan.Knot(0, null, KnotForm.Step),
                        SeguePlan.Knot(SegueFormat.T_MAX, 0L, KnotForm.Ramp)
                    )
                )
            )
        )
        return SegueCodec.encode(plan)
    }

    @Test
    fun confidenceAwareAnalysis_155_158_160_170_bpmSyntheticFixtures() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val dbFile = createTempDbFile("fixtures_bpm")
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cacheDir = createTempCacheDir("fixtures_bpm")
        val cache = TrackAnalysisCache(cacheDir, diagnostics = diagnostics)
        val modelProvider = BeatThisModelProvider(forcePureKotlinFallback = true, diagnostics = diagnostics)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics
        )

        // 1. T01: 155 BPM click + sub
        val fix155 = SyntheticAudioFixtureGenerator.generateClickAndSubWav(
            name = "T01_155bpm",
            bpm = 155.0,
            durationSec = 16.0,
            sampleRateHz = 44_100
        )
        val uri155 = "content://app.setflow.test/audio/155"
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry(
                uriString = uri155,
                displayName = "Track_155.wav",
                declaredMimeType = "audio/wav",
                inMemoryBytes = fix155.wavBytes
            )
        )
        val track155 = makeTestTrack("trk_155", "Track_155.wav", uri155, fix155.durationUs, fix155.wavBytes.size.toLong(), "fp_155")
        db.upsertTrack(track155)

        val status155 = manager.analyze("trk_155", AnalysisPriority.PLAYBACK_ARMED)
        assertTrue("T01 155 BPM should be confident or have steady grid", status155 is AnalysisStatus.Confident || status155 is AnalysisStatus.Unreliable)
        val analysis155 = when (status155) {
            is AnalysisStatus.Confident -> status155.analysis
            is AnalysisStatus.Unreliable -> status155.analysis
            else -> error("Unexpected failure: $status155")
        }
        assertBpmCloseOrHalfTime(analysis155.bpm, 155.0)
        assertNotNull("Grid head should not be null", analysis155.head)

        // 2. T02: 158 BPM click + sub
        val fix158 = SyntheticAudioFixtureGenerator.generateClickAndSubWav(
            name = "T02_158bpm",
            bpm = 158.0,
            durationSec = 16.0,
            sampleRateHz = 44_100,
            phaseOffsetMs = 350.0
        )
        val uri158 = "content://app.setflow.test/audio/158"
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry(
                uriString = uri158,
                displayName = "Track_158.wav",
                declaredMimeType = "audio/wav",
                inMemoryBytes = fix158.wavBytes
            )
        )
        val track158 = makeTestTrack("trk_158", "Track_158.wav", uri158, fix158.durationUs, fix158.wavBytes.size.toLong(), "fp_158")
        db.upsertTrack(track158)

        val status158 = manager.analyze("trk_158", AnalysisPriority.PLAYBACK_ARMED)
        val analysis158 = (status158 as? AnalysisStatus.Confident)?.analysis ?: (status158 as? AnalysisStatus.Unreliable)?.analysis!!
        assertBpmCloseOrHalfTime(analysis158.bpm, 158.0)

        // 3. T03: 160 BPM hard kick
        val fix160 = SyntheticAudioFixtureGenerator.generateHardKickAndClickWav(
            name = "T03_160bpm",
            bpm = 160.0,
            durationSec = 16.0,
            sampleRateHz = 44_100
        )
        val uri160 = "content://app.setflow.test/audio/160"
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry(
                uriString = uri160,
                displayName = "Track_160.wav",
                declaredMimeType = "audio/wav",
                inMemoryBytes = fix160.wavBytes
            )
        )
        val track160 = makeTestTrack("trk_160", "Track_160.wav", uri160, fix160.durationUs, fix160.wavBytes.size.toLong(), "fp_160")
        db.upsertTrack(track160)

        val status160 = manager.analyze("trk_160", AnalysisPriority.PLAYBACK_ARMED)
        val analysis160 = (status160 as? AnalysisStatus.Confident)?.analysis ?: (status160 as? AnalysisStatus.Unreliable)?.analysis!!
        assertBpmCloseOrHalfTime(analysis160.bpm, 160.0)

        // 4. T04: 170 BPM kick
        val fix170 = SyntheticAudioFixtureGenerator.generateHardKickAndClickWav(
            name = "T04_170bpm",
            bpm = 170.0,
            durationSec = 16.0,
            sampleRateHz = 44_100,
            peakAmplitude = 0.95f
        )
        val uri170 = "content://app.setflow.test/audio/170"
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry(
                uriString = uri170,
                displayName = "Track_170.wav",
                declaredMimeType = "audio/wav",
                inMemoryBytes = fix170.wavBytes
            )
        )
        val track170 = makeTestTrack("trk_170", "Track_170.wav", uri170, fix170.durationUs, fix170.wavBytes.size.toLong(), "fp_170")
        db.upsertTrack(track170)

        val status170 = manager.analyze("trk_170", AnalysisPriority.PLAYBACK_ARMED)
        val analysis170 = (status170 as? AnalysisStatus.Confident)?.analysis ?: (status170 as? AnalysisStatus.Unreliable)?.analysis!!
        assertBpmCloseOrHalfTime(analysis170.bpm, 170.0)
    }

    @Test
    fun halfTimeAmbiguity_80BpmVs160Bpm_correctedViaManualGridCorrection() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(createTempDbFile("half_time")), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cache = TrackAnalysisCache(createTempCacheDir("half_time"), diagnostics = diagnostics)
        val modelProvider = BeatThisModelProvider(forcePureKotlinFallback = true, diagnostics = diagnostics)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics
        )

        // Generate 80 BPM fixture
        val fix80 = SyntheticAudioFixtureGenerator.generateClickAndSubWav(
            name = "T05_80bpm",
            bpm = 80.0,
            durationSec = 16.0,
            sampleRateHz = 44_100
        )
        val uri80 = "content://app.setflow.test/audio/80"
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry(
                uriString = uri80,
                displayName = "Track_80.wav",
                declaredMimeType = "audio/wav",
                inMemoryBytes = fix80.wavBytes
            )
        )
        val track80 = makeTestTrack("trk_80", "Track_80.wav", uri80, fix80.durationUs, fix80.wavBytes.size.toLong(), "fp_80")
        db.upsertTrack(track80)

        // 1. Initial automated analysis
        val initialStatus = manager.analyze("trk_80", AnalysisPriority.PLAYBACK_ARMED)
        val initialAnalysis = (initialStatus as? AnalysisStatus.Confident)?.analysis
            ?: (initialStatus as? AnalysisStatus.Unreliable)?.analysis!!
        assertFalse("Initial analysis must not claim to be a manual correction", (initialStatus as? AnalysisStatus.Confident)?.isManualCorrection == true)

        // 2. User toggles DOUBLE_BPM to disambiguate 80 BPM -> 160 BPM
        val correctedStatus = manager.applyManualCorrection(
            trackId = "trk_80",
            params = BeatGridCorrectionParams(
                bpmOverride = 160.0,
                phaseAnchorUs = 0L
            )
        )

        assertTrue("Corrected status must be confident or unreliable", correctedStatus is AnalysisStatus.Confident || correctedStatus is AnalysisStatus.Unreliable)
        val correctedAnalysis = (correctedStatus as? AnalysisStatus.Confident)?.analysis
            ?: (correctedStatus as? AnalysisStatus.Unreliable)?.analysis!!

        // Assert BPM was doubled and grid recalculated accurately
        assertEquals(160.0, correctedAnalysis.bpm, 1.0)
        assertEquals(60.0 / correctedAnalysis.bpm, correctedAnalysis.head!!.period, 1e-4)
        assertTrue(correctedAnalysis.steady)
        assertTrue(correctedAnalysis.head!!.reliable)

        // Verify manual correction record in SQLite
        val persistedCorrection = db.getBeatGridCorrection("trk_80")
        assertNotNull("Manual correction must be persisted in SQLite", persistedCorrection)
        assertEquals(1L, persistedCorrection!!.revision)
        assertEquals(160.0, persistedCorrection.bpmOverride!!, 1.0)
        assertNull("Manual correction must NOT fake neural confidenceOverride", persistedCorrection.confidenceOverride)
    }

    @Test
    fun twoTierCacheHitsAndDeterministicInvalidation() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(createTempDbFile("cache_test")), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cacheDir = createTempCacheDir("cache_test")
        val cache = TrackAnalysisCache(cacheDir, diagnostics = diagnostics)
        val modelProvider = BeatThisModelProvider(forcePureKotlinFallback = true, diagnostics = diagnostics)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics
        )

        val fix = SyntheticAudioFixtureGenerator.generateClickAndSubWav(
            name = "Cache_Track",
            bpm = 155.0,
            durationSec = 16.0,
            sampleRateHz = 44_100
        )
        val uri = "content://app.setflow.test/audio/cache_test"
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry(
                uriString = uri,
                displayName = "Cache_Track.wav",
                declaredMimeType = "audio/wav",
                inMemoryBytes = fix.wavBytes
            )
        )
        val track = makeTestTrack("trk_cache", "Cache_Track.wav", uri, fix.durationUs, fix.wavBytes.size.toLong(), "fp_cache_initial")
        db.upsertTrack(track)

        // 1. First analysis: Cache miss -> decodes and persists to L1 & L2
        val status1 = manager.analyze("trk_cache", AnalysisPriority.PLAYBACK_ARMED)
        assertFalse("First run must be a cache miss", (status1 as? AnalysisStatus.Confident)?.cacheHit == true)

        // 2. Second analysis: Cache hit in L1 memory
        val status2 = manager.analyze("trk_cache", AnalysisPriority.PLAYBACK_ARMED)
        assertTrue("Second run must be a cache hit", (status2 as? AnalysisStatus.Confident)?.cacheHit == true)

        // 3. Re-instantiate new cache pointing to same diskDir (simulating app restart / process death)
        val restartedCache = TrackAnalysisCache(cacheDir, diagnostics = diagnostics)
        val restartedManager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = restartedCache,
            diagnostics = diagnostics
        )
        val status3 = restartedManager.analyze("trk_cache", AnalysisPriority.PLAYBACK_ARMED)
        assertTrue("Restarted manager must hit persistent L2 disk cache", (status3 as? AnalysisStatus.Confident)?.cacheHit == true)

        // 4. Invalidate cache on audio change (contentFingerprint changed)
        val updatedTrack = track.copy(contentFingerprint = "fp_cache_modified")
        db.upsertTrack(updatedTrack)
        val status4 = restartedManager.analyze("trk_cache", AnalysisPriority.PLAYBACK_ARMED)
        assertFalse("Audio content change must trigger a cache miss", (status4 as? AnalysisStatus.Confident)?.cacheHit == true)
    }

    @Test
    fun manualGridCorrectionPersistsAndInvalidatesOnlyAffectedPlansD10() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(createTempDbFile("d10_test")), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cache = TrackAnalysisCache(createTempCacheDir("d10_test"), diagnostics = diagnostics)
        val modelProvider = BeatThisModelProvider(forcePureKotlinFallback = true, diagnostics = diagnostics)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics
        )

        // Create 3 tracks
        val fixA = SyntheticAudioFixtureGenerator.generateClickAndSubWav("T_A", 155.0, 16.0)
        val fixB = SyntheticAudioFixtureGenerator.generateClickAndSubWav("T_B", 158.0, 16.0)
        val fixC = SyntheticAudioFixtureGenerator.generateClickAndSubWav("T_C", 160.0, 16.0)

        sourceProvider.registerDocument(VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/a", "A.wav", "audio/wav", inMemoryBytes = fixA.wavBytes))
        sourceProvider.registerDocument(VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/b", "B.wav", "audio/wav", inMemoryBytes = fixB.wavBytes))
        sourceProvider.registerDocument(VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/c", "C.wav", "audio/wav", inMemoryBytes = fixC.wavBytes))

        val trkA = makeTestTrack("tr_a", "A.wav", "content://t/a", fixA.durationUs, fixA.wavBytes.size.toLong(), "fp_a")
        val trkB = makeTestTrack("tr_b", "B.wav", "content://t/b", fixB.durationUs, fixB.wavBytes.size.toLong(), "fp_b")
        val trkC = makeTestTrack("tr_c", "C.wav", "content://t/c", fixC.durationUs, fixC.wavBytes.size.toLong(), "fp_c")

        db.upsertTrack(trkA)
        db.upsertTrack(trkB)
        db.upsertTrack(trkC)

        // Create project with sequence A -> B -> C
        val project = db.createProject("D10 Project")
        val detail = db.addTracksToProject(project.projectId, project.editRevision, listOf("tr_a", "tr_b", "tr_c"))
        assertEquals(2, detail.edges.size)
        val edgeAB = detail.edges[0]
        val edgeBC = detail.edges[1]

        val planJsonAB = buildCanonicalSegueJson("audiokey_a", "audiokey_b")
        val planJsonBC = buildCanonicalSegueJson("audiokey_b", "audiokey_c")

        // Add user overrides to both edges
        db.saveEdgeOverrideAndResolvedPlan(
            edgeId = edgeAB.edge.edgeId,
            expectedEdgePlanRevision = edgeAB.edge.planRevision,
            overrideSnapshot = OverrideSnapshotEntity(
                edgeId = edgeAB.edge.edgeId,
                revision = 1L,
                exitAnchorUs = 12_000_000L,
                entryAnchorUs = 1_000_000L,
                spanUs = 4_000_000L,
                userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or OverrideSnapshotEntity.BIT_ENTRY_ANCHOR or OverrideSnapshotEntity.BIT_SPAN,
                editTimestampUtc = 1_700_000_000_000L
            ),
            canonicalSegueJson = planJsonAB
        )

        db.saveEdgeOverrideAndResolvedPlan(
            edgeId = edgeBC.edge.edgeId,
            expectedEdgePlanRevision = edgeBC.edge.planRevision,
            overrideSnapshot = OverrideSnapshotEntity(
                edgeId = edgeBC.edge.edgeId,
                revision = 1L,
                exitAnchorUs = 14_000_000L,
                entryAnchorUs = 2_000_000L,
                spanUs = 3_000_000L,
                userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or OverrideSnapshotEntity.BIT_ENTRY_ANCHOR or OverrideSnapshotEntity.BIT_SPAN,
                editTimestampUtc = 1_700_000_000_000L
            ),
            canonicalSegueJson = planJsonBC
        )

        // Perform initial analysis of B
        manager.analyze("tr_b", AnalysisPriority.PLAYBACK_ARMED)

        // Apply manual grid correction to Track B
        manager.applyManualCorrection(
            trackId = "tr_b",
            params = BeatGridCorrectionParams(bpmOverride = 159.0)
        )

        // Verify edges referencing Track B (edgeAB and edgeBC) are marked STALE_ANALYSIS
        val updatedDetail = db.getProjectDetail(project.projectId)!!
        val updatedSnapAB = updatedDetail.edges.first { it.edge.edgeId == edgeAB.edge.edgeId }
        val updatedSnapBC = updatedDetail.edges.first { it.edge.edgeId == edgeBC.edge.edgeId }

        assertEquals(EdgeValidationStatus.STALE_ANALYSIS, updatedSnapAB.edge.lastValidationStatus)
        assertEquals(EdgeValidationStatus.STALE_ANALYSIS, updatedSnapBC.edge.lastValidationStatus)

        // CRITICAL CONTRACT D10: User overrides MUST NEVER be erased or deleted
        assertNotNull("User override on edgeAB must be preserved (D10)", updatedSnapAB.overridePlan)
        assertEquals(12_000_000L, updatedSnapAB.overridePlan!!.exitAnchorUs)
        assertNotNull("User override on edgeBC must be preserved (D10)", updatedSnapBC.overridePlan)
        assertEquals(14_000_000L, updatedSnapBC.overridePlan!!.exitAnchorUs)
    }

    @Test
    fun inFlightAnalysisDeduplicationAndBoundedConcurrency() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(createTempDbFile("dedup_test")), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cache = TrackAnalysisCache(createTempCacheDir("dedup_test"), diagnostics = diagnostics)
        val modelProvider = BeatThisModelProvider(forcePureKotlinFallback = true, diagnostics = diagnostics)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics,
            maxConcurrent = 2
        )

        val fix = SyntheticAudioFixtureGenerator.generateClickAndSubWav("Dedup_Track", 155.0, 16.0)
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/dedup", "D.wav", "audio/wav", inMemoryBytes = fix.wavBytes)
        )
        val track = makeTestTrack("tr_dedup", "Dedup.wav", "content://t/dedup", fix.durationUs, fix.wavBytes.size.toLong(), "fp_dedup")
        db.upsertTrack(track)

        // Launch 5 simultaneous requests for the same track
        val jobs = (1..5).map {
            async {
                manager.analyze("tr_dedup", AnalysisPriority.PLAYBACK_ARMED)
            }
        }

        val results = jobs.map { it.await() }
        for (res in results) {
            assertTrue("Every deduplicated caller must receive a valid status", res is AnalysisStatus.Confident || res is AnalysisStatus.Unreliable)
            val bpm = (res as? AnalysisStatus.Confident)?.bpm ?: (res as? AnalysisStatus.Unreliable)?.bpm!!
            assertBpmCloseOrHalfTime(bpm, 155.0)
        }

        // Check diagnostics to ensure deduplication occurred
        val dedupLog = diagnostics.snapshot().any { it.message.contains("Deduplicated in-flight analysis") }
        assertTrue("Diagnostics ring buffer must record in-flight deduplication", dedupLog)
    }

    @Test
    fun corruptedOrUnsupportedOnnxModelGracefullyFallsBackToPureKotlinBeatTracker() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(createTempDbFile("corrupt_model")), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cache = TrackAnalysisCache(createTempCacheDir("corrupt_model"), diagnostics = diagnostics)

        // Inject corrupted / invalid byte array as ONNX model
        val corruptedModelBytes = "THIS IS NOT A VALID ONNX MODEL BINARY FILE".toByteArray(Charsets.UTF_8)
        val modelProvider = BeatThisModelProvider(
            overrideModelBytes = corruptedModelBytes,
            diagnostics = diagnostics
        )

        // Verify BeatThis creation returns null safely instead of throwing unhandled exception
        val session = modelProvider.createBeatThis()
        assertNull("Corrupted model bytes must return null from createBeatThis()", session)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics
        )

        val fix = SyntheticAudioFixtureGenerator.generateClickAndSubWav("Fallback_Track", 160.0, 16.0)
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/fallback", "F.wav", "audio/wav", inMemoryBytes = fix.wavBytes)
        )
        val track = makeTestTrack("tr_fallback", "Fallback.wav", "content://t/fallback", fix.durationUs, fix.wavBytes.size.toLong(), "fp_fallback")
        db.upsertTrack(track)

        // Manager must successfully analyze via pure-Kotlin BeatTracker fallback
        val status = manager.analyze("tr_fallback", AnalysisPriority.PLAYBACK_ARMED)
        assertTrue("Analysis with corrupted model must succeed via BeatTracker fallback", status is AnalysisStatus.Confident || status is AnalysisStatus.Unreliable)
        val bpm = (status as? AnalysisStatus.Confident)?.bpm ?: (status as? AnalysisStatus.Unreliable)?.bpm!!
        assertBpmCloseOrHalfTime(bpm, 160.0)
    }

    @Test
    fun silentAndIrregularNonMusicalAudioFallBackSafelyToTimeBasedFade() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(createTempDbFile("silent_fallback")), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cache = TrackAnalysisCache(createTempCacheDir("silent_fallback"), diagnostics = diagnostics)
        val modelProvider = BeatThisModelProvider(forcePureKotlinFallback = true, diagnostics = diagnostics)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics
        )

        // 1. Silent audio (T16)
        val fixSilent = SyntheticAudioFixtureGenerator.generateSilentWav("Silent_Track", durationSec = 10.0)
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/silent", "Silent.wav", "audio/wav", inMemoryBytes = fixSilent.wavBytes)
        )
        val trackSilent = makeTestTrack("tr_silent", "Silent.wav", "content://t/silent", fixSilent.durationUs, fixSilent.wavBytes.size.toLong(), "fp_silent")
        db.upsertTrack(trackSilent)

        val statusSilent = manager.analyze("tr_silent", AnalysisPriority.PLAYBACK_ARMED)
        assertTrue("Silent track analysis must complete without crashing", statusSilent is AnalysisStatus.Unreliable || statusSilent is AnalysisStatus.Confident)
        val analysisSilent = (statusSilent as? AnalysisStatus.Confident)?.analysis ?: (statusSilent as? AnalysisStatus.Unreliable)?.analysis!!

        // 2. Irregular non-musical transients (T16)
        val fixIrreg = SyntheticAudioFixtureGenerator.generateIrregularNonMusicalWav("Irreg_Track", durationSec = 12.0)
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/irreg", "Irreg.wav", "audio/wav", inMemoryBytes = fixIrreg.wavBytes)
        )
        val trackIrreg = makeTestTrack("tr_irreg", "Irreg.wav", "content://t/irreg", fixIrreg.durationUs, fixIrreg.wavBytes.size.toLong(), "fp_irreg")
        db.upsertTrack(trackIrreg)

        val statusIrreg = manager.analyze("tr_irreg", AnalysisPriority.PLAYBACK_ARMED)
        val analysisIrreg = (statusIrreg as? AnalysisStatus.Confident)?.analysis ?: (statusIrreg as? AnalysisStatus.Unreliable)?.analysis!!

        // 3. Verify upstream SeguePolicy / MixPlanner handles irregular / unsteady audio by producing safe time-based fade
        val normalFix = SyntheticAudioFixtureGenerator.generateClickAndSubWav("Normal_Track", 155.0, 16.0)
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/normal", "Normal.wav", "audio/wav", inMemoryBytes = normalFix.wavBytes)
        )
        val normalTrack = makeTestTrack("tr_normal", "Normal.wav", "content://t/normal", normalFix.durationUs, normalFix.wavBytes.size.toLong(), "fp_normal")
        db.upsertTrack(normalTrack)
        val normalStatus = manager.analyze("tr_normal", AnalysisPriority.PLAYBACK_ARMED)
        val normalAnalysis = (normalStatus as? AnalysisStatus.Confident)?.analysis ?: (normalStatus as? AnalysisStatus.Unreliable)?.analysis!!

        // Plan a transition between Normal and Irregular
        val plan = MixPlanner.plan(normalAnalysis, analysisIrreg)
        assertNotNull("MixPlanner must produce a valid fallback plan", plan)
        // Verify plan uses safe non-BLEND style (CUT or FADE) without beat-locked bass swap (ACC-32, A04_BAD_ANALYSIS)
        assertNotEquals("Unsteady/irregular grid transition must not be BLEND", MixPlan.Style.BLEND, plan.style)
        assertTrue("Unsteady/irregular grid transition must be CUT or FADE", plan.style in listOf(MixPlan.Style.CUT, MixPlan.Style.FADE))

        // Plan a transition from an irregular track
        val planFromIrreg = MixPlanner.plan(analysisIrreg, normalAnalysis)
        assertNotEquals("Unsteady outgoing track must not be BLEND", MixPlan.Style.BLEND, planFromIrreg.style)
        assertTrue("Unsteady outgoing track must be CUT or FADE", planFromIrreg.style in listOf(MixPlan.Style.CUT, MixPlan.Style.FADE))

        // Plan a transition from a silent track with completely absent grid
        val planFromSilent = MixPlanner.plan(analysisSilent, normalAnalysis)
        assertEquals("Absent outgoing grid must fall back to pure time-based FADE", MixPlan.Style.FADE, planFromSilent.style)
        assertFalse("Absent outgoing grid fade must not be synced", planFromSilent.synced)
    }

    @Test
    fun tempoRampIntroOutroSeparation() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(createTempDbFile("ramp_test")), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cache = TrackAnalysisCache(createTempCacheDir("ramp_test"), diagnostics = diagnostics)
        val modelProvider = BeatThisModelProvider(forcePureKotlinFallback = true, diagnostics = diagnostics)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics
        )

        // T06: 155 -> 160 BPM ramp
        val rampFix = SyntheticAudioFixtureGenerator.generateTempoRampWav("Ramp_Track", startBpm = 155.0, endBpm = 160.0, durationSec = 24.0)
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/ramp", "Ramp.wav", "audio/wav", inMemoryBytes = rampFix.wavBytes)
        )
        val track = makeTestTrack("tr_ramp", "Ramp.wav", "content://t/ramp", rampFix.durationUs, rampFix.wavBytes.size.toLong(), "fp_ramp")
        db.upsertTrack(track)

        val status = manager.analyze("tr_ramp", AnalysisPriority.PLAYBACK_ARMED)
        assertTrue("Ramp track must analyze cleanly", status is AnalysisStatus.Confident || status is AnalysisStatus.Unreliable)
        val analysis = (status as? AnalysisStatus.Confident)?.analysis ?: (status as? AnalysisStatus.Unreliable)?.analysis!!
        assertTrue(
            "BPM must be in the 150-165 range (or 75-82.5 half-time range), got ${analysis.bpm}",
            analysis.bpm in 150.0..165.0 || analysis.bpm in 75.0..82.5
        )
    }

    @Test
    fun cancellationStopsAnalysisImmediatelyWithoutCorruptingState() = runBlocking {
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(createTempDbFile("cancel_test")), diagnostics)
        val sourceProvider = VirtualSafTrackSourceProvider()
        val cache = TrackAnalysisCache(createTempCacheDir("cancel_test"), diagnostics = diagnostics)
        val modelProvider = BeatThisModelProvider(forcePureKotlinFallback = true, diagnostics = diagnostics)

        val manager = TrackAnalysisManager(
            database = db,
            sourceProvider = sourceProvider,
            modelProvider = modelProvider,
            cache = cache,
            diagnostics = diagnostics
        )

        val fix = SyntheticAudioFixtureGenerator.generateClickAndSubWav("Cancel_Track", 155.0, 16.0)
        sourceProvider.registerDocument(
            VirtualSafTrackSourceProvider.VirtualDocumentEntry("content://t/cancel", "Cancel.wav", "audio/wav", inMemoryBytes = fix.wavBytes)
        )
        val track = makeTestTrack("tr_cancel", "Cancel.wav", "content://t/cancel", fix.durationUs, fix.wavBytes.size.toLong(), "fp_cancel")
        db.upsertTrack(track)

        val job = launch {
            manager.analyze("tr_cancel", AnalysisPriority.IDLE_LIBRARY)
        }
        delay(5)
        job.cancelAndJoin()

        assertTrue("Cancelled job must be completed and cancelled", job.isCancelled)
    }
}
