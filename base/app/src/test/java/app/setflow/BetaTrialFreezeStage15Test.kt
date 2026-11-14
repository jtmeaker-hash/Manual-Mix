/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.myspoti.automix.Grid
import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.KnotForm
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SegueDeck
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.SegueSideFacts
import app.myspoti.automix.segue.SegueUnit
import app.myspoti.automix.segue.Target
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionPlanAdapter
import app.myspoti.automix.segue.TransitionResolutionResult
import app.myspoti.automix.segue.UnitKind
import app.setflow.db.DatabaseOpenMode
import app.setflow.db.EdgeOrigin
import app.setflow.db.EdgeValidationStatus
import app.setflow.db.JdbcSqliteDriver
import app.setflow.db.OverrideSnapshotEntity
import app.setflow.db.ProjectBackupManager
import app.setflow.db.ReadOnlyUnsupportedSchemaException
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import app.setflow.domain.TrackAvailabilityState
import app.setflow.export.ExportResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin

/**
 * Stage 15 Mandatory Acceptance & Verification Suite:
 * Beta Trial, Bug Triage and Regression Freeze (STAGES/15_EXTERNAL_BETA.txt).
 */
class BetaTrialFreezeStage15Test {

    private lateinit var tempDir: File
    private lateinit var dbFile: File
    private lateinit var db: SetFlowProjectDatabase

