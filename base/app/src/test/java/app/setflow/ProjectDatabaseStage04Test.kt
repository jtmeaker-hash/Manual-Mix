/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.KnotForm
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueCodec
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueSideFacts
import app.myspoti.automix.segue.SegueUnit
import app.myspoti.automix.segue.Target
import app.myspoti.automix.segue.UnitKind
import app.setflow.db.AutoPlanSnapshotEntity
import app.setflow.db.BeatGridCorrectionEntity
import app.setflow.db.DatabaseOpenMode
import app.setflow.db.EdgeOrigin
import app.setflow.db.EdgeValidationStatus
import app.setflow.db.ExportJobEntity
import app.setflow.db.InvalidPlanPersistenceException
import app.setflow.db.JdbcSqliteDriver
import app.setflow.db.OptimisticConcurrencyException
import app.setflow.db.OverrideSnapshotEntity
import app.setflow.db.ProjectBackupManager
import app.setflow.db.ReadOnlyUnsupportedSchemaException
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.TrackEntity
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.TrackAvailabilityState
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.ui.SetFlowShellController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class ProjectDatabaseStage04Test {

    private fun newTempDbFile(prefix: String): File {
        val dir = Files.createTempDirectory(prefix).toFile()
        dir.deleteOnExit()
        return File(dir, "setflow_test.db")
    }

    private fun sampleTrack(
        trackId: String,
        title: String,
        durationUs: Long = 180_000_000L,
        audioKey: String = "audiokey_$trackId"
    ) = TrackEntity(
        trackId = trackId,
        displayName = "$title.wav",
        title = title,
        artist = "Test Artist",
        album = "Test Album",
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
        barCount: Int = 4,
        knotsCount: Int = 2
    ): String {
        val knotsOut = if (knotsCount <= 2) {
            listOf(
                SeguePlan.Knot(0, 0L, KnotForm.Step),
                SeguePlan.Knot(SegueFormat.T_MAX, null, KnotForm.Ramp)
            )
        } else {
            (0 until knotsCount).map { idx ->
                val t = if (idx == knotsCount - 1) {
                    SegueFormat.T_MAX
                } else {
                    ((idx.toLong() * SegueFormat.T_MAX) / (knotsCount - 1)).toInt()
                }
                SeguePlan.Knot(t, -idx.toLong() * 10L, if (idx == 0) KnotForm.Step else KnotForm.Ramp)
            }
        }
        val plan = SeguePlan(
            origin = SeguePlan.Origin(engine = "setflow-stage04-test", corpus = "unit"),
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
                tempoMicroPerMinute = 128_000_000L,
                residualUs = 0L
            ),
            lanes = listOf(
                SeguePlan.Lane(
                    side = LaneSide.OUTGOING,
                    target = Target.LEVEL,
                    unit = SegueUnit.DB,
                    knots = knotsOut
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
    fun d01_createSetWith3TracksTwoCustomEdgesKillProcessAndReloadExactValuesAndRevisions() {
        val dbFile = newTempDbFile("stage04_d01")
        val projectId: String
        val edge1Id: String
        val edge2Id: String
        val expectedHash1: String
        val expectedHash2: String

        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            db.upsertTrack(sampleTrack("trk_a", "Alpha"))
            db.upsertTrack(sampleTrack("trk_b", "Bravo"))
            db.upsertTrack(sampleTrack("trk_c", "Charlie"))

            val proj = db.createProject("Friday Peak Hour Set")
            projectId = proj.projectId
            assertEquals(1L, proj.editRevision)

            val detailAfterAdd = db.addTracksToProject(
                projectId = projectId,
                expectedRevision = 1L,
                trackIds = listOf("trk_a", "trk_b", "trk_c")
            )
            assertEquals(2L, detailAfterAdd.project.editRevision)
            assertEquals(3, detailAfterAdd.occurrences.size)
            assertEquals(2, detailAfterAdd.edges.size)

            edge1Id = detailAfterAdd.edges[0].edge.edgeId
            edge2Id = detailAfterAdd.edges[1].edge.edgeId

            val planJson1 = buildCanonicalSegueJson(
                audioKeyA = "audiokey_trk_a",
                audioKeyB = "audiokey_trk_b",
                exitAnchorUs = 115_250_000L,
                entryAnchorUs = 12_500_000L,
                spanUs = 15_000_000L,
                ratePpmB = 1_015_000L,
                barCount = 8
            )
            val savedEdge1 = db.saveEdgeOverrideAndResolvedPlan(
                edgeId = edge1Id,
                expectedEdgePlanRevision = 1L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = edge1Id,
                    exitAnchorUs = 115_250_000L,
                    entryAnchorUs = 12_500_000L,
                    spanUs = 15_000_000L,
                    barCount = 8,
                    pulseMode = "locked",
                    plannedRatePpm = 1_015_000,
                    presetId = "eq_swap_8bar",
                    userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or
                        OverrideSnapshotEntity.BIT_ENTRY_ANCHOR or
                        OverrideSnapshotEntity.BIT_SPAN or
                        OverrideSnapshotEntity.BIT_PLANNED_RATE,
                    editTimestampUtc = 1_700_000_100_000L
                ),
                canonicalSegueJson = planJson1,
                outgoingFacts = SegueSideFacts("audiokey_trk_a", "grid_a", 180_000_000L),
                incomingFacts = SegueSideFacts("audiokey_trk_b", "grid_b", 180_000_000L)
            )
            expectedHash1 = savedEdge1.resolvedPlan!!.planHashSha256

            val planJson2 = buildCanonicalSegueJson(
                audioKeyA = "audiokey_trk_b",
                audioKeyB = "audiokey_trk_c",
                gridKeyA = "grid_b",
                gridKeyB = "grid_c",
                exitAnchorUs = 140_000_000L,
                entryAnchorUs = 4_000_000L,
                spanUs = 7_500_000L,
                ratePpmB = 992_000L,
                barCount = 4
            )
            val savedEdge2 = db.saveEdgeOverrideAndResolvedPlan(
                edgeId = edge2Id,
                expectedEdgePlanRevision = 1L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = edge2Id,
                    exitAnchorUs = 140_000_000L,
                    entryAnchorUs = 4_000_000L,
                    spanUs = 7_500_000L,
                    barCount = 4,
                    pulseMode = "locked",
                    plannedRatePpm = 992_000,
                    presetId = "quick_cut_4bar",
                    userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or OverrideSnapshotEntity.BIT_ENTRY_ANCHOR,
                    editTimestampUtc = 1_700_000_200_000L
                ),
                canonicalSegueJson = planJson2,
                outgoingFacts = SegueSideFacts("audiokey_trk_b", "grid_b", 180_000_000L),
                incomingFacts = SegueSideFacts("audiokey_trk_c", "grid_c", 180_000_000L)
            )
            expectedHash2 = savedEdge2.resolvedPlan!!.planHashSha256
        }

        // Simulate process restart by opening a fresh SetFlowProjectDatabase instance on dbFile
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { reloadedDb ->
            val reloaded = reloadedDb.getProjectDetail(projectId)
            assertNotNull(reloaded)
            assertEquals("Friday Peak Hour Set", reloaded!!.project.name)
            assertEquals(4L, reloaded.project.editRevision)
            assertEquals(listOf("trk_a", "trk_b", "trk_c"), reloaded.occurrences.map { it.occurrence.trackId })
            assertEquals(listOf(0, 1, 2), reloaded.occurrences.map { it.occurrence.positionIndex })

            val e1 = reloaded.edges[0]
            assertEquals(edge1Id, e1.edge.edgeId)
            assertEquals(EdgeOrigin.CUSTOM, e1.edge.origin)
            assertEquals(2L, e1.edge.planRevision)
            assertEquals(EdgeValidationStatus.VALID, e1.edge.lastValidationStatus)
            assertEquals(115_250_000L, e1.overridePlan!!.exitAnchorUs)
            assertEquals(12_500_000L, e1.overridePlan!!.entryAnchorUs)
            assertEquals(15_000_000L, e1.overridePlan!!.spanUs)
            assertEquals(1_015_000, e1.overridePlan!!.plannedRatePpm)
            assertEquals(expectedHash1, e1.resolvedPlan!!.planHashSha256)

            val e2 = reloaded.edges[1]
            assertEquals(edge2Id, e2.edge.edgeId)
            assertEquals(EdgeOrigin.CUSTOM, e2.edge.origin)
            assertEquals(2L, e2.edge.planRevision)
            assertEquals(140_000_000L, e2.overridePlan!!.exitAnchorUs)
            assertEquals(4_000_000L, e2.overridePlan!!.entryAnchorUs)
            assertEquals(7_500_000L, e2.overridePlan!!.spanUs)
            assertEquals(992_000, e2.overridePlan!!.plannedRatePpm)
            assertEquals(expectedHash2, e2.resolvedPlan!!.planHashSha256)
        }
    }

    @Test
    fun d02_sameTrackAppearsThreeTimesInOneSetEachEdgeRemainsDistinct() {
        val dbFile = newTempDbFile("stage04_d02")
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            db.upsertTrack(sampleTrack("trk_loop", "Tool Loop Track"))
            val proj = db.createProject("Same Track Triple Occurrence Set")
            val detail = db.addTracksToProject(
                projectId = proj.projectId,
                expectedRevision = proj.editRevision,
                trackIds = listOf("trk_loop", "trk_loop", "trk_loop")
            )

            assertEquals(3, detail.occurrences.size)
            val occIds = detail.occurrences.map { it.occurrence.occurrenceId }
            assertEquals(3, occIds.toSet().size) // All 3 occurrence IDs are distinct

            assertEquals(2, detail.edges.size)
            val e01 = detail.edges[0].edge
            val e12 = detail.edges[1].edge
            assertNotEquals(e01.edgeId, e12.edgeId)
            assertEquals(occIds[0], e01.outgoingOccurrenceId)
            assertEquals(occIds[1], e01.incomingOccurrenceId)
            assertEquals(occIds[1], e12.outgoingOccurrenceId)
            assertEquals(occIds[2], e12.incomingOccurrenceId)

            // Save distinct custom plans on e01 vs e12 even though both connect trk_loop -> trk_loop
            db.saveEdgeOverrideAndResolvedPlan(
                edgeId = e01.edgeId,
                expectedEdgePlanRevision = 1L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = e01.edgeId,
                    exitAnchorUs = 45_000_000L,
                    entryAnchorUs = 5_000_000L,
                    spanUs = 8_000_000L,
                    editTimestampUtc = 1000L
                ),
                canonicalSegueJson = buildCanonicalSegueJson(
                    audioKeyA = "audiokey_trk_loop",
                    audioKeyB = "audiokey_trk_loop",
                    exitAnchorUs = 45_000_000L,
                    entryAnchorUs = 5_000_000L
                )
            )
            db.saveEdgeOverrideAndResolvedPlan(
                edgeId = e12.edgeId,
                expectedEdgePlanRevision = 1L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = e12.edgeId,
                    exitAnchorUs = 95_000_000L,
                    entryAnchorUs = 15_000_000L,
                    spanUs = 8_000_000L,
                    editTimestampUtc = 2000L
                ),
                canonicalSegueJson = buildCanonicalSegueJson(
                    audioKeyA = "audiokey_trk_loop",
                    audioKeyB = "audiokey_trk_loop",
                    exitAnchorUs = 95_000_000L,
                    entryAnchorUs = 15_000_000L
                )
            )

            val afterSaves = db.getProjectDetail(proj.projectId)!!
            assertEquals(45_000_000L, afterSaves.edges[0].overridePlan!!.exitAnchorUs)
            assertEquals(95_000_000L, afterSaves.edges[1].overridePlan!!.exitAnchorUs)

            // Move occ2 to the front: [occ2, occ0, occ1]
            // Now (occ2 -> occ0) is a new pair (must be fresh AUTO), while (occ0 -> occ1) remained adjacent and keeps e01!
            val afterReorder = db.reorderOccurrences(
                projectId = proj.projectId,
                expectedRevision = afterSaves.project.editRevision,
                newOrderedOccurrenceIds = listOf(occIds[2], occIds[0], occIds[1])
            )
            assertEquals(2, afterReorder.edges.size)
            val newFirstEdge = afterReorder.edges[0]
            val survivingSecondEdge = afterReorder.edges[1]

            assertEquals(occIds[2], newFirstEdge.edge.outgoingOccurrenceId)
            assertEquals(occIds[0], newFirstEdge.edge.incomingOccurrenceId)
            assertEquals(EdgeOrigin.AUTO, newFirstEdge.edge.origin)
            assertNull("New pair (occ2->occ0) must not inherit override from e12", newFirstEdge.overridePlan)

            assertEquals(e01.edgeId, survivingSecondEdge.edge.edgeId)
            assertEquals(45_000_000L, survivingSecondEdge.overridePlan!!.exitAnchorUs)
        }
    }

    @Test
    fun d03_reorderWhileEditorOpenCausesOptimisticConflictAndPreventsWrongNeighborSave() {
        val dbFile = newTempDbFile("stage04_d03")
        val diagnostics = DiagnosticsRingBuffer(capacity = 32)
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile), diagnostics = diagnostics).use { db ->
            db.upsertTrack(sampleTrack("trk_a", "Track A"))
            db.upsertTrack(sampleTrack("trk_b", "Track B"))
            db.upsertTrack(sampleTrack("trk_c", "Track C"))

            val proj = db.createProject("Concurrency Conflict Set")
            val detail = db.addTracksToProject(proj.projectId, proj.editRevision, listOf("trk_a", "trk_b", "trk_c"))
            val occA = detail.occurrences[0].occurrence.occurrenceId
            val occB = detail.occurrences[1].occurrence.occurrenceId
            val occC = detail.occurrences[2].occurrence.occurrenceId
            val edgeAB = detail.edges[0].edge

            // Editor opens on edgeAB (A -> B) while project editRevision is 2
            val editorCapturedProjectRev = detail.project.editRevision
            val editorCapturedPlanRev = edgeAB.planRevision

            // Background/other screen reorders [A, B, C] -> [A, C, B]
            val reordered = db.reorderOccurrences(
                projectId = proj.projectId,
                expectedRevision = editorCapturedProjectRev,
                newOrderedOccurrenceIds = listOf(occA, occC, occB)
            )
            val newEdgeAC = reordered.edges[0].edge
            assertNotEquals(edgeAB.edgeId, newEdgeAC.edgeId)
            assertNull(reordered.edges[0].overridePlan)

            // 1. Stale editor attempts to save to old edgeAB (which was archived when B and C swapped)
            try {
                db.saveEdgeOverrideAndResolvedPlan(
                    edgeId = edgeAB.edgeId,
                    expectedEdgePlanRevision = editorCapturedPlanRev,
                    overrideSnapshot = OverrideSnapshotEntity(
                        edgeId = edgeAB.edgeId,
                        exitAnchorUs = 100_000_000L,
                        editTimestampUtc = 1234L
                    ),
                    canonicalSegueJson = buildCanonicalSegueJson()
                )
                fail("Expected OptimisticConcurrencyException when saving to archived edgeAB")
            } catch (expected: OptimisticConcurrencyException) {
                assertEquals("Edge", expected.entityType)
                assertEquals(edgeAB.edgeId, expected.entityId)
            }

            // 2. Stale editor attempts to save to newEdgeAC using stale expectedProjectRevision or wrong incomingOccurrenceId
            try {
                db.saveEdgeOverrideAndResolvedPlan(
                    edgeId = newEdgeAC.edgeId,
                    expectedEdgePlanRevision = 1L,
                    expectedProjectRevision = editorCapturedProjectRev,
                    expectedOutgoingOccurrenceId = occA,
                    expectedIncomingOccurrenceId = occB,
                    overrideSnapshot = OverrideSnapshotEntity(
                        edgeId = newEdgeAC.edgeId,
                        exitAnchorUs = 100_000_000L,
                        editTimestampUtc = 1234L
                    ),
                    canonicalSegueJson = buildCanonicalSegueJson()
                )
                fail("Expected OptimisticConcurrencyException when project revision / neighbor pair changed")
            } catch (expected: OptimisticConcurrencyException) {
                assertEquals("Project", expected.entityType)
            }

            // Verify newEdgeAC (A -> C) was NOT polluted by the rejected save
            val finalDetail = db.getProjectDetail(proj.projectId)!!
            assertNull(finalDetail.edges[0].overridePlan)
            assertEquals(EdgeOrigin.AUTO, finalDetail.edges[0].edge.origin)
            assertTrue(diagnostics.snapshot().any { it.category == "SetFlowDB" })
        }
    }

    @Test
    fun d04_reorderAndRemoveTrackThenUndoRestoresExactEdgeIdsAndSnapshots() {
        val dbFile = newTempDbFile("stage04_d04")
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            db.upsertTrack(sampleTrack("trk_a", "Track A"))
            db.upsertTrack(sampleTrack("trk_b", "Track B"))
            db.upsertTrack(sampleTrack("trk_c", "Track C"))

            val proj = db.createProject("Undo Restoration Set")
            val initial = db.addTracksToProject(proj.projectId, proj.editRevision, listOf("trk_a", "trk_b", "trk_c"))
            val occA = initial.occurrences[0].occurrence.occurrenceId
            val occB = initial.occurrences[1].occurrence.occurrenceId
            val occC = initial.occurrences[2].occurrence.occurrenceId
            val edgeABId = initial.edges[0].edge.edgeId
            val edgeBCId = initial.edges[1].edge.edgeId

            db.saveEdgeOverrideAndResolvedPlan(
                edgeId = edgeABId,
                expectedEdgePlanRevision = 1L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = edgeABId,
                    exitAnchorUs = 111_000_000L,
                    entryAnchorUs = 11_000_000L,
                    spanUs = 8_000_000L,
                    editTimestampUtc = 1000L
                ),
                canonicalSegueJson = buildCanonicalSegueJson(exitAnchorUs = 111_000_000L, entryAnchorUs = 11_000_000L)
            )
            db.saveEdgeOverrideAndResolvedPlan(
                edgeId = edgeBCId,
                expectedEdgePlanRevision = 1L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = edgeBCId,
                    exitAnchorUs = 122_000_000L,
                    entryAnchorUs = 22_000_000L,
                    spanUs = 8_000_000L,
                    editTimestampUtc = 2000L
                ),
                canonicalSegueJson = buildCanonicalSegueJson(exitAnchorUs = 122_000_000L, entryAnchorUs = 22_000_000L)
            )

            val committedBeforeReorder = db.getProjectDetail(proj.projectId)!!

            // 1. Reorder [A, B, C] -> [A, C, B]
            val afterReorder = db.reorderOccurrences(
                projectId = proj.projectId,
                expectedRevision = committedBeforeReorder.project.editRevision,
                newOrderedOccurrenceIds = listOf(occA, occC, occB)
            )
            assertEquals(listOf(occA, occC, occB), afterReorder.occurrences.map { it.occurrence.occurrenceId })
            assertTrue("Old overrides must not apply to A->C or C->B", afterReorder.edges.all { it.overridePlan == null })

            // 2. Undo reorder -> must restore [A, B, C] with exact edgeABId and edgeBCId and their snapshots
            val afterUndoReorder = db.undoLastStructuralChange(proj.projectId, afterReorder.project.editRevision)
            assertEquals(listOf(occA, occB, occC), afterUndoReorder.occurrences.map { it.occurrence.occurrenceId })
            assertEquals(edgeABId, afterUndoReorder.edges[0].edge.edgeId)
            assertEquals(edgeBCId, afterUndoReorder.edges[1].edge.edgeId)
            assertEquals(111_000_000L, afterUndoReorder.edges[0].overridePlan!!.exitAnchorUs)
            assertEquals(122_000_000L, afterUndoReorder.edges[1].overridePlan!!.exitAnchorUs)

            // 3. Remove middle occurrence B -> leaves [A, C] with 1 fresh AUTO edge
            val afterRemoveB = db.removeOccurrence(
                projectId = proj.projectId,
                expectedRevision = afterUndoReorder.project.editRevision,
                occurrenceId = occB
            )
            assertEquals(listOf(occA, occC), afterRemoveB.occurrences.map { it.occurrence.occurrenceId })
            assertEquals(1, afterRemoveB.edges.size)
            assertNull(afterRemoveB.edges[0].overridePlan)

            // 4. Undo removal of B -> must restore [A, B, C] with exact edgeABId and edgeBCId and their snapshots
            val afterUndoRemove = db.undoLastStructuralChange(proj.projectId, afterRemoveB.project.editRevision)
            assertEquals(listOf(occA, occB, occC), afterUndoRemove.occurrences.map { it.occurrence.occurrenceId })
            assertEquals(edgeABId, afterUndoRemove.edges[0].edge.edgeId)
            assertEquals(edgeBCId, afterUndoRemove.edges[1].edge.edgeId)
            assertEquals(111_000_000L, afterUndoRemove.edges[0].overridePlan!!.exitAnchorUs)
            assertEquals(122_000_000L, afterUndoRemove.edges[1].overridePlan!!.exitAnchorUs)
            assertEquals(
                committedBeforeReorder.edges[0].resolvedPlan!!.planHashSha256,
                afterUndoRemove.edges[0].resolvedPlan!!.planHashSha256
            )
            assertEquals(
                committedBeforeReorder.edges[1].resolvedPlan!!.planHashSha256,
                afterUndoRemove.edges[1].resolvedPlan!!.planHashSha256
            )
        }
    }

    @Test
    fun d05_and_s01_forceFailure100TimesHalfwayThroughMultiTableWritesRollsBackCleanly() {
        val dbFile = newTempDbFile("stage04_d05_s01")
        val projectId: String
        val edgeId: String
        val baselineHash: String
        val baselineProjectRev: Long
        val baselineEdgePlanRev: Long
        val occIds: List<String>

        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            db.upsertTrack(sampleTrack("trk_1", "One"))
            db.upsertTrack(sampleTrack("trk_2", "Two"))
            db.upsertTrack(sampleTrack("trk_3", "Three"))

            val proj = db.createProject("100x Crash Rollback Harness")
            projectId = proj.projectId
            val detail = db.addTracksToProject(projectId, proj.editRevision, listOf("trk_1", "trk_2", "trk_3"))
            occIds = detail.occurrences.map { it.occurrence.occurrenceId }
            edgeId = detail.edges[0].edge.edgeId

            val initialSave = db.saveEdgeOverrideAndResolvedPlan(
                edgeId = edgeId,
                expectedEdgePlanRevision = 1L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = edgeId,
                    exitAnchorUs = 100_000_000L,
                    entryAnchorUs = 10_000_000L,
                    spanUs = 8_000_000L,
                    editTimestampUtc = 1000L
                ),
                canonicalSegueJson = buildCanonicalSegueJson(
                    audioKeyA = "audiokey_trk_1",
                    audioKeyB = "audiokey_trk_2",
                    exitAnchorUs = 100_000_000L,
                    entryAnchorUs = 10_000_000L
                )
            )
            baselineHash = initialSave.resolvedPlan!!.planHashSha256
            baselineEdgePlanRev = initialSave.edge.planRevision
            baselineProjectRev = db.getProject(projectId)!!.editRevision

            // Execute 100 deliberate mid-transaction interruptions across plan saves and reorders
            for (iteration in 1..100) {
                try {
                    if (iteration % 2 == 1) {
                        db.saveEdgeOverrideAndResolvedPlan(
                            edgeId = edgeId,
                            expectedEdgePlanRevision = baselineEdgePlanRev,
                            overrideSnapshot = OverrideSnapshotEntity(
                                edgeId = edgeId,
                                exitAnchorUs = 100_000_000L + iteration * 1_000L,
                                entryAnchorUs = 10_000_000L,
                                spanUs = 8_000_000L,
                                editTimestampUtc = 2000L + iteration
                            ),
                            canonicalSegueJson = buildCanonicalSegueJson(
                                audioKeyA = "audiokey_trk_1",
                                audioKeyB = "audiokey_trk_2",
                                exitAnchorUs = 100_000_000L + iteration * 1_000L,
                                entryAnchorUs = 10_000_000L
                            ),
                            faultInjectorBeforeCommit = {
                                throw RuntimeException("Simulated process death mid-save #$iteration")
                            }
                        )
                    } else {
                        db.reorderOccurrences(
                            projectId = projectId,
                            expectedRevision = baselineProjectRev,
                            newOrderedOccurrenceIds = listOf(occIds[2], occIds[0], occIds[1]),
                            faultInjectorBeforeCommit = {
                                throw RuntimeException("Simulated process death mid-reorder #$iteration")
                            }
                        )
                    }
                    fail("Iteration $iteration must have thrown simulated crash exception")
                } catch (expected: RuntimeException) {
                    assertTrue(expected.message!!.contains("Simulated process death"))
                }
            }
        }

        // Reopen DB from disk and verify 100% rollback to baseline revision and hash
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { reloadedDb ->
            val snap = reloadedDb.getProjectDetail(projectId)!!
            assertEquals(baselineProjectRev, snap.project.editRevision)
            assertEquals(occIds, snap.occurrences.map { it.occurrence.occurrenceId })
            assertEquals(listOf(0, 1, 2), snap.occurrences.map { it.occurrence.positionIndex })

            val e0 = snap.edges[0]
            assertEquals(edgeId, e0.edge.edgeId)
            assertEquals(baselineEdgePlanRev, e0.edge.planRevision)
            assertEquals(100_000_000L, e0.overridePlan!!.exitAnchorUs)
            assertEquals(baselineHash, e0.resolvedPlan!!.planHashSha256)
        }
    }

    @Test
    fun d06_forwardSchemaMigrationsV1ToV2AndV2ToV3PreserveAllCommittedProjectData() {
        val dbFile = newTempDbFile("stage04_d06_migrations")

        // Step 1: Create schema v1 database and insert v1 records directly
        JdbcSqliteDriver(dbFile, targetSchemaVersion = 1).use { v1Driver ->
            assertEquals(1, v1Driver.schemaVersion)
            assertEquals(DatabaseOpenMode.READ_WRITE, v1Driver.openMode)
            v1Driver.writeTransaction { s ->
                s.execSql(
                    """
                    INSERT INTO projects (projectId, schemaVersion, name, createdAtUtc, updatedAtUtc, editRevision, settingsSnapshot, deletedAt)
                    VALUES ('proj_v1', 1, 'Legacy V1 Set', 1000, 2000, 5, '{"snap":1}', NULL)
                    """.trimIndent()
                )
                s.execSql(
                    """
                    INSERT INTO tracks (
                        trackId, displayName, title, artist, album, durationUs, contentUri, providerAuthority,
                        mime, codecDescription, sampleRateHz, channelCount, bitsPerSample, size, modifiedTime,
                        mediaStoreId, grantStatus, contentFingerprint, audioFingerprint, fingerprintVersion,
                        cachedArtworkKey, lastVerified, availabilityState
                    ) VALUES (
                        'trk_v1_a', 'a.wav', 'Track V1 A', 'Artist', 'Album', 180000000, 'content://test/a.wav', 'test',
                        'audio/wav', 'WAV', 44100, 2, 16, 10000, 1000, NULL, 'PERSISTED', 'fp_a', 'ak_a', 1, NULL, 1000, 'AVAILABLE'
                    )
                    """.trimIndent()
                )
                s.execSql(
                    """
                    INSERT INTO tracks (
                        trackId, displayName, title, artist, album, durationUs, contentUri, providerAuthority,
                        mime, codecDescription, sampleRateHz, channelCount, bitsPerSample, size, modifiedTime,
                        mediaStoreId, grantStatus, contentFingerprint, audioFingerprint, fingerprintVersion,
                        cachedArtworkKey, lastVerified, availabilityState
                    ) VALUES (
                        'trk_v1_b', 'b.wav', 'Track V1 B', 'Artist', 'Album', 180000000, 'content://test/b.wav', 'test',
                        'audio/wav', 'WAV', 44100, 2, 16, 10000, 1000, NULL, 'PERSISTED', 'fp_b', 'ak_b', 1, NULL, 1000, 'AVAILABLE'
                    )
                    """.trimIndent()
                )
                s.execSql(
                    "INSERT INTO occurrences (occurrenceId, projectId, trackId, positionIndex, playbackGainDb, sourceRangeStartUs, sourceRangeEndUs) VALUES ('occ_1', 'proj_v1', 'trk_v1_a', 0, -1.5, NULL, NULL)"
                )
                s.execSql(
                    "INSERT INTO occurrences (occurrenceId, projectId, trackId, positionIndex, playbackGainDb, sourceRangeStartUs, sourceRangeEndUs) VALUES ('occ_2', 'proj_v1', 'trk_v1_b', 1, 0.0, NULL, NULL)"
                )
                s.execSql(
                    "INSERT INTO edges (edgeId, projectId, outgoingOccurrenceId, incomingOccurrenceId, relationVersion, planRevision, origin, lastResolvedFingerprint, lastValidationStatus) VALUES ('edge_v1', 'proj_v1', 'occ_1', 'occ_2', 1, 3, 'CUSTOM', 'hash_v1', 'VALID')"
                )
                // Note: in v1 schema, override_snapshots does NOT have presetId or userEditedFieldsBitset
                s.execSql(
                    """
                    INSERT INTO override_snapshots (
                        edgeId, overrideSchema, revision, exitAnchorUs, entryAnchorUs, spanUs,
                        barCount, pulseMode, plannedRatePpm, laneOverridesJson,
                        correctedGridVersionA, correctedGridVersionB, editTimestampUtc
                    ) VALUES ('edge_v1', 1, 3, 125000000, 10000000, 8000000, 4, 'locked', 1000000, NULL, NULL, NULL, 1500)
                    """.trimIndent()
                )
            }
        }

        // Step 2: Migrate v1 -> v2
        JdbcSqliteDriver(dbFile, targetSchemaVersion = 2).use { v2Driver ->
            assertEquals(2, v2Driver.schemaVersion)
            assertEquals(DatabaseOpenMode.READ_WRITE, v2Driver.openMode)
            v2Driver.writeTransaction { s ->
                s.execSql(
                    """
                    INSERT INTO beat_grid_corrections (
                        trackId, revision, bpmOverride, phaseAnchorUs, beatsPerBar,
                        regionStartUs, regionEndUs, confidenceOverride, createdAtUtc
                    ) VALUES ('trk_v1_a', 1, 128.0, 250000, 4, NULL, NULL, 0.95, 2500)
                    """.trimIndent()
                )
            }
        }

        // Step 3: Migrate v2 -> v3 and verify via SetFlowProjectDatabase
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile, targetSchemaVersion = 3)).use { db ->
            assertEquals(3, db.schemaVersion)
            assertEquals(DatabaseOpenMode.READ_WRITE, db.openMode)

            val detail = db.getProjectDetail("proj_v1")!!
            assertEquals("Legacy V1 Set", detail.project.name)
            assertEquals(5L, detail.project.editRevision)
            assertEquals(2, detail.occurrences.size)
            assertEquals(-1.5, detail.occurrences[0].occurrence.playbackGainDb!!, 1e-6)

            val edge = detail.edges[0]
            assertEquals("edge_v1", edge.edge.edgeId)
            assertEquals(3L, edge.edge.planRevision)
            assertEquals(125_000_000L, edge.overridePlan!!.exitAnchorUs)
            assertNull(edge.overridePlan!!.presetId)
            assertEquals(0, edge.overridePlan!!.userEditedFieldsBitset)

            val gridCorr = db.getBeatGridCorrection("trk_v1_a")!!
            assertEquals(128.0, gridCorr.bpmOverride!!, 1e-6)
            assertEquals(0.95, gridCorr.confidenceOverride!!, 1e-6)

            // Verify export_jobs table also works in v3
            db.saveExportJob(
                ExportJobEntity(
                    jobId = "job_01",
                    projectId = "proj_v1",
                    projectRevision = 5L,
                    format = "WAV_PCM_16",
                    destinationUri = "content://exports/set.wav",
                    status = "QUEUED",
                    progress = 0.0,
                    plannedDurationFrames = 44100L * 300L,
                    createdAt = 3000L
                )
            )
            assertEquals("QUEUED", db.getExportJob("job_01")!!.status)
        }
    }

    @Test
    fun d07_and_d09_projectBackupRoundtripDuplicateSafeReimportMissingTrackRelinkAndUriStripping() {
        val dbFile1 = newTempDbFile("stage04_d07_src")
        val exportedJson: String
        val originalProjectId: String

        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile1)).use { db ->
            db.upsertTrack(sampleTrack("trk_a", "Subterranean"))
            db.upsertTrack(sampleTrack("trk_b", "Horizon"))
            db.saveBeatGridCorrection(
                BeatGridCorrectionEntity(
                    trackId = "trk_a",
                    revision = 2L,
                    bpmOverride = 126.5,
                    phaseAnchorUs = 100_000L,
                    beatsPerBar = 4,
                    confidenceOverride = 0.9,
                    createdAtUtc = 5000L
                )
            )

            val proj = db.createProject("Backup Export Set")
            originalProjectId = proj.projectId
            val detail = db.addTracksToProject(originalProjectId, proj.editRevision, listOf("trk_a", "trk_b"))
            val edgeId = detail.edges[0].edge.edgeId

            db.saveEdgeOverrideAndResolvedPlan(
                edgeId = edgeId,
                expectedEdgePlanRevision = 1L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = edgeId,
                    exitAnchorUs = 130_000_000L,
                    entryAnchorUs = 8_000_000L,
                    spanUs = 16_000_000L,
                    barCount = 8,
                    pulseMode = "locked",
                    plannedRatePpm = 1_005_000,
                    presetId = "long_blend",
                    userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or OverrideSnapshotEntity.BIT_SPAN,
                    editTimestampUtc = 6000L
                ),
                canonicalSegueJson = buildCanonicalSegueJson(
                    audioKeyA = "audiokey_trk_a",
                    audioKeyB = "audiokey_trk_b",
                    exitAnchorUs = 130_000_000L,
                    entryAnchorUs = 8_000_000L,
                    spanUs = 16_000_000L,
                    ratePpmB = 1_005_000L,
                    barCount = 8
                )
            )

            val backupManager = ProjectBackupManager(db)
            val exportRes = backupManager.exportProjectBackup(
                projectId = originalProjectId,
                destinationLabelUri = "content://com.android.externalstorage.documents/document/primary%3ABackups%2Fset.json",
                extraOptionalFields = mapOf(
                    "futureExtension" to JsonObject(mapOf("experimentalFlag" to JsonPrimitive("preserve-me")))
                )
            )
            exportedJson = exportRes.jsonText

            // S30: Verify no private content:// or /storage/ URIs leaked into the shareable backup JSON
            assertFalse("Shareable backup must not contain content:// URIs", exportedJson.contains("content://"))
            assertFalse("Shareable backup must not contain primary%3AMusic", exportedJson.contains("primary%3AMusic"))

            // D07: Import the backup into the SAME database where originalProjectId already exists -> duplicate-safe ID remap!
            val duplicateImport = backupManager.importProjectBackup(exportedJson)
            assertTrue(duplicateImport.wasRemappedForDuplicateId)
            assertEquals(originalProjectId, duplicateImport.originalProjectId)
            assertNotEquals(originalProjectId, duplicateImport.importedProjectDetail.project.projectId)
            assertTrue("Tracks already exist locally so relink list must be empty", duplicateImport.tracksRequiringRelink.isEmpty())
            assertTrue(duplicateImport.preservedUnknownFields.containsKey("futureExtension"))

            // Import a SECOND time into the same DB -> must also succeed with another distinct projectId
            val secondImport = backupManager.importProjectBackup(exportedJson)
            assertTrue(secondImport.wasRemappedForDuplicateId)
            assertNotEquals(
                duplicateImport.importedProjectDetail.project.projectId,
                secondImport.importedProjectDetail.project.projectId
            )
            assertEquals(3, db.listProjects().size)
        }

        // D09 & ACC-27: Import the backup into a brand-new database where the audio tracks are MISSING locally
        val dbFile2 = newTempDbFile("stage04_d09_missing_tracks")
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile2)).use { freshDb ->
            val backupManager2 = ProjectBackupManager(freshDb)
            val missingImport = backupManager2.importProjectBackup(exportedJson)

            assertFalse(missingImport.wasRemappedForDuplicateId)
            assertEquals(originalProjectId, missingImport.importedProjectDetail.project.projectId)
            assertEquals(2, missingImport.tracksRequiringRelink.size)

            val importedOccs = missingImport.importedProjectDetail.occurrences
            assertEquals(2, importedOccs.size)
            assertTrue(
                importedOccs.all { it.track.availabilityState == TrackAvailabilityState.MISSING_OR_REVOKED.name }
            )
            assertEquals(130_000_000L, missingImport.importedProjectDetail.edges[0].overridePlan!!.exitAnchorUs)
            assertEquals(126.5, freshDb.getBeatGridCorrection("trk_a")!!.bpmOverride!!, 1e-6)
        }
    }

    @Test
    fun d08_unknownFutureSchemaVersion99OpensInReadOnlyRecoveryModeWithoutDeletingUserData() {
        val dbFile = newTempDbFile("stage04_d08_future_schema")
        val projectId: String

        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            val proj = db.createProject("Precious User Set")
            projectId = proj.projectId
        }

        // Bump SQLite user_version to 99 to simulate opening a database created by a newer app version
        java.sql.DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA user_version = 99")
            }
        }

        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { recoveryDb ->
            assertEquals(DatabaseOpenMode.READ_ONLY_UNSUPPORTED_SCHEMA, recoveryDb.openMode)
            assertEquals(99, recoveryDb.schemaVersion)
            assertNotNull(recoveryDb.readOnlyReason)

            // Writes must be rejected with ReadOnlyUnsupportedSchemaException (never destructive wipe!)
            try {
                recoveryDb.createProject("Should Fail In Read-Only Mode")
                fail("Expected ReadOnlyUnsupportedSchemaException")
            } catch (expected: ReadOnlyUnsupportedSchemaException) {
                assertEquals(99, expected.foundVersion)
            }

            // Read-only recovery export must succeed and preserve the user's project
            val recoveryJson = recoveryDb.exportReadOnlyRecoveryJson()
            assertTrue(recoveryJson.contains("Precious User Set"))
            assertTrue(recoveryJson.contains(projectId))
        }
    }

    @Test
    fun d10_backgroundAutoPlanReanalysisInvalidatesResolvedStatusButNeverErasesUserOverride() {
        val dbFile = newTempDbFile("stage04_d10_reanalysis")
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            db.upsertTrack(sampleTrack("trk_a", "Track A"))
            db.upsertTrack(sampleTrack("trk_b", "Track B"))
            val proj = db.createProject("Reanalysis Override Protection Set")
            val detail = db.addTracksToProject(proj.projectId, proj.editRevision, listOf("trk_a", "trk_b"))
            val edgeId = detail.edges[0].edge.edgeId

            // Save initial AutoPlan
            val initialAutoJson = buildCanonicalSegueJson(
                audioKeyA = "audiokey_trk_a",
                audioKeyB = "audiokey_trk_b",
                exitAnchorUs = 120_000_000L,
                entryAnchorUs = 16_000_000L
            )
            db.saveAutoPlanSnapshot(
                edgeId = edgeId,
                autoPlan = AutoPlanSnapshotEntity(
                    edgeId = edgeId,
                    engineVersion = "1.0",
                    analyzerVersion = "1.0",
                    parametersJson = "{}",
                    analysisKeyA = "audiokey_trk_a",
                    analysisKeyB = "audiokey_trk_b",
                    planCanonicalJson = initialAutoJson,
                    generatedAtUtc = 1000L
                ),
                resolvedCanonicalJson = initialAutoJson
            )

            // User saves custom override on edgeId
            db.saveEdgeOverrideAndResolvedPlan(
                edgeId = edgeId,
                expectedEdgePlanRevision = 2L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = edgeId,
                    exitAnchorUs = 133_333_000L,
                    entryAnchorUs = 9_999_000L,
                    spanUs = 12_000_000L,
                    presetId = "custom_eq_blend",
                    userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR or OverrideSnapshotEntity.BIT_ENTRY_ANCHOR,
                    editTimestampUtc = 2000L
                ),
                canonicalSegueJson = buildCanonicalSegueJson(
                    audioKeyA = "audiokey_trk_a",
                    audioKeyB = "audiokey_trk_b",
                    exitAnchorUs = 133_333_000L,
                    entryAnchorUs = 9_999_000L,
                    spanUs = 12_000_000L
                )
            )

            // 1. Background reanalysis with updated analyzer version (same audio keys)
            val afterAnalyzerUpdate = db.recordBackgroundAutoPlanReanalysis(
                edgeId = edgeId,
                newAutoPlan = AutoPlanSnapshotEntity(
                    edgeId = edgeId,
                    engineVersion = "1.1",
                    analyzerVersion = "1.1",
                    parametersJson = "{}",
                    analysisKeyA = "audiokey_trk_a",
                    analysisKeyB = "audiokey_trk_b",
                    planCanonicalJson = buildCanonicalSegueJson(
                        audioKeyA = "audiokey_trk_a",
                        audioKeyB = "audiokey_trk_b",
                        exitAnchorUs = 125_000_000L,
                        entryAnchorUs = 14_000_000L
                    ),
                    generatedAtUtc = 3000L
                )
            )
            assertEquals(EdgeValidationStatus.STALE_ANALYSIS, afterAnalyzerUpdate.edge.lastValidationStatus)
            assertNotNull("User override must NEVER be deleted by reanalysis", afterAnalyzerUpdate.overridePlan)
            assertEquals(133_333_000L, afterAnalyzerUpdate.overridePlan!!.exitAnchorUs)
            assertEquals(9_999_000L, afterAnalyzerUpdate.overridePlan!!.entryAnchorUs)
            assertEquals("custom_eq_blend", afterAnalyzerUpdate.overridePlan!!.presetId)

            // 2. Background reanalysis where audioKeyA changed
            val afterAudioKeyChange = db.recordBackgroundAutoPlanReanalysis(
                edgeId = edgeId,
                newAutoPlan = AutoPlanSnapshotEntity(
                    edgeId = edgeId,
                    engineVersion = "1.1",
                    analyzerVersion = "1.1",
                    parametersJson = "{}",
                    analysisKeyA = "audiokey_trk_a_remastered",
                    analysisKeyB = "audiokey_trk_b",
                    planCanonicalJson = buildCanonicalSegueJson(
                        audioKeyA = "audiokey_trk_a_remastered",
                        audioKeyB = "audiokey_trk_b",
                        exitAnchorUs = 125_000_000L,
                        entryAnchorUs = 14_000_000L
                    ),
                    generatedAtUtc = 4000L
                )
            )
            assertEquals(EdgeValidationStatus.STALE_AUDIO_KEY, afterAudioKeyChange.edge.lastValidationStatus)
            assertNotNull("User override must survive audio key change for review", afterAudioKeyChange.overridePlan)
            assertEquals(133_333_000L, afterAudioKeyChange.overridePlan!!.exitAnchorUs)
        }
    }

    @Test
    fun d11_and_s16_s17_rejectsNegativeTimestampsOver24hMalformedPlans10kKnotsAnd100MbBackupBomb() {
        val dbFile = newTempDbFile("stage04_d11_s16_s17")
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            db.upsertTrack(sampleTrack("trk_a", "Track A"))
            db.upsertTrack(sampleTrack("trk_b", "Track B"))
            val proj = db.createProject("Bounds Validation Set")
            val detail = db.addTracksToProject(proj.projectId, proj.editRevision, listOf("trk_a", "trk_b"))
            val edgeId = detail.edges[0].edge.edgeId

            // 1. Reject >24h track duration
            try {
                db.upsertTrack(sampleTrack("trk_huge", "Huge", durationUs = SetFlowProjectDatabase.MAX_DURATION_US + 1L))
                fail("Expected >24h track duration to be rejected")
            } catch (_: InvalidPlanPersistenceException) {
            }

            // 2. Reject negative exitAnchorUs in override
            try {
                db.saveEdgeOverrideAndResolvedPlan(
                    edgeId = edgeId,
                    expectedEdgePlanRevision = 1L,
                    overrideSnapshot = OverrideSnapshotEntity(
                        edgeId = edgeId,
                        exitAnchorUs = -500L,
                        editTimestampUtc = 1000L
                    ),
                    canonicalSegueJson = buildCanonicalSegueJson()
                )
                fail("Expected negative exitAnchorUs to be rejected")
            } catch (_: InvalidPlanPersistenceException) {
            }

            // 3. Reject >24h spanUs in override
            try {
                db.saveEdgeOverrideAndResolvedPlan(
                    edgeId = edgeId,
                    expectedEdgePlanRevision = 1L,
                    overrideSnapshot = OverrideSnapshotEntity(
                        edgeId = edgeId,
                        spanUs = SetFlowProjectDatabase.MAX_DURATION_US + 100L,
                        editTimestampUtc = 1000L
                    ),
                    canonicalSegueJson = buildCanonicalSegueJson()
                )
                fail("Expected >24h spanUs to be rejected")
            } catch (_: InvalidPlanPersistenceException) {
            }

            // 4. Reject 10,000-knot lane bomb (S17)
            val tenThousandKnotPlan = buildCanonicalSegueJson(knotsCount = 10_000)
            try {
                db.saveEdgeOverrideAndResolvedPlan(
                    edgeId = edgeId,
                    expectedEdgePlanRevision = 1L,
                    overrideSnapshot = OverrideSnapshotEntity(edgeId = edgeId, editTimestampUtc = 1000L),
                    canonicalSegueJson = tenThousandKnotPlan
                )
                fail("Expected 10k-knot plan to be rejected")
            } catch (e: InvalidPlanPersistenceException) {
                assertTrue(e.violations.any { it.contains("LANE_KNOTS_EXCEED_MAX") })
            }

            // 5. Reject SeguePlan whose window extends past outgoing track duration (SegueValidator.against)
            try {
                db.saveEdgeOverrideAndResolvedPlan(
                    edgeId = edgeId,
                    expectedEdgePlanRevision = 1L,
                    overrideSnapshot = OverrideSnapshotEntity(edgeId = edgeId, editTimestampUtc = 1000L),
                    canonicalSegueJson = buildCanonicalSegueJson(exitAnchorUs = 178_000_000L, spanUs = 10_000_000L),
                    outgoingFacts = SegueSideFacts("audiokey_trk_a", "grid_a", 180_000_000L),
                    incomingFacts = SegueSideFacts("audiokey_trk_b", "grid_b", 180_000_000L)
                )
                fail("Expected WINDOW_PAST_OUTGOING violation")
            } catch (e: InvalidPlanPersistenceException) {
                assertTrue(e.violations.contains("WINDOW_PAST_OUTGOING"))
            }

            // 6. Reject 100 MiB backup stream bomb without OOM (S17)
            val backupManager = ProjectBackupManager(db)
            val endlessJsonStream = object : InputStream() {
                override fun read(): Int = '{'.code
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    b.fill('{'.code.toByte(), off, off + len)
                    return len
                }
            }
            try {
                backupManager.importProjectBackupStream(endlessJsonStream)
                fail("Expected >10MiB stream bomb to be rejected")
            } catch (e: InvalidPlanPersistenceException) {
                assertTrue(e.message.contains("exceeds maximum"))
            }
        }
    }

    @Test
    fun d12_previewEditorDraftAndSavedPlanDifferBeforeSaveAndCancelRestoresCommitted() {
        val dbFile = newTempDbFile("stage04_d12_draft")
        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            db.upsertTrack(sampleTrack("trk_a", "Track A"))
            db.upsertTrack(sampleTrack("trk_b", "Track B"))
            val proj = db.createProject("Draft vs Committed Set")
            val detail = db.addTracksToProject(proj.projectId, proj.editRevision, listOf("trk_a", "trk_b"))
            val edgeId = detail.edges[0].edge.edgeId

            val autoJson = buildCanonicalSegueJson(
                exitAnchorUs = 120_000_000L,
                entryAnchorUs = 16_000_000L,
                spanUs = 8_000_000L,
                ratePpmB = 1_000_000L,
                barCount = 4
            )
            db.saveAutoPlanSnapshot(
                edgeId = edgeId,
                autoPlan = AutoPlanSnapshotEntity(
                    edgeId = edgeId,
                    engineVersion = "1.0",
                    analyzerVersion = "1.0",
                    parametersJson = "{}",
                    analysisKeyA = "audiokey_trk_a",
                    analysisKeyB = "audiokey_trk_b",
                    planCanonicalJson = autoJson,
                    generatedAtUtc = 1000L
                ),
                resolvedCanonicalJson = autoJson
            )

            // Save an override that ONLY edits exitAnchorUs (leaving entryAnchorUs, spanUs, barCount from AutoPlan)
            db.saveEdgeOverrideAndResolvedPlan(
                edgeId = edgeId,
                expectedEdgePlanRevision = 2L,
                overrideSnapshot = OverrideSnapshotEntity(
                    edgeId = edgeId,
                    exitAnchorUs = 135_000_000L,
                    userEditedFieldsBitset = OverrideSnapshotEntity.BIT_EXIT_ANCHOR,
                    editTimestampUtc = 2000L
                ),
                canonicalSegueJson = buildCanonicalSegueJson(
                    exitAnchorUs = 135_000_000L,
                    entryAnchorUs = 16_000_000L,
                    spanUs = 8_000_000L
                )
            )

            // Open editor and derive draft: exitAnchorUs comes from Override, unedited fields come from AutoPlan
            val initialDraft = db.deriveEditorDraft(edgeId)
            assertEquals(135_000_000L, initialDraft.exitAnchorUs)
            assertEquals(16_000_000L, initialDraft.entryAnchorUs)
            assertEquals(8_000_000L, initialDraft.spanUs)
            assertEquals(4, initialDraft.barCount)

            // User tweaks draft in memory before Save
            val unsavedDraft = initialDraft.copy(
                exitAnchorUs = 150_000_000L,
                spanUs = 16_000_000L
            )
            assertNotEquals(unsavedDraft.exitAnchorUs, db.getProjectDetail(proj.projectId)!!.edges[0].overridePlan!!.exitAnchorUs)

            // User hits Cancel (discards unsavedDraft without calling saveEdgeOverrideAndResolvedPlan)
            val reloadedDraftAfterCancel = db.deriveEditorDraft(edgeId)
            assertEquals(135_000_000L, reloadedDraftAfterCancel.exitAnchorUs)
            assertEquals(8_000_000L, reloadedDraftAfterCancel.spanUs)
        }

        // Also verify SetFlowShellController persisted set & occurrence actions (Task 8)
        val tempDir = Files.createTempDirectory("stage04_shell_ctrl").toFile()
        tempDir.deleteOnExit()
        val services = LocalOnlyShellServiceGraph(storageDirectory = tempDir)
        val controller = SetFlowShellController(services)
        assertEquals(1, controller.uiState.value.sets.size)
        val created = controller.createNewSet("Club Warmup Set")
        assertEquals(2, controller.uiState.value.sets.size)
        controller.openSetDetail(created.project.projectId)
        assertEquals("Club Warmup Set", controller.uiState.value.activeProjectDetail?.project?.name)
    }
}
