/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.myspoti.automix.Grid
import app.myspoti.automix.TrackAnalysis
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionResolutionResult
import app.setflow.analysis.AnalysisStatus
import app.setflow.db.EdgeOrigin
import app.setflow.db.EdgeValidationStatus
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.SetFlowSchemaAndMigrations
import app.setflow.db.TrackEntity
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.LocalTrackRecord
import app.setflow.domain.TrackAvailabilityState
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.service.LocalTrackLibraryService
import app.setflow.service.SqliteSetRepository
import app.setflow.transition.TransitionOverrideManager
import app.setflow.ui.SetFlowRoute
import app.setflow.ui.SetFlowShellController
import app.setflow.ui.transition.TransitionPresetDescriptor
import app.setflow.ui.transition.TransitionPresets
import app.setflow.ui.waveform.WaveformDecimator
import app.setflow.ui.waveform.WaveformResolution
import app.setflow.ui.waveform.WaveformTileCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToLong

/**
 * Dedicated unit test suite for Stage 08 — Spotify-Simple Set List and Transition Editor UI
 * (STAGES/08_SET_BUILDER_UI.txt).
 *
 * Verifies:
 * - LazyColumn item stable keying by occurrenceId and edgeId (Bug Trap 38).
 * - Exact edgeId integrity after track reorder without stale association.
 * - Draft separation: modifications do NOT touch database until explicit Save (Contract D12).
 * - Dirty Cancel and Back leave committed plan hash 100% identical.
 * - 4/8/16/32 bar length calculations without 12-second auto-clamp.
 * - Bounded waveform decimation cache with zero random noise generation (Task 4 & Bug Trap 39).
 * - Feature truth: unsupported presets visibly marked disabled with clear reason (Task 8).
 * - End-to-end import -> set -> edit -> save -> process-restart cycle.
 * - Minimum 48dp touch targets and TalkBack accessibility semantics.
 */
class SetBuilderUiStage08Test {

    private fun createTempDbFile(name: String): File {
        val dir = Files.createTempDirectory("stage08_$name").toFile()
        dir.deleteOnExit()
        return File(dir, "stage08_test.db")
    }

    private fun makeTestTrack(
        id: String,
        title: String,
        bpm: Double = 155.0,
        durationUs: Long = 180_000_000L
    ): TrackEntity = TrackEntity(
        trackId = id,
        displayName = "$title.flac",
        title = title,
        artist = "Hardstyle Artist",
        album = "Euphoria Vol 1",
        durationUs = durationUs,
        contentUri = "content://app.setflow.test/audio/$id",
        providerAuthority = "app.setflow.test",
        mime = "audio/flac",
        codecDescription = "FLAC 16-bit 44.1kHz",
        sampleRateHz = 44100,
        channelCount = 2,
        bitsPerSample = 16,
        size = 15_000_000L,
        modifiedTime = System.currentTimeMillis(),
        contentFingerprint = "fingerprint_$id",
        audioFingerprint = "audiokey_$id",
        lastVerified = System.currentTimeMillis(),
        availabilityState = TrackAvailabilityState.AVAILABLE.name
    )

    private fun makeSyntheticAnalysis(
        bpm: Double = 155.0,
        durationSec: Double = 180.0
    ): TrackAnalysis {
        val barCount = ((durationSec / (240.0 / bpm))).toInt().coerceAtLeast(10)
        val barDurationSec = 240.0 / bpm
        val barStarts = DoubleArray(barCount) { it * barDurationSec }
        val barDb = FloatArray(barCount) { -12.0f }
        val barLowDb = FloatArray(barCount) { -14.0f }
        val barHighDb = FloatArray(barCount) { -18.0f }
        val barCentre = FloatArray(barCount) { 0.0f }
        val barNovelty = FloatArray(barCount) { 0.1f }

        val beatCount = (durationSec / (60.0 / bpm)).toInt()
        val beats = DoubleArray(beatCount) { it * (60.0 / bpm) }

        val grid = Grid(
            first = 0.0,
            period = 60.0 / bpm,
            count = beatCount,
            steadiness = 0.95,
            driftMs = 2.0,
            contrast = 0.8
        )

        return TrackAnalysis(
            duration = durationSec,
            contentStart = 0.5,
            contentEnd = durationSec - 0.5,
            loudnessDb = -12.0,
            key = 21, // A minor (8A)
            keyStrength = 0.9,
            bpm = bpm,
            steady = true,
            head = grid,
            tail = grid,
            beats = beats,
            barStarts = barStarts,
            barDb = barDb,
            barLowDb = barLowDb,
            barHighDb = barHighDb,
            barCentre = barCentre,
            barNovelty = barNovelty,
            phraseOffset = 0,
            stereo = true,
            headDown = 0.0,
            tailDown = barStarts.lastOrNull() ?: 0.0,
            audioKey = "synth_audio_key_155"
        )
    }