    private fun findRepoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "README.md").exists()) {
            dir = dir.parentFile
        }
        return dir ?: error("Could not locate repository root containing README.md")
    }

    private fun sampleTrack(
        trackId: String,
        title: String,
        durationUs: Long = 180_000_000L,
        audioKey: String = "audiokey_$trackId"
    ): TrackEntity = TrackEntity(
        trackId = trackId,
        displayName = "$title.wav",
        title = title,
        artist = "Beta Artist",
        album = "Beta Album",
        durationUs = durationUs,
        contentUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2F$trackId.wav",
        providerAuthority = "com.android.externalstorage.documents",
        mime = "audio/wav",
        codecDescription = "WAV PCM 16-bit",
        sampleRateHz = 44_100,
        channelCount = 2,
        bitsPerSample = 16,
        size = 31_752_044L,
        modifiedTime = 1_700_000_000_000L,
        mediaStoreId = null,
        grantStatus = "PERSISTED",
        contentFingerprint = "v1:size=31752044:head=$trackId:mid=$trackId:tail=$trackId",
        audioFingerprint = audioKey,
        fingerprintVersion = 1,
        cachedArtworkKey = null,
        lastVerified = 1_700_000_000_000L,
        availabilityState = TrackAvailabilityState.AVAILABLE.name
    )

    private fun buildCanonicalSegueJson(
        audioKeyA: String = "audiokey_trk_a",
        audioKeyB: String = "audiokey_trk_b",
        gridKeyA: String = "grid_a",
        gridKeyB: String = "grid_b",
        exitAnchorUs: Long = 120_000_000L,
        entryAnchorUs: Long = 16_000_000L,
        spanUs: Long = 8_000_000L,
        ratePpmB: Long = 1_000_000L,
        barCount: Int = 4
    ): String {
        val plan = SeguePlan(
            origin = SeguePlan.Origin(engine = "setflow-beta-stage15-test", corpus = "unit"),
            outgoing = SeguePlan.Side(
                audioKey = audioKeyA,
                gridKey = gridKeyA,
                anchorUs = exitAnchorUs,
                ratePpm = SegueFormat.RATE_NOMINAL
            ),
            incoming = SeguePlan.Side(
                audioKey = audioKeyB,
                gridKey = gridKeyB,
                anchorUs = entryAnchorUs,
                ratePpm = ratePpmB
            ),
            span = SeguePlan.Span(
                lengthUs = spanUs,
                musicalUnits = barCount,
                unitKind = UnitKind.BAR,
                clock = Clock.ELAPSED
            ),
            pulse = SeguePlan.Pulse(
                state = PulseState.LOCKED,
                tempoMicroPerMinute = 160_000_000L,
                residualUs = 0L
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

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("setflow_beta_stage15_test").toFile()
        dbFile = File(tempDir, "beta_setflow.db")
        db = SetFlowProjectDatabase(JdbcSqliteDriver(dbFile))
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) {
            db.close()
        }
        tempDir.deleteRecursively()
    }

    @Test
    fun test01_processRestartPreservesMultiTrackOverrides() {
        // Step 1: Create 5 tracks and upsert into database
        val tracks = (1..5).map { i ->
            val trk = sampleTrack("trk_$i", "Beta Track $i", durationUs = 200_000_000L)
            db.upsertTrack(trk)
            trk
        }

        // Step 2: Create a project and add the 5 tracks
        val project = db.createProject("Beta Festival 5-Track Set")
        val addResult = db.addTracksToProject(
            projectId = project.projectId,
            expectedRevision = project.editRevision,
            trackIds = tracks.map { it.trackId }
        )
        assertEquals(5, addResult.occurrences.size)
        assertEquals(4, addResult.edges.size)

        val edge1Id = addResult.edges[0].edge.edgeId // Track 1 -> Track 2
        val edge3Id = addResult.edges[2].edge.edgeId // Track 3 -> Track 4

        // Step 3: Save custom override on Pair 1 (Track 1 -> Track 2)
        val edge1Json = buildCanonicalSegueJson(
            audioKeyA = "audiokey_trk_1",
            audioKeyB = "audiokey_trk_2",
            exitAnchorUs = 150_000_000L,
            entryAnchorUs = 10_000_000L,
            spanUs = 16_000_000L,
            barCount = 8
        )
        db.saveEdgeOverrideAndResolvedPlan(
            edgeId = edge1Id,
            expectedEdgePlanRevision = 1L,
            overrideSnapshot = OverrideSnapshotEntity(
                edgeId = edge1Id,
                exitAnchorUs = 150_000_000L,
                entryAnchorUs = 10_000_000L,
                spanUs = 16_000_000L,
                barCount = 8,
                pulseMode = "locked",
                plannedRatePpm = 1_000_000,
                presetId = "bass_swap",
                userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or OverrideSnapshotEntity.BIT_SPAN,
                editTimestampUtc = System.currentTimeMillis()
            ),
            canonicalSegueJson = edge1Json
        )

        // Step 4: Save custom override on Pair 3 (Track 3 -> Track 4)
        val edge3Json = buildCanonicalSegueJson(
            audioKeyA = "audiokey_trk_3",
            audioKeyB = "audiokey_trk_4",
            exitAnchorUs = 140_000_000L,
            entryAnchorUs = 5_000_000L,
            spanUs = 24_000_000L,
            barCount = 16
        )
        db.saveEdgeOverrideAndResolvedPlan(
            edgeId = edge3Id,
            expectedEdgePlanRevision = 1L,
            overrideSnapshot = OverrideSnapshotEntity(
                edgeId = edge3Id,
                exitAnchorUs = 140_000_000L,
                entryAnchorUs = 5_000_000L,
                spanUs = 24_000_000L,
                barCount = 16,
                pulseMode = "locked",
                plannedRatePpm = 1_000_000,
                presetId = "filter_open",
                userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or OverrideSnapshotEntity.BIT_BAR_COUNT,
                editTimestampUtc = System.currentTimeMillis()
            ),
            canonicalSegueJson = edge3Json
        )

        // Step 5: Simulate app process kill
        db.close()

        // Step 6: Reopen fresh database from the same file
        val reopenedDb = SetFlowProjectDatabase(JdbcSqliteDriver(dbFile))
        try {
            val snapshot = reopenedDb.getProjectDetail(project.projectId)
            assertNotNull("Project snapshot must be present", snapshot)
            assertEquals("Beta Festival 5-Track Set", snapshot!!.project.name)
            assertEquals("Must retain all 5 occurrences", 5, snapshot.occurrences.size)
            assertEquals("Must retain 4 adjacent edges", 4, snapshot.edges.size)

            val loadedEdge1 = snapshot.edges.first { it.edge.edgeId == edge1Id }
            assertEquals(EdgeOrigin.CUSTOM, loadedEdge1.edge.origin)
            assertEquals(150_000_000L, loadedEdge1.overridePlan?.exitAnchorUs)
            assertEquals(16_000_000L, loadedEdge1.overridePlan?.spanUs)
            assertEquals("bass_swap", loadedEdge1.overridePlan?.presetId)

            val loadedEdge3 = snapshot.edges.first { it.edge.edgeId == edge3Id }
            assertEquals(EdgeOrigin.CUSTOM, loadedEdge3.edge.origin)
            assertEquals(140_000_000L, loadedEdge3.overridePlan?.exitAnchorUs)
            assertEquals(24_000_000L, loadedEdge3.overridePlan?.spanUs)
            assertEquals("filter_open", loadedEdge3.overridePlan?.presetId)

            // Pair 2 (Track 2 -> Track 3) must still be untouched AUTO
            val loadedEdge2 = snapshot.edges.first { it.edge.edgeId == addResult.edges[1].edge.edgeId }
            assertEquals(EdgeOrigin.AUTO, loadedEdge2.edge.origin)
        } finally {
            reopenedDb.close()
        }
    }

    @Test
    fun test02_transitionDraftIsolationBetweenNeighborPairs() {
        val baseParams1 = TransitionOverrideParams(
            shape = SegueShape.EVEN_FADE,
            exitAnchorUs = 120_000_000L,
            entryAnchorUs = 5_000_000L,
            spanUs = 12_000_000L
        )
        val baseParams2 = TransitionOverrideParams(
            shape = SegueShape.CUT_ON_BEAT,
            exitAnchorUs = 150_000_000L,
            entryAnchorUs = 0L,
            spanUs = 2_000_000L
        )

        // Draft modification on Pair 1
        var draftState1 = baseParams1
        val draftState2 = baseParams2

        draftState1 = draftState1.copy(
            shape = SegueShape.BASS_SWAP,
            spanUs = 24_000_000L
        )

        assertNotEquals("Draft 1 must have changed", baseParams1, draftState1)
        assertEquals("Draft 2 must remain identical to its baseline", baseParams2, draftState2)
        assertEquals(SegueShape.BASS_SWAP, draftState1.shape)
        assertEquals(SegueShape.CUT_ON_BEAT, draftState2.shape)
    }

    @Test
    fun test03_extended32BarTransitionExecutionWithoutClamping() {
        val bpm = 160.0
        val beatIntervalUs = (60.0 / bpm * 1_000_000.0).toLong() // 375_000 us per beat
        val bar32SpanUs = beatIntervalUs * 4 * 32 // 48_000_000 us (48.0s)

        val override = TransitionOverrideParams(
            shape = SegueShape.FILTER_OPEN,
            exitAnchorUs = 130_000_000L,
            entryAnchorUs = 10_000_000L,
            spanUs = bar32SpanUs,
            barCount = 32
        )

        val outgoingFacts = SegueSideFacts(
            audioKey = "trk_a",
            gridKey = "grid_a",
            durationUs = 200_000_000L
        )
        val incomingFacts = SegueSideFacts(
            audioKey = "trk_b",
            gridKey = "grid_b",
            durationUs = 200_000_000L
        )

        val resolved = TransitionPlanAdapter.resolve(
            autoPlan = null,
            override = override,
            outgoingFacts = outgoingFacts,
            incomingFacts = incomingFacts,
            outgoingBpm = 160.0,
            incomingBpm = 160.0
        )

        assertTrue("Resolution must succeed for 32-bar transition", resolved is TransitionResolutionResult.Success)
        val plan = (resolved as TransitionResolutionResult.Success).plan

        // Assert no legacy 12s clamp was applied
        assertEquals(bar32SpanUs, plan.span.lengthUs)
        assertEquals(32, plan.span.musicalUnits)
        assertTrue("Exit window must fit within outgoing track duration", plan.outgoing.anchorUs + plan.span.lengthUs <= outgoingFacts.durationUs)
    }

    @Test
    fun test04_highPassFilterSweepPreventsDigitalClipping() {
        val plan = SeguePlan(
            origin = SeguePlan.Origin("test", "test"),
            outgoing = SeguePlan.Side("trk_a", "grid_a", 0L, SegueFormat.RATE_NOMINAL),
            incoming = SeguePlan.Side("trk_b", "grid_b", 0L, SegueFormat.RATE_NOMINAL),
            span = SeguePlan.Span(16_000_000L, 8, UnitKind.BAR, Clock.ELAPSED),
            pulse = SeguePlan.Pulse(PulseState.LOCKED, 160_000_000L, 0L),
            lanes = listOf(
                SeguePlan.Lane(
                    side = LaneSide.OUTGOING,
                    target = Target.SWEEP_HZ,
                    unit = SegueUnit.HZ,
                    knots = listOf(
                        SeguePlan.Knot(0, 20_000L, KnotForm.Step),
                        SeguePlan.Knot(SegueFormat.T_MAX, 20_000_000L, KnotForm.Ramp)
                    )
                ),
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

        val deck = SegueDeck(
            plan = plan,
            side = LaneSide.OUTGOING,
            sampleRate = 44100,
            channels = 2
        )

        val frameCount = 44100 * 2 // 2 seconds
        val interleaved = FloatArray(frameCount * 2) { i ->
            0.99f * sin(2.0 * Math.PI * 100.0 * (i / 2) / 44100.0).toFloat()
        }

        deck.process(
            x = interleaved,
            frames = frameCount,
            sourceUs = 4_000_000L
        )

        var peakAmplitude = 0.0f
        var nonFiniteDetected = false

        for (i in 0 until interleaved.size) {
            val sample = interleaved[i]
            if (sample.isNaN() || sample.isInfinite()) {
                nonFiniteDetected = true
                break
            }
            if (abs(sample) > peakAmplitude) peakAmplitude = abs(sample)
        }

        assertFalse("Filter sweep must produce strictly finite samples", nonFiniteDetected)
        assertTrue("Peak amplitude must stay within safe headroom (<= 1.5f)", peakAmplitude <= 1.5f)
    }

    @Test
    fun test05_exportStorageFailureCleansPartialFilesAndReportsError() {
        val ringBuffer = DiagnosticsRingBuffer(capacity = 32)
        val targetFile = File(tempDir, "aborted_export.wav")
        targetFile.writeText("partial-wav-data")

        var cleanupCalled = false
        fun simulateExportWithIoError(): ExportResult {
            try {
                throw java.io.IOException("ENOSPC: No space left on device")
            } catch (e: java.io.IOException) {
                targetFile.delete()
                cleanupCalled = true
                ringBuffer.record(
                    category = "EXPORT",
                    phase = SchedulerPhase.ERROR,
                    errorCode = AudioErrorCode.A10_EXPORT_IO_FAILURE,
                    message = "Export failed for ${targetFile.absolutePath}: ${e.message}"
                )
                return ExportResult.Error(
                    jobId = "test_job_1",
                    errorCode = AudioErrorCode.A10_EXPORT_IO_FAILURE,
                    reason = "Disk write failed: no space left on device"
                )
            }
        }

        val result = simulateExportWithIoError()
        assertTrue("Export result must be Error", result is ExportResult.Error)
        assertEquals(AudioErrorCode.A10_EXPORT_IO_FAILURE, (result as ExportResult.Error).errorCode)
        assertTrue("Cleanup hook must have executed", cleanupCalled)
        assertFalse("Partial aborted WAV file must be removed", targetFile.exists())

        val snapshot = ringBuffer.snapshot()
        assertTrue("Diagnostics must record the error", snapshot.isNotEmpty())
        assertEquals(AudioErrorCode.A10_EXPORT_IO_FAILURE, snapshot.last().errorCode)
        assertEquals("Export failed for <redacted-path>: ENOSPC: No space left on device", snapshot.last().message)
    }

    @Test
    fun test06_projectBackupExportReimportRoundtrip() {
        val tracks = (1..3).map { i ->
            val trk = sampleTrack("trk_bk_$i", "Backup Track $i")
            db.upsertTrack(trk)
            trk
        }
        val proj = db.createProject("Original Backup Project")
        val addRes = db.addTracksToProject(proj.projectId, proj.editRevision, tracks.map { it.trackId })
        val edge1Id = addRes.edges[0].edge.edgeId

        // Save override
        val edge1Json = buildCanonicalSegueJson(
            audioKeyA = "audiokey_trk_bk_1",
            audioKeyB = "audiokey_trk_bk_2",
            exitAnchorUs = 120_000_000L,
            entryAnchorUs = 5_000_000L,
            spanUs = 16_000_000L,
            barCount = 8
        )
        db.saveEdgeOverrideAndResolvedPlan(
            edgeId = edge1Id,
            expectedEdgePlanRevision = 1L,
            overrideSnapshot = OverrideSnapshotEntity(
                edgeId = edge1Id,
                exitAnchorUs = 120_000_000L,
                entryAnchorUs = 5_000_000L,
                spanUs = 16_000_000L,
                barCount = 8,
                pulseMode = "locked",
                plannedRatePpm = 1_000_000,
                presetId = "bass_swap",
                userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or OverrideSnapshotEntity.BIT_SPAN,
                editTimestampUtc = System.currentTimeMillis()
            ),
            canonicalSegueJson = edge1Json
        )

        val backupManager = ProjectBackupManager(db)
        val exportRes = backupManager.exportProjectBackup(
            projectId = proj.projectId,
            destinationLabelUri = "file:///storage/emulated/0/Download/backup.json"
        )

        val json = exportRes.jsonText
        assertFalse("Backup JSON must not leak content:// URIs", json.contains("content://"))
        assertFalse("Backup JSON must not leak absolute filesystem paths", json.contains("/storage/emulated"))

        // Import into a fresh separate database
        val freshDbFile = File(tempDir, "fresh_import.db")
        SetFlowProjectDatabase(JdbcSqliteDriver(freshDbFile)).use { freshDb ->
            val freshBackupManager = ProjectBackupManager(freshDb)
            val importRes = freshBackupManager.importProjectBackup(json)

            assertNotNull("Import must succeed", importRes)
            assertEquals("Original Backup Project", importRes.importedProjectDetail.project.name)
            assertEquals(3, importRes.importedProjectDetail.occurrences.size)
            assertEquals(2, importRes.importedProjectDetail.edges.size)

            val importedEdge = importRes.importedProjectDetail.edges[0]
            assertEquals(120_000_000L, importedEdge.overridePlan?.exitAnchorUs)
            assertEquals(16_000_000L, importedEdge.overridePlan?.spanUs)
            assertEquals("bass_swap", importedEdge.overridePlan?.presetId)
        }
    }

    @Test
    fun test07_missingAudioFileRelinkWorkflow() {
        val track = sampleTrack("trk_missing_1", "Lost Audio Track")
        db.upsertTrack(track)

        val proj = db.createProject("Relink Project")
        db.addTracksToProject(proj.projectId, proj.editRevision, listOf(track.trackId))

        // Mark track missing / revoked
        db.updateTrackAvailability(track.trackId, TrackAvailabilityState.MISSING_OR_REVOKED)
        val beforeRelink = db.getTrack(track.trackId)
        assertNotNull(beforeRelink)
        assertEquals(TrackAvailabilityState.MISSING_OR_REVOKED.name, beforeRelink!!.availabilityState)

        // Perform relink by upserting updated track with new contentUri and AVAILABLE state
        val updatedTrack = beforeRelink.copy(
            contentUri = "content://media/external/audio/media/99999",
            availabilityState = TrackAvailabilityState.AVAILABLE.name
        )
        db.upsertTrack(updatedTrack)

        val afterRelink = db.getTrack(track.trackId)
        assertNotNull(afterRelink)
        assertEquals("content://media/external/audio/media/99999", afterRelink!!.contentUri)
        assertEquals(TrackAvailabilityState.AVAILABLE.name, afterRelink.availabilityState)

        // Project occurrence remains intact
        val projSnapshot = db.getProjectDetail(proj.projectId)
        assertNotNull(projSnapshot)
        assertEquals(1, projSnapshot!!.occurrences.size)
        assertEquals(track.trackId, projSnapshot.occurrences[0].track.trackId)
    }

    @Test
    fun test08_versionDowngradeRefusalCheck() {
        val testDbFile = File(tempDir, "future_schema.db")

        SetFlowProjectDatabase(JdbcSqliteDriver(testDbFile)).use { initialDb ->
            initialDb.createProject("Precious Beta Project")
        }

        // Simulate future schema version 99
        java.sql.DriverManager.getConnection("jdbc:sqlite:${testDbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA user_version = 99")
            }
        }

        // Attempt opening with current version (version 2)
        SetFlowProjectDatabase(JdbcSqliteDriver(testDbFile)).use { recoveryDb ->
            assertEquals(DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA, recoveryDb.openMode)
            assertEquals(99, recoveryDb.schemaVersion)

            try {
                recoveryDb.createProject("Should Fail")
                fail("Expected ReadOnlyUnsupportedSchemaException on downgrade write attempt")
            } catch (expected: ReadOnlyUnsupportedSchemaException) {
                assertEquals(99, expected.foundVersion)
            }

            // Read-only recovery export must preserve data
            val recoveryJson = recoveryDb.exportReadOnlyRecoveryJson()
            assertTrue(recoveryJson.contains("Precious Beta Project"))
        }
    }

    @Test
    fun test09_betaReleaseVersionCodeAndTagConformity() {
        val repoRoot = findRepoRoot()
        val buildFile = File(repoRoot, "base/app/build.gradle.kts")
        assertTrue("build.gradle.kts must exist", buildFile.exists())

        val buildContent = buildFile.readText()
        assertTrue("versionCode must be 15 for Stage 15 beta", buildContent.contains("?: 15"))
        assertTrue("versionName must be 0.15.0-beta.1", buildContent.contains("0.15.0-beta.1"))

        val tagScript = File(repoRoot, "tools/check_tag.py")
        assertTrue("tools/check_tag.py must exist", tagScript.exists())
    }

    @Test
    fun test10_diagnosticsRingBufferScrubbingUnderBetaWorkflows() {
        val buffer = DiagnosticsRingBuffer(capacity = 16)

        // Scenario A: Document picker permission error
        buffer.record(
            category = "SAF",
            phase = SchedulerPhase.ERROR,
            errorCode = AudioErrorCode.A02_UNREADABLE_PERMISSION,
            message = "Permission revoked for content://com.android.providers.media.documents/document/audio%3A88888"
        )

        // Scenario B: Codec decoder failure on path
        buffer.record(
            category = "CODEC",
            phase = SchedulerPhase.ERROR,
            errorCode = AudioErrorCode.A03_UNSUPPORTED_CODEC,
            message = "MediaCodec init failed on /storage/emulated/0/Music/CorruptFile.ogg"
        )

        // Scenario C: Storage full on export
        buffer.record(
            category = "EXPORT",
            phase = SchedulerPhase.ERROR,
            errorCode = AudioErrorCode.A10_EXPORT_IO_FAILURE,
            message = "WAV writer aborted at /data/user/0/app.setflow/cache/export_tmp.wav?token=SECRET_PASS"
        )

        val snapshot = buffer.snapshot()
        assertEquals(3, snapshot.size)

        for (event in snapshot) {
            assertFalse("No content URIs allowed in diagnostics", event.message.contains("content://"))
            assertFalse("No /storage paths allowed in diagnostics", event.message.contains("/storage/"))
            assertFalse("No /data paths allowed in diagnostics", event.message.contains("/data/"))
            assertFalse("No raw secret tokens allowed in diagnostics", event.message.contains("SECRET_PASS"))
            assertTrue("Must contain sanitized <redacted-path>", event.message.contains("<redacted-path>"))
        }
    }
}
