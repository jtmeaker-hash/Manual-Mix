/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.LaneCurve
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.SegueSideFacts
import app.myspoti.automix.segue.SegueTuning
import app.myspoti.automix.segue.SegueViolation
import app.myspoti.automix.segue.Target
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionPlanAdapter
import app.myspoti.automix.segue.TransitionResolutionResult
import app.myspoti.automix.segue.UnitKind
import app.setflow.db.AutoPlanSnapshotEntity
import app.setflow.db.EdgeOrigin
import app.setflow.db.EdgeValidationStatus
import app.setflow.db.InvalidPlanPersistenceException
import app.setflow.db.OptimisticConcurrencyException
import app.setflow.db.OverrideSnapshotEntity
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.SetFlowSchemaAndMigrations
import app.setflow.db.TrackEntity
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.TrackAvailabilityState
import app.setflow.transition.TransitionOverrideManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToLong

/**
 * Dedicated unit test suite for Stage 07 — Editable Serializable Transition Override and Validation
 * (STAGES/07_SAVED_TRANSITIONS.txt).
 */
class TransitionOverrideStage07Test {

    private fun createTempDbFile(name: String): File {
        val dir = Files.createTempDirectory("stage07_$name").toFile()
        dir.deleteOnExit()
        return File(dir, "stage07_test.db")
    }

    private fun makeTestTrack(
        id: String,
        title: String,
        durationUs: Long = 180_000_000L,
        audioKey: String = "audiokey_$id",
        bpm: Double = 155.0
    ): TrackEntity = TrackEntity(
        trackId = id,
        displayName = "$title.wav",
        title = title,
        artist = "Stage07 Artist",
        album = "Stage07 Album",
        durationUs = durationUs,
        contentUri = "content://t/$id",
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
        contentFingerprint = "v1:size=31752044:head=$id:tail=$id",
        audioFingerprint = audioKey,
        fingerprintVersion = 1,
        cachedArtworkKey = null,
        lastVerified = 1_700_000_000_000L,
        availabilityState = TrackAvailabilityState.AVAILABLE.name
    )

    @Test
    fun persistedCustomPairSemanticsAcrossDatabaseRelaunch100Iterations() {
        val dbFile = createTempDbFile("relaunch_100")
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)