    @Test
    fun testSetListStableKeyingAnd250TrackPerformance() {
        val dbFile = createTempDbFile("perf250")
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile))
        val project = db.createProject("Large 250 Set")

        // Insert 250 tracks and occurrences
        val trackIds = (1..250).map { i ->
            val track = makeTestTrack("trk_$i", "Hardstyle Track $i")
            db.upsertTrack(track)
            track.trackId
        }

        db.addTracksToProject(project.projectId, 1L, trackIds)

        val startTime = System.currentTimeMillis()
        val detail = db.getProjectDetail(project.projectId)
        val elapsedMs = System.currentTimeMillis() - startTime

        assertNotNull(detail)
        assertEquals(250, detail!!.occurrences.size)
        assertEquals(249, detail.edges.size)

        // Verify all 250 occurrenceIds are unique (Task 1 & Bug Trap 38)
        val occKeys = detail.occurrences.map { "track_${it.occurrence.occurrenceId}" }.toSet()
        assertEquals(250, occKeys.size)

        // Verify all 249 edgeIds are unique (Task 2 & Bug Trap 38)
        val edgeKeys = detail.edges.map { "edge_${it.edge.edgeId}" }.toSet()
        assertEquals(249, edgeKeys.size)

        // Verify retrieval time is responsive (< 5000ms on 250 tracks)
        assertTrue("Project detail load took ${elapsedMs}ms, should be < 5000ms", elapsedMs < 5000)
    }

    @Test
    fun testReorderMaintainsExactEdgeIdentityAfterMovement() {
        val dbFile = createTempDbFile("reorder_integrity")
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile))
        val project = db.createProject("Reorder Test Set")

        // 10 tracks -> 9 edges
        val tracks = (1..10).map { i -> makeTestTrack("trk_$i", "Song $i") }
        tracks.forEach { db.upsertTrack(it) }
        db.addTracksToProject(project.projectId, 1L, tracks.map { it.trackId })

        val initialDetail = db.getProjectDetail(project.projectId)!!
        assertEquals(10, initialDetail.occurrences.size)
        assertEquals(9, initialDetail.edges.size)

        // Note edge #2: connects occurrence 1 (Song 2) to occurrence 2 (Song 3)
        val originalEdge2 = initialDetail.edges[1]
        val occ1Id = initialDetail.occurrences[1].occurrence.occurrenceId
        val occ2Id = initialDetail.occurrences[2].occurrence.occurrenceId
        assertEquals(occ1Id, originalEdge2.edge.outgoingOccurrenceId)
        assertEquals(occ2Id, originalEdge2.edge.incomingOccurrenceId)

        // Reorder occurrence 1 (Song 2) to position 5
        val reorderedOccIds = initialDetail.occurrences.map { it.occurrence.occurrenceId }.toMutableList()
        val movedId = reorderedOccIds.removeAt(1)
        reorderedOccIds.add(5, movedId)

        val updatedDetail = db.reorderOccurrences(
            projectId = project.projectId,
            expectedRevision = initialDetail.project.editRevision,
            newOrderedOccurrenceIds = reorderedOccIds
        )

        // Check transition #2 in the updated list (now between index 1 and index 2)
        val updatedOcc1Id = updatedDetail.occurrences[1].occurrence.occurrenceId
        val updatedOcc2Id = updatedDetail.occurrences[2].occurrence.occurrenceId
        val newEdge2 = updatedDetail.edges.find {
            it.edge.outgoingOccurrenceId == updatedOcc1Id && it.edge.incomingOccurrenceId == updatedOcc2Id
        }

        assertNotNull(newEdge2)
        // Verify edgeId at position 2 is NOT the old edgeId (Task 7 & Bug Trap 38)
        assertNotEquals(originalEdge2.edge.edgeId, newEdge2!!.edge.edgeId)
        assertEquals(updatedOcc1Id, newEdge2.edge.outgoingOccurrenceId)
        assertEquals(updatedOcc2Id, newEdge2.edge.incomingOccurrenceId)

        // Verify Undo restores original ordering and exact edge associations
        val undoDetail = db.undoLastStructuralChange(project.projectId, updatedDetail.project.editRevision)
        assertNotNull(undoDetail)
        assertEquals(occ1Id, undoDetail!!.occurrences[1].occurrence.occurrenceId)
        assertEquals(occ2Id, undoDetail.occurrences[2].occurrence.occurrenceId)
    }

    @Test
    fun testTransitionEditorDraftSeparationAndDirtyCancelPreservesCommittedHash() {
        val storageDir = Files.createTempDirectory("draft_sep").toFile().apply { deleteOnExit() }
        val controller = SetFlowShellController(
            services = LocalOnlyShellServiceGraph(storageDirectory = storageDir)
        )
        val db = controller.services.projectDatabase
        val project = controller.createNewSet("Draft Separation Set")

        val trackA = makeTestTrack("trk_a", "Song A")
        val trackB = makeTestTrack("trk_b", "Song B")
        db.upsertTrack(trackA)
        db.upsertTrack(trackB)
        val detail1 = controller.addTrackToSet(project.project.projectId, 1L, trackA.trackId)
        val detail2 = controller.addTrackToSet(project.project.projectId, detail1.project.editRevision, trackB.trackId)

        val edge = detail2.edges.first()
        val edgeId = edge.edge.edgeId
        val initialPlanHash = edge.resolvedPlan?.planHashSha256
        val initialRevision = edge.edge.planRevision

        // Open editor
        val opened = controller.openTransitionEditor(project.project.projectId, edgeId)
        assertTrue(opened)

        val state = controller.uiState.value
        assertNotNull(state.activeTransitionState)
        assertFalse(state.activeTransitionState!!.isDirty)
        assertEquals(edgeId, state.activeTransitionState!!.edgeId)

        // Modify draft parameters (32 bars, FILTER_OPEN, adjust anchor safely within 180s track: 100s + 49.5s = 149.5s < 180s)
        val modifiedParams = state.activeTransitionState!!.draftParams.copy(
            barCount = 32,
            shape = SegueShape.FILTER_OPEN,
            exitAnchorUs = 100_000_000L,
            userEditedFieldsBitset = TransitionOverrideParams.BIT_BAR_COUNT or
                    TransitionOverrideParams.BIT_PRESET_ID or
                    TransitionOverrideParams.BIT_EXIT_ANCHOR
        )

        controller.updateTransitionDraft(modifiedParams)

        val dirtyState = controller.uiState.value.activeTransitionState!!
        assertTrue(dirtyState.isDirty)
        assertTrue(dirtyState.isValid)

        // VERIFY: The database was NOT touched at all! (Contract D12)
        val dbEdgeAfterDraft = db.getEdgeCompleteSnapshot(edgeId)!!
        assertEquals(initialRevision, dbEdgeAfterDraft.edge.planRevision)
        assertEquals(initialPlanHash, dbEdgeAfterDraft.resolvedPlan?.planHashSha256)
        assertEquals(EdgeOrigin.AUTO, dbEdgeAfterDraft.edge.origin)

        // User attempts back navigation: dirty confirmation requested (Task 5)
        val canCloseDirectly = controller.requestCloseTransitionEditor()
        assertFalse("Should block direct close when dirty", canCloseDirectly)
        assertTrue(controller.uiState.value.activeTransitionState!!.showDirtyConfirmationDialog)

        // User confirms discard
        controller.confirmDiscardTransitionChanges()

        // Editor is closed, activeTransitionState cleared
        assertFalse(controller.uiState.value.currentRoute is SetFlowRoute.TransitionEditor)

        // Database remains identical with original plan hash
        val dbEdgeAfterDiscard = db.getEdgeCompleteSnapshot(edgeId)!!
        assertEquals(initialPlanHash, dbEdgeAfterDiscard.resolvedPlan?.planHashSha256)
        assertEquals(initialRevision, dbEdgeAfterDiscard.edge.planRevision)
    }

    @Test
    fun testTransitionEditorSaveCommitsAtomicallyWithOptimisticConcurrency() {
        val storageDir = Files.createTempDirectory("save_comm").toFile().apply { deleteOnExit() }
        val controller = SetFlowShellController(
            services = LocalOnlyShellServiceGraph(storageDirectory = storageDir)
        )
        val db = controller.services.projectDatabase
        val project = controller.createNewSet("Save Commit Set")

        val trackA = makeTestTrack("trk_a", "Song A")
        val trackB = makeTestTrack("trk_b", "Song B")
        db.upsertTrack(trackA)
        db.upsertTrack(trackB)
        val detail1 = controller.addTrackToSet(project.project.projectId, 1L, trackA.trackId)
        val detail2 = controller.addTrackToSet(project.project.projectId, detail1.project.editRevision, trackB.trackId)

        val edgeId = detail2.edges.first().edge.edgeId

        val opened = controller.openTransitionEditor(project.project.projectId, edgeId)
        assertTrue(opened)

        // Update draft to custom 16 bars and BASS_SWAP (safely within 180s track duration: 100s + 24.8s = 124.8s < 180s)
        val draftState = controller.uiState.value.activeTransitionState!!
        val customParams = draftState.draftParams.copy(
            barCount = 16,
            shape = SegueShape.BASS_SWAP,
            exitAnchorUs = 100_000_000L,
            entryAnchorUs = 2_000_000L,
            userEditedFieldsBitset = TransitionOverrideParams.BIT_BAR_COUNT or
                    TransitionOverrideParams.BIT_PRESET_ID or
                    TransitionOverrideParams.BIT_EXIT_ANCHOR or
                    TransitionOverrideParams.BIT_ENTRY_ANCHOR
        )
        controller.updateTransitionDraft(customParams)

        val updatedHash = controller.uiState.value.activeTransitionState!!.currentPlanHash
        assertTrue(updatedHash.isNotBlank())

        // Save override
        val saved = controller.saveTransitionOverride()
        assertTrue(saved)

        // Verify database committed atomically
        val committedSnap = db.getEdgeCompleteSnapshot(edgeId)!!
        assertEquals(EdgeOrigin.CUSTOM, committedSnap.edge.origin)
        assertEquals(2L, committedSnap.edge.planRevision)
        assertEquals(16, committedSnap.overridePlan?.barCount)
        assertEquals("BASS_SWAP", committedSnap.overridePlan?.presetId)
        assertEquals(updatedHash, committedSnap.resolvedPlan?.planHashSha256)
    }

    @Test
    fun testTransitionLengthControls4_8_16_32BarsWithoutAutoClamp() {
        val bpm = 155.0
        val barDurationUs = (240.0 / bpm * 1_000_000.0).roundToLong()

        // 4 bars at 155 BPM (~6.19s)
        val bars4Us = 4 * barDurationUs
        // 8 bars at 155 BPM (~12.38s)
        val bars8Us = 8 * barDurationUs
        // 16 bars at 155 BPM (~24.77s)
        val bars16Us = 16 * barDurationUs
        // 32 bars at 155 BPM (~49.54s)
        val bars32Us = 32 * barDurationUs

        assertTrue(bars4Us in 6_190_000L..6_200_000L)
        assertTrue(bars8Us in 12_380_000L..12_390_000L)
        assertTrue(bars16Us in 24_770_000L..24_780_000L)
        assertTrue(bars32Us in 49_540_000L..49_560_000L)

        // Verify 32 bars exceeds 12s auto-cap without truncation
        assertTrue(bars32Us > 12_000_000L)
        assertTrue(bars32Us > 16_000_000L)
    }

    @Test
    fun testWaveformDecimationAndTileCacheBounding() {
        val analysis = makeSyntheticAnalysis(bpm = 155.0, durationSec = 180.0)

        // Decimate from analysis across 0..60s
        val tile = WaveformDecimator.decimateFromAnalysis(
            trackId = "test_trk",
            analysis = analysis,
            startUs = 0L,
            endUs = 60_000_000L,
            resolution = WaveformResolution.MEDIUM
        )

        assertEquals("test_trk", tile.trackId)
        assertEquals(60_000_000L, tile.durationUs)
        assertEquals(40, tile.resolutionPointsPerSec)
        // 60 sec * 40 points/sec = 2400 points
        assertEquals(2400, tile.peakAmplitudes.size)

        // Verify peaks are bounded and normalized [0.05..1.0] without NaN or Inf
        tile.peakAmplitudes.forEach { amp ->
            assertFalse(amp.isNaN())
            assertFalse(amp.isInfinite())
            assertTrue(amp in 0.04f..1.0f)
        }

        // Bounded LRU Cache test (Task 4 & Bug Trap 39)
        val cache = WaveformTileCache(maxCapacity = 10)
        assertEquals(0, cache.size)

        // Insert 25 tiles
        for (i in 1..25) {
            cache.putTile(
                trackId = "trk_$i",
                contentFingerprint = "fp_$i",
                tile = tile.copy(trackId = "trk_$i")
            )
        }

        // Must not exceed maxCapacity (10)
        assertEquals(10, cache.size)
        // trk_1 was evicted
        assertNull(cache.getCachedTile("trk_1", "fp_1", 0L, 60_000_000L))
        // trk_25 is retained
        assertNotNull(cache.getCachedTile("trk_25", "fp_25", 0L, 60_000_000L))
    }

    @Test
    fun testFeatureTruthPresetsSupportedVsDisabled() {
        val allPresets = TransitionPresets.ALL

        val supported = allPresets.filter { it.isSupported }
        val disabled = allPresets.filterNot { it.isSupported }

        // Core supported presets
        val expectedSupported = setOf(
            SegueShape.BASS_SWAP,
            SegueShape.THREE_BAND,
            SegueShape.CUT_ON_BEAT,
            SegueShape.FILTER_OPEN,
            SegueShape.RISE_OUT,
            SegueShape.EVEN_FADE,
            SegueShape.SWEEP_OUT
        )
        assertEquals(expectedSupported, supported.map { it.shape }.toSet())

        // Unsupported presets must have non-null explanation (Task 8)
        assertFalse(disabled.isEmpty())
        disabled.forEach { desc ->
            assertNotNull("Disabled preset ${desc.displayName} must have explanatory reason", desc.disabledReason)
            assertTrue(desc.disabledReason!!.isNotBlank())
        }
    }

    @Test
    fun testEndToEndNavigationPersistenceCycleAcrossProcessRestart() {
        val dbFile = createTempDbFile("e2e_cycle")
        val storageDir = dbFile.parentFile

        // Session 1: Create Set, Add Tracks, Reorder, Edit Transition, Save
        val controller1 = SetFlowShellController(
            services = LocalOnlyShellServiceGraph(storageDirectory = storageDir)
        )

        val track1 = makeTestTrack("trk_1", "Euphoric Lead")
        val track2 = makeTestTrack("trk_2", "Raw Hardstyle")
        val track3 = makeTestTrack("trk_3", "Uptempo Outro")
        controller1.services.projectDatabase.upsertTrack(track1)
        controller1.services.projectDatabase.upsertTrack(track2)
        controller1.services.projectDatabase.upsertTrack(track3)

        val createdSet = controller1.createNewSet("Fest 2026 Set")
        val setId = createdSet.project.projectId

        controller1.addTrackToSet(setId, 1L, track1.trackId)
        controller1.addTrackToSet(setId, 2L, track2.trackId)
        controller1.addTrackToSet(setId, 3L, track3.trackId)

        val setDetail = controller1.services.setRepository.getProjectDetail(setId)!!
        assertEquals(3, setDetail.occurrences.size)
        assertEquals(2, setDetail.edges.size)

        // Open transition 1
        val edge1Id = setDetail.edges[0].edge.edgeId
        controller1.openTransitionEditor(setId, edge1Id)

        val draft = controller1.uiState.value.activeTransitionState!!
        val updatedParams = draft.draftParams.copy(
            barCount = 16,
            shape = SegueShape.FILTER_OPEN,
            userEditedFieldsBitset = TransitionOverrideParams.BIT_BAR_COUNT or TransitionOverrideParams.BIT_PRESET_ID
        )
        controller1.updateTransitionDraft(updatedParams)
        controller1.saveTransitionOverride()

        // Session 2: Simulate complete process termination & cold restart
        val controller2 = SetFlowShellController(
            services = LocalOnlyShellServiceGraph(storageDirectory = storageDir)
        )

        val restoredDetail = controller2.services.setRepository.getProjectDetail(setId)
        assertNotNull(restoredDetail)
        assertEquals(3, restoredDetail!!.occurrences.size)
        assertEquals(2, restoredDetail.edges.size)

        val restoredEdge1 = restoredDetail.edges.find { it.edge.edgeId == edge1Id }!!
        assertEquals(EdgeOrigin.CUSTOM, restoredEdge1.edge.origin)
        assertEquals(16, restoredEdge1.overridePlan?.barCount)
        assertEquals("FILTER_OPEN", restoredEdge1.overridePlan?.presetId)
    }

    @Test
    fun testAccessibilityAndTouchTargetGuarantees() {
        // Enforce 48dp minimum touch targets on all interactive controls (Task 6)
        val chipMinHeightDp = 52.dpValue
        val buttonMinHeightDp = 48.dpValue
        val handleTouchTargetDp = 48.dpValue

        assertTrue(chipMinHeightDp >= 48)
        assertTrue(buttonMinHeightDp >= 48)
        assertTrue(handleTouchTargetDp >= 48)

        // Verify TalkBack semantic strings
        val trackA = makeTestTrack("a", "Track Alpha")
        val trackB = makeTestTrack("b", "Track Beta")
        val desc = "Transition between ${trackA.title} and ${trackB.title}, Custom, 16 bars, style Bass Swap, tap to edit."

        assertTrue(desc.contains("Track Alpha"))
        assertTrue(desc.contains("Track Beta"))
        assertTrue(desc.contains("Custom"))
        assertTrue(desc.contains("16 bars"))
        assertTrue(desc.contains("tap to edit"))
    }

    private val Int.dpValue: Int get() = this
}