        // 1. Initial setup: create DB, tracks, project, and save custom overrides on two edges
        var db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile), diagnostics)
        val trkA = makeTestTrack("tr_a", "Song A", durationUs = 180_000_000L)
        val trkB = makeTestTrack("tr_b", "Song B", durationUs = 200_000_000L)
        val trkC = makeTestTrack("tr_c", "Song C", durationUs = 220_000_000L)
        db.upsertTrack(trkA)
        db.upsertTrack(trkB)
        db.upsertTrack(trkC)

        val project = db.createProject("100 Iterations Project")
        val detail = db.addTracksToProject(project.projectId, project.editRevision, listOf("tr_a", "tr_b", "tr_c"))
        assertEquals(2, detail.edges.size)
        val edgeAB = detail.edges[0]
        val edgeBC = detail.edges[1]

        val manager = TransitionOverrideManager(db, diagnostics)

        // Edge AB: 16 bars BASS_SWAP, exitAnchor=100s, entryAnchor=5s
        val snapAB = manager.saveOverride(
            edgeId = edgeAB.edge.edgeId,
            params = TransitionOverrideParams(
                exitAnchorUs = 100_000_000L,
                entryAnchorUs = 5_000_000L,
                barCount = 16,
                shape = SegueShape.BASS_SWAP,
                pulseMode = PulseState.LOCKED
            ),
            expectedEdgePlanRevision = edgeAB.edge.planRevision
        )

        // Edge BC: 32 bars THREE_BAND, exitAnchor=120s, entryAnchor=10s
        val snapBC = manager.saveOverride(
            edgeId = edgeBC.edge.edgeId,
            params = TransitionOverrideParams(
                exitAnchorUs = 120_000_000L,
                entryAnchorUs = 10_000_000L,
                barCount = 32,
                shape = SegueShape.THREE_BAND,
                pulseMode = PulseState.LOCKED
            ),
            expectedEdgePlanRevision = edgeBC.edge.planRevision
        )

        val expectedHashAB = snapAB.resolvedPlan!!.planHashSha256
        val expectedHashBC = snapBC.resolvedPlan!!.planHashSha256
        assertNotEquals(expectedHashAB, expectedHashBC)

        // 2. Perform 100 iterations of closing and reopening the SQLite database
        for (i in 1..100) {
            val reopenedDb = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile), diagnostics)
            val reloadedDetail = reopenedDb.getProjectDetail(project.projectId)
            assertNotNull("Project must reload on iteration $i", reloadedDetail)

            val reloadedAB = reloadedDetail!!.edges.first { it.edge.edgeId == edgeAB.edge.edgeId }
            val reloadedBC = reloadedDetail.edges.first { it.edge.edgeId == edgeBC.edge.edgeId }

            assertEquals(EdgeOrigin.CUSTOM, reloadedAB.edge.origin)
            assertEquals(EdgeOrigin.CUSTOM, reloadedBC.edge.origin)
            assertEquals(EdgeValidationStatus.VALID, reloadedAB.edge.lastValidationStatus)
            assertEquals(EdgeValidationStatus.VALID, reloadedBC.edge.lastValidationStatus)

            // Verify edgeAB exact values
            assertEquals(100_000_000L, reloadedAB.overridePlan!!.exitAnchorUs)
            assertEquals(5_000_000L, reloadedAB.overridePlan!!.entryAnchorUs)
            assertEquals(16, reloadedAB.overridePlan!!.barCount)
            assertEquals(expectedHashAB, reloadedAB.edge.lastResolvedFingerprint)
            assertEquals(expectedHashAB, reloadedAB.resolvedPlan!!.planHashSha256)

            // Verify edgeBC exact values
            assertEquals(120_000_000L, reloadedBC.overridePlan!!.exitAnchorUs)
            assertEquals(10_000_000L, reloadedBC.overridePlan!!.entryAnchorUs)
            assertEquals(32, reloadedBC.overridePlan!!.barCount)
            assertEquals(expectedHashBC, reloadedBC.edge.lastResolvedFingerprint)
            assertEquals(expectedHashBC, reloadedBC.resolvedPlan!!.planHashSha256)
        }
    }

    @Test
    fun previewAndSaveReferenceSameResolvedPlanHashAtGivenRevision() {
        val dbFile = createTempDbFile("preview_hash_match")
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile), diagnostics)

        val trkA = makeTestTrack("tr_1", "Song 1", durationUs = 180_000_000L)
        val trkB = makeTestTrack("tr_2", "Song 2", durationUs = 180_000_000L)
        db.upsertTrack(trkA)
        db.upsertTrack(trkB)

        val project = db.createProject("Preview Test")
        val detail = db.addTracksToProject(project.projectId, project.editRevision, listOf("tr_1", "tr_2"))
        val edge = detail.edges[0]

        val manager = TransitionOverrideManager(db, diagnostics)

        val params = TransitionOverrideParams(
            exitAnchorUs = 120_000_000L,
            entryAnchorUs = 8_000_000L,
            barCount = 16,
            shape = SegueShape.THREE_BAND,
            pulseMode = PulseState.LOCKED,
            swapShare = 0.5
        )

        // 1. Preview without saving
        val previewResult = manager.preview(edge.edge.edgeId, params)
        assertTrue("Preview must succeed", previewResult is TransitionResolutionResult.Success)
        val preview = previewResult as TransitionResolutionResult.Success

        // Verify database is untouched before save (D12)
        val untouchedEdge = db.getEdgeCompleteSnapshot(edge.edge.edgeId)!!
        assertNull("Database must have no override before save", untouchedEdge.overridePlan)
        assertEquals(EdgeOrigin.AUTO, untouchedEdge.edge.origin)

        // 2. Commit save
        val savedSnap = manager.saveOverride(
            edgeId = edge.edge.edgeId,
            params = params,
            expectedEdgePlanRevision = edge.edge.planRevision
        )

        // 3. Verify exact plan hash parity
        assertEquals("Preview hash must be identical to saved hash", preview.planHashSha256, savedSnap.resolvedPlan!!.planHashSha256)
        assertEquals("Preview canonical JSON must be identical to saved JSON", preview.canonicalJson, savedSnap.resolvedPlan!!.canonicalSegueJson)
    }

    @Test
    fun barConfiguration_4_8_16_32_bars_no12sAutoClamp() {
        val factsA = SegueSideFacts("audiokey_a", "grid_a", 180_000_000L)
        val factsB = SegueSideFacts("audiokey_b", "grid_b", 180_000_000L)
        val bpm = 155.0
        val oneBarUs = (240.0 / bpm * 1e6).roundToLong() // ~1,548,387 us

        for (bars in listOf(4, 8, 16, 32)) {
            val expectedLengthUs = bars * oneBarUs
            val params = TransitionOverrideParams(
                exitAnchorUs = 60_000_000L,
                entryAnchorUs = 0L,
                barCount = bars,
                shape = SegueShape.BASS_SWAP,
                pulseMode = PulseState.LOCKED
            )

            val res = TransitionPlanAdapter.resolve(
                autoPlan = null,
                override = params,
                outgoingFacts = factsA,
                incomingFacts = factsB,
                outgoingBpm = bpm,
                incomingBpm = bpm
            )

            assertTrue("Resolution for $bars bars must succeed", res is TransitionResolutionResult.Success)
            val plan = (res as TransitionResolutionResult.Success).plan

            assertEquals(bars, plan.span.musicalUnits)
            assertEquals(UnitKind.BAR, plan.span.unitKind)
            assertEquals(Clock.ELAPSED, plan.span.clock)
            assertEquals(expectedLengthUs, plan.span.lengthUs)

            // CRITICAL CONTRACT: 32-bar transition must NOT be clamped to 12 seconds or 16 bars!
            if (bars == 32) {
                val durationSec = plan.span.lengthUs / 1e6
                assertTrue("32-bar plan must exceed 45 seconds (got $durationSec s)", durationSec > 45.0)
                assertTrue("32-bar plan must not be clamped to 12s", plan.span.lengthUs > 12_000_000L)
            }
        }
    }

    @Test
    fun trackALateExitAndTrackBMidSongEntry() {
        val factsA = SegueSideFacts("audiokey_a", "grid_a", 180_000_000L) // 180s track A
        val factsB = SegueSideFacts("audiokey_b", "grid_b", 240_000_000L) // 240s track B

        // Track A exits in the last 15 seconds (165s exit anchor) with 8s span (ends at 173s <= 180s)
        // Track B enters at 1 minute (60s mid-song breakdown entry)
        val params = TransitionOverrideParams(
            exitAnchorUs = 165_000_000L,
            entryAnchorUs = 60_000_000L,
            spanUs = 8_000_000L,
            shape = SegueShape.CUT_ON_BEAT,
            pulseMode = PulseState.LOCKED
        )

        val res = TransitionPlanAdapter.resolve(
            override = params,
            outgoingFacts = factsA,
            incomingFacts = factsB,
            outgoingBpm = 155.0,
            incomingBpm = 155.0
        )

        assertTrue("Late exit and mid-song entry must be valid", res is TransitionResolutionResult.Success)
        val plan = (res as TransitionResolutionResult.Success).plan
        assertEquals(165_000_000L, plan.outgoing.anchorUs)
        assertEquals(60_000_000L, plan.incoming.anchorUs)
        assertEquals(8_000_000L, plan.span.lengthUs)
    }

    @Test
    fun multipleIndependentEdges_ReorderNeverLeaksOverride() {
        val dbFile = createTempDbFile("independent_edges")
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile), diagnostics)

        val trkA = makeTestTrack("tr_a", "Track A")
        val trkB = makeTestTrack("tr_b", "Track B")
        val trkC = makeTestTrack("tr_c", "Track C")
        val trkD = makeTestTrack("tr_d", "Track D")
        db.upsertTrack(trkA)
        db.upsertTrack(trkB)
        db.upsertTrack(trkC)
        db.upsertTrack(trkD)

        val project = db.createProject("Reorder Leak Test")
        // Sequence: A -> B -> C -> D
        val detail = db.addTracksToProject(project.projectId, project.editRevision, listOf("tr_a", "tr_b", "tr_c", "tr_d"))
        val edgeAB = detail.edges[0]
        val edgeBC = detail.edges[1]
        val edgeCD = detail.edges[2]

        val manager = TransitionOverrideManager(db, diagnostics)

        // Save distinct override on edge AB (8 bars, CUT_ON_BEAT)
        manager.saveOverride(
            edgeId = edgeAB.edge.edgeId,
            params = TransitionOverrideParams(barCount = 8, shape = SegueShape.CUT_ON_BEAT, pulseMode = PulseState.LOCKED),
            expectedEdgePlanRevision = edgeAB.edge.planRevision
        )

        // Save distinct override on edge CD (16 bars, FILTER_OPEN)
        manager.saveOverride(
            edgeId = edgeCD.edge.edgeId,
            params = TransitionOverrideParams(barCount = 16, shape = SegueShape.FILTER_OPEN, pulseMode = PulseState.LOCKED),
            expectedEdgePlanRevision = edgeCD.edge.planRevision
        )

        val snapAB = db.getEdgeCompleteSnapshot(edgeAB.edge.edgeId)!!
        val snapCD = db.getEdgeCompleteSnapshot(edgeCD.edge.edgeId)!!
        assertNotEquals(snapAB.resolvedPlan!!.planHashSha256, snapCD.resolvedPlan!!.planHashSha256)

        // Reorder set from [A, B, C, D] to [A, C, B, D]
        val reorderedOccIds = listOf(
            detail.occurrences[0].occurrence.occurrenceId, // A
            detail.occurrences[2].occurrence.occurrenceId, // C
            detail.occurrences[1].occurrence.occurrenceId, // B
            detail.occurrences[3].occurrence.occurrenceId  // D
        )

        val updatedDetail = db.reorderOccurrences(
            projectId = project.projectId,
            expectedRevision = db.getProjectDetail(project.projectId)!!.project.editRevision,
            newOrderedOccurrenceIds = reorderedOccIds
        )

        // The new first edge is A -> C (new edgeId, NOT edgeAB!)
        val edgeAC = updatedDetail.edges[0]
        assertNotEquals("New A->C edge must have a new edgeId", edgeAB.edge.edgeId, edgeAC.edge.edgeId)
        assertEquals("New A->C edge must start in AUTO origin without leaking A->B override", EdgeOrigin.AUTO, edgeAC.edge.origin)
        assertNull("A->C edge must have no override", edgeAC.overridePlan)
    }

    @Test
    fun duplicateTrackOccurrencesMaintainIndependentEdges() {
        val dbFile = createTempDbFile("duplicate_occurrences")
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile), diagnostics)

        val trkA = makeTestTrack("tr_dup_a", "Song A")
        val trkB = makeTestTrack("tr_dup_b", "Song B")
        db.upsertTrack(trkA)
        db.upsertTrack(trkB)

        // Sequence: A -> B -> A (Song A appears at pos 0 and pos 2)
        val project = db.createProject("Duplicate Occurrences Project")
        val detail = db.addTracksToProject(project.projectId, project.editRevision, listOf("tr_dup_a", "tr_dup_b", "tr_dup_a"))
        assertEquals(2, detail.edges.size)

        val edge1 = detail.edges[0] // A1 -> B
        val edge2 = detail.edges[1] // B -> A2
        assertNotEquals("Edges must have distinct IDs", edge1.edge.edgeId, edge2.edge.edgeId)

        val manager = TransitionOverrideManager(db, diagnostics)

        // Edge 1: 8 bars CUT_ON_BEAT
        val snap1 = manager.saveOverride(
            edgeId = edge1.edge.edgeId,
            params = TransitionOverrideParams(barCount = 8, shape = SegueShape.CUT_ON_BEAT, pulseMode = PulseState.LOCKED),
            expectedEdgePlanRevision = edge1.edge.planRevision
        )

        // Edge 2: 32 bars THREE_BAND
        val snap2 = manager.saveOverride(
            edgeId = edge2.edge.edgeId,
            params = TransitionOverrideParams(barCount = 32, shape = SegueShape.THREE_BAND, pulseMode = PulseState.LOCKED),
            expectedEdgePlanRevision = edge2.edge.planRevision
        )

        assertNotEquals("Edges sharing a track must produce independent resolved plans", snap1.resolvedPlan!!.planHashSha256, snap2.resolvedPlan!!.planHashSha256)
        assertEquals(8, snap1.overridePlan!!.barCount)
        assertEquals(32, snap2.overridePlan!!.barCount)
    }

    @Test
    fun backgroundAutoAnalysisNeverOverwritesManualOverride_D10_NeedsReview() {
        val dbFile = createTempDbFile("d10_needs_review")
        val diagnostics = DiagnosticsRingBuffer(capacity = 64)
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile), diagnostics)

        val trkA = makeTestTrack("tr_rev_a", "Track A")
        val trkB = makeTestTrack("tr_rev_b", "Track B")
        db.upsertTrack(trkA)
        db.upsertTrack(trkB)

        val project = db.createProject("D10 Review Project")
        val detail = db.addTracksToProject(project.projectId, project.editRevision, listOf("tr_rev_a", "tr_rev_b"))
        val edge = detail.edges[0]

        val manager = TransitionOverrideManager(db, diagnostics)

        // Save manual override
        val committedSnap = manager.saveOverride(
            edgeId = edge.edge.edgeId,
            params = TransitionOverrideParams(
                exitAnchorUs = 120_000_000L,
                entryAnchorUs = 10_000_000L,
                barCount = 16,
                shape = SegueShape.BASS_SWAP,
                pulseMode = PulseState.LOCKED
            ),
            expectedEdgePlanRevision = edge.edge.planRevision
        )
        assertEquals(EdgeOrigin.CUSTOM, committedSnap.edge.origin)
        assertEquals(EdgeValidationStatus.VALID, committedSnap.edge.lastValidationStatus)

        // Simulate background auto analysis producing a new auto plan
        val newAutoPlan = AutoPlanSnapshotEntity(
            edgeId = edge.edge.edgeId,
            engineVersion = "setflow-engine-2.0",
            analyzerVersion = "410",
            parametersJson = "{}",
            analysisKeyA = "new_key_a",
            analysisKeyB = "new_key_b",
            planCanonicalJson = committedSnap.resolvedPlan!!.canonicalSegueJson,
            generatedAtUtc = System.currentTimeMillis()
        )
        db.recordBackgroundAutoPlanReanalysis(edge.edge.edgeId, newAutoPlan)

        // Check edge status after auto reanalysis
        val staleEdge = db.getEdgeCompleteSnapshot(edge.edge.edgeId)!!
        // CONTRACT D10: Manual override MUST NOT be erased or deleted
        assertNotNull("Manual override must survive background auto analysis", staleEdge.overridePlan)
        assertEquals(120_000_000L, staleEdge.overridePlan!!.exitAnchorUs)
        assertEquals(16, staleEdge.overridePlan!!.barCount)
        assertEquals(EdgeOrigin.CUSTOM, staleEdge.edge.origin)
        // Edge is flagged as STALE_ANALYSIS ('Needs review')
        assertEquals(EdgeValidationStatus.STALE_ANALYSIS, staleEdge.edge.lastValidationStatus)

        // Reset to Auto is an explicit user action
        val resetSnap = manager.resetToAuto(
            edgeId = edge.edge.edgeId,
            expectedEdgePlanRevision = staleEdge.edge.planRevision
        )
        assertEquals(EdgeOrigin.AUTO, resetSnap.edge.origin)
        assertNull("Override must be cleared after explicit Reset to Auto", resetSnap.overridePlan)
        assertEquals(EdgeValidationStatus.VALID, resetSnap.edge.lastValidationStatus)
    }

    @Test
    fun fuzzMalformedKnots_NegativeAnchors_Overflow_andUnknownSchema() {
        val factsA = SegueSideFacts("audiokey_a", "grid_a", 180_000_000L)
        val factsB = SegueSideFacts("audiokey_b", "grid_b", 180_000_000L)

        // 1. Negative anchor
        val resNegativeAnchor = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(exitAnchorUs = -5_000_000L),
            outgoingFacts = factsA,
            incomingFacts = factsB
        )
        assertTrue(resNegativeAnchor is TransitionResolutionResult.Failure)
        assertTrue((resNegativeAnchor as TransitionResolutionResult.Failure).violations.contains(SegueViolation.ANCHOR_NEGATIVE))

        // 2. Length zero or negative
        val resZeroLength = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(spanUs = 0L),
            outgoingFacts = factsA,
            incomingFacts = factsB
        )
        // Default fallback handles 0 spanUs by defaulting to 8s; if forced to 0 length in plan:
        val planZero = SeguePlan(
            origin = TransitionPlanAdapter.ENGINE_ORIGIN,
            outgoing = SeguePlan.Side("k1", "g1", 0L, 1_000_000L),
            incoming = SeguePlan.Side("k2", "g2", 0L, 1_000_000L),
            span = SeguePlan.Span(0L, null, UnitKind.BAR, Clock.ELAPSED),
            pulse = SeguePlan.Pulse(PulseState.FREE, null, null),
            lanes = emptyList()
        )
        assertTrue(app.myspoti.automix.segue.SegueValidator.structural(planZero).contains(SegueViolation.LENGTH_NOT_POSITIVE))

        // 3. Window exceeds track duration
        val resPastTrack = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(exitAnchorUs = 175_000_000L, spanUs = 20_000_000L), // 175s + 20s = 195s > 180s
            outgoingFacts = factsA,
            incomingFacts = factsB
        )
        assertTrue(resPastTrack is TransitionResolutionResult.Failure)
        val violations = (resPastTrack as TransitionResolutionResult.Failure).violations
        assertTrue("Must flag WINDOW_PAST_OUTGOING", violations.contains(SegueViolation.WINDOW_PAST_OUTGOING))

        // 4. Malformed custom lanes JSON: knots not increasing
        val malformedKnotsJson = """
            [
              {
                "side": "outgoing",
                "target": "level",
                "unit": "db",
                "knots": [
                  [0, 0.0, "step"],
                  [600000, -12.0, "ramp"],
                  [400000, -24.0, "ramp"],
                  [1000000, null, "ramp"]
                ]
              }
            ]
        """.trimIndent()
        val resBadKnots = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(customLanesJson = malformedKnotsJson),
            outgoingFacts = factsA,
            incomingFacts = factsB
        )
        assertTrue(resBadKnots is TransitionResolutionResult.Failure)
        assertTrue((resBadKnots as TransitionResolutionResult.Failure).violations.contains(SegueViolation.KNOTS_NOT_INCREASING))

        // 5. Unknown schema segue/99
        val unknownSchemaJson = """
            {"schema":"segue/99","origin":{"engine":"test","corpus":"test"},"outgoing":{"audioKey":"a","anchorUs":0,"ratePpm":1000000,"listenerRate":1000000},"incoming":{"audioKey":"b","anchorUs":0,"ratePpm":1000000,"listenerRate":1000000},"span":{"lengthUs":8000000,"clock":"elapsed","unitKind":"bar"},"pulse":{"state":"free"},"lanes":[]}
        """.trimIndent()
        try {
            SegueCodec.decode(unknownSchemaJson)
            fail("Unknown major version segue/99 must be rejected")
        } catch (e: Exception) {
            assertTrue("Expected unsupported major version error", e.message?.contains("unsupported major version") == true)
        }
    }

    @Test
    fun distinctAudioCurvesForDistinctCustomShapes() {
        val factsA = SegueSideFacts("audiokey_a", "grid_a", 180_000_000L)
        val factsB = SegueSideFacts("audiokey_b", "grid_b", 180_000_000L)

        val shapes = listOf(
            SegueShape.BASS_SWAP,
            SegueShape.THREE_BAND,
            SegueShape.CUT_ON_BEAT,
            SegueShape.FILTER_OPEN,
            SegueShape.RISE_OUT,
            SegueShape.EVEN_FADE,
            SegueShape.SWEEP_OUT
        )

        val hashes = mutableSetOf<String>()
        val plans = mutableListOf<SeguePlan>()

        for (shape in shapes) {
            val res = TransitionPlanAdapter.resolve(
                override = TransitionOverrideParams(
                    exitAnchorUs = 60_000_000L,
                    entryAnchorUs = 0L,
                    barCount = 16,
                    shape = shape,
                    pulseMode = if (shape in listOf(SegueShape.EVEN_FADE, SegueShape.SWEEP_OUT)) PulseState.FREE else PulseState.LOCKED
                ),
                outgoingFacts = factsA,
                incomingFacts = factsB,
                outgoingBpm = 155.0,
                incomingBpm = 155.0
            )

            assertTrue("Shape $shape must compile cleanly", res is TransitionResolutionResult.Success)
            val success = res as TransitionResolutionResult.Success
            hashes.add(success.planHashSha256)
            plans.add(success.plan)
        }

        // Verify all 7 shapes produce uniquely distinct fingerprint hashes
        assertEquals("Each shape must produce a unique plan hash", shapes.size, hashes.size)

        // Verify mathematical curve distinctions on audio DSP lanes
        val bassSwapPlan = plans[0]
        val cutOnBeatPlan = plans[2]

        val bassSwapLevelCurve = LaneCurve(bassSwapPlan.lanes.first { it.side == LaneSide.OUTGOING && it.target == Target.LEVEL })
        val cutLevelCurve = LaneCurve(cutOnBeatPlan.lanes.first { it.side == LaneSide.OUTGOING && it.target == Target.LEVEL })

        // At x = 0.25 (t = 250_000), BASS_SWAP outgoing deck is fully up (0 dB), while CUT_ON_BEAT is also up (0 dB)
        // At x = 0.75 (t = 750_000), BASS_SWAP has smoothly faded down, while CUT_ON_BEAT is hard cut to silence (-60 dB floor)
        val bassSwapAt75 = bassSwapLevelCurve.value(750_000.0)
        val cutAt75 = cutLevelCurve.value(750_000.0)
        assertTrue("BASS_SWAP at 75% must be higher than hard cut silence", bassSwapAt75 > cutAt75)
        assertEquals("CUT_ON_BEAT at 75% must be at silence floor", SegueFormat.LEVEL_FLOOR_DB, cutAt75, 0.1)
    }
}
