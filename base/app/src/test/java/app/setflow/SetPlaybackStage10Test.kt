/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

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
import app.setflow.db.AutoPlanSnapshotEntity
import app.setflow.db.EdgeCompleteSnapshot
import app.setflow.db.EdgeEntity
import app.setflow.db.EdgeOrigin
import app.setflow.db.EdgeValidationStatus
import app.setflow.db.JdbcSqliteDriver
import app.setflow.db.OccurrenceEntity
import app.setflow.db.OccurrenceWithTrack
import app.setflow.db.OverrideSnapshotEntity
import app.setflow.db.ProjectDetailSnapshot
import app.setflow.db.ProjectEntity
import app.setflow.db.ResolvedPlanSnapshotEntity
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import app.setflow.domain.TrackAvailabilityState
import app.setflow.playback.PlaybackResumeCheckpoint
import app.setflow.playback.PlaybackResumeStore
import app.setflow.playback.SetPlaybackQueueController
import app.setflow.playback.SetQueuePhase
import app.setflow.playback.SetTimelineResolution
import app.setflow.playback.SetTimelineResolver
import app.setflow.playback.TwoDeckPlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Stage 10 verification test suite: Whole-set automatic playback, timeline resolver,
 * bounded decoder lifecycle, audio focus, route change protection, and safe resume
 * (STAGES/10_SET_PLAYBACK_SERVICE.txt).
 */
class SetPlaybackStage10Test {

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
    ): TrackEntity = TrackEntity(
        trackId = trackId,
        displayName = "$title.flac",
        title = title,
        artist = "Test Artist",
        album = "Test Album",
        durationUs = durationUs,
        contentUri = "content://app.setflow.test/audio/$trackId",
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

    private fun sampleProject(
        projectId: String,
        name: String,
        editRevision: Long = 1L
    ): ProjectEntity = ProjectEntity(
        projectId = projectId,
        name = name,
        createdAtUtc = 1_000_000L,
        updatedAtUtc = 1_000_000L,
        editRevision = editRevision
    )

    private fun saveResolvedPlan(
        db: SetFlowProjectDatabase,
        edgeId: String,
        plan: SeguePlan
    ) {
        db.saveEdgeOverrideAndResolvedPlan(
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

    private fun buildSamplePlan(
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

    /**
     * Task 1: Authoritative set timeline resolver computes duration accounting for overlaps.
     */
    @Test
    fun test1_SetTimelineResolverCalculatesAccurateGlobalDurationAndOverlaps() {
        val t1 = sampleTrack("t1", "Track One", durationUs = 180_000_000L) // 180s
        val t2 = sampleTrack("t2", "Track Two", durationUs = 200_000_000L) // 200s
        val t3 = sampleTrack("t3", "Track Three", durationUs = 150_000_000L) // 150s

        val occ1 = OccurrenceEntity("o1", "p1", t1.trackId, 0)
        val occ2 = OccurrenceEntity("o2", "p1", t2.trackId, 1)
        val occ3 = OccurrenceEntity("o3", "p1", t3.trackId, 2)

        // Edge 1: Track 1 -> Track 2. Exit anchor: 172s, Entry: 0s, Span: 8s.
        val plan1 = buildSamplePlan(t1.trackId, t2.trackId, 172_000_000L, 0L, 8_000_000L)
        val json1 = SegueCodec.encode(plan1)
        val hash1 = SegueCodec.fingerprint(plan1)

        val edge1 = EdgeEntity("e1", "p1", "o1", "o2", planRevision = 1L)
        val edgeSnap1 = EdgeCompleteSnapshot(
            edge = edge1,
            resolvedPlan = ResolvedPlanSnapshotEntity(
                edgeId = "e1",
                resolutionRevision = 1L,
                canonicalSegueJson = json1,
                planHashSha256 = hash1,
                sourceKeys = "${t1.trackId}:${t2.trackId}",
                validatedAtUtc = System.currentTimeMillis()
            )
        )

        // Edge 2: Track 2 -> Track 3. Exit anchor: 190s, Entry: 0s, Span: 10s.
        val plan2 = buildSamplePlan(t2.trackId, t3.trackId, 190_000_000L, 0L, 10_000_000L)
        val json2 = SegueCodec.encode(plan2)
        val hash2 = SegueCodec.fingerprint(plan2)

        val edge2 = EdgeEntity("e2", "p1", "o2", "o3", planRevision = 1L)
        val edgeSnap2 = EdgeCompleteSnapshot(
            edge = edge2,
            resolvedPlan = ResolvedPlanSnapshotEntity(
                edgeId = "e2",
                resolutionRevision = 1L,
                canonicalSegueJson = json2,
                planHashSha256 = hash2,
                sourceKeys = "${t2.trackId}:${t3.trackId}",
                validatedAtUtc = System.currentTimeMillis()
            )
        )

        val project = sampleProject("p1", "Test Set")
        val detail = ProjectDetailSnapshot(
            project = project,
            occurrences = listOf(
                OccurrenceWithTrack(occ1, t1),
                OccurrenceWithTrack(occ2, t2),
                OccurrenceWithTrack(occ3, t3)
            ),
            edges = listOf(edgeSnap1, edgeSnap2)
        )

        val res = SetTimelineResolver.resolve(detail)
        assertTrue("Timeline resolution must succeed", res is SetTimelineResolution.Success)
        val timeline = (res as SetTimelineResolution.Success).timeline

        assertEquals(3, timeline.occurrenceSlots.size)
        assertEquals(2, timeline.edgeSlots.size)

        // Track 1: 0 .. 180s (172s + 8s span)
        val slot1 = timeline.occurrenceSlots[0]
        assertEquals(0L, slot1.startGlobalUs)
        assertEquals(180_000_000L, slot1.endGlobalUs)

        // Edge 1: 172s .. 180s (span 8s)
        val edgeSlot1 = timeline.edgeSlots[0]
        assertEquals(172_000_000L, edgeSlot1.overlapStartGlobalUs)
        assertEquals(180_000_000L, edgeSlot1.overlapEndGlobalUs)
        assertEquals(hash1, edgeSlot1.planHashSha256)

        // Track 2: starts at 172s (overlap start) and ends at 172s + 190s + 10s = 372s
        val slot2 = timeline.occurrenceSlots[1]
        assertEquals(172_000_000L, slot2.startGlobalUs)
        assertEquals(372_000_000L, slot2.endGlobalUs)

        // Edge 2: 362s (172 + 190) .. 372s (span 10s)
        val edgeSlot2 = timeline.edgeSlots[1]
        assertEquals(362_000_000L, edgeSlot2.overlapStartGlobalUs)
        assertEquals(372_000_000L, edgeSlot2.overlapEndGlobalUs)

        // Track 3: starts at 362s and ends at 362s + 150s = 512s
        val slot3 = timeline.occurrenceSlots[2]
        assertEquals(362_000_000L, slot3.startGlobalUs)
        assertEquals(512_000_000L, slot3.endGlobalUs)

        // Total set duration must equal 512s
        assertEquals(512_000_000L, timeline.totalDurationUs)

        // Test global timestamp seeking resolution (Task 6)
        val pos1 = timeline.resolveGlobalPosition(50_000_000L) // 50s in
        assertNotNull(pos1)
        assertEquals("o1", pos1!!.first.occurrenceId)
        assertEquals(50_000_000L, pos1.second)

        val pos2 = timeline.resolveGlobalPosition(250_000_000L) // 250s in -> in Track 2 (offset 250 - 172 = 78s)
        assertNotNull(pos2)
        assertEquals("o2", pos2!!.first.occurrenceId)
        assertEquals(78_000_000L, pos2.second)

        val pos3 = timeline.resolveGlobalPosition(400_000_000L) // 400s in -> in Track 3 (offset 400 - 362 = 38s)
        assertNotNull(pos3)
        assertEquals("o3", pos3!!.first.occurrenceId)
        assertEquals(38_000_000L, pos3.second)
    }

    /**
     * Task 1: Strict rejection of unsupported triple overlaps (A12_TIMELINE_OVERFLOW).
     */
    @Test
    fun test2_SetTimelineResolverRejectsUnsupportedTripleOverlap() {
        val t1 = sampleTrack("t1", "Track One", durationUs = 180_000_000L)
        val t2 = sampleTrack("t2", "Track Two", durationUs = 200_000_000L)
        val t3 = sampleTrack("t3", "Track Three", durationUs = 150_000_000L)

        val occ1 = OccurrenceEntity("o1", "p1", t1.trackId, 0)
        val occ2 = OccurrenceEntity("o2", "p1", t2.trackId, 1)
        val occ3 = OccurrenceEntity("o3", "p1", t3.trackId, 2)

        // Edge 1: 170s .. 180s (span 10s)
        val plan1 = buildSamplePlan(t1.trackId, t2.trackId, 170_000_000L, 0L, 10_000_000L)
        val edgeSnap1 = EdgeCompleteSnapshot(
            edge = EdgeEntity("e1", "p1", "o1", "o2", planRevision = 1L),
            resolvedPlan = ResolvedPlanSnapshotEntity("e1", 1L, SegueCodec.encode(plan1), SegueCodec.fingerprint(plan1), "", "", 0L)
        )

        // Edge 2: Starts at exitAnchor 5s into Track 2!
        // Global start = 170s + 5s = 175s, which is BEFORE Edge 1 finishes at 180s!
        val plan2 = buildSamplePlan(t2.trackId, t3.trackId, 5_000_000L, 0L, 10_000_000L)
        val edgeSnap2 = EdgeCompleteSnapshot(
            edge = EdgeEntity("e2", "p1", "o2", "o3", planRevision = 1L),
            resolvedPlan = ResolvedPlanSnapshotEntity("e2", 1L, SegueCodec.encode(plan2), SegueCodec.fingerprint(plan2), "", "", 0L)
        )

        val detail = ProjectDetailSnapshot(
            project = sampleProject("p1", "Triple Overlap Set"),
            occurrences = listOf(OccurrenceWithTrack(occ1, t1), OccurrenceWithTrack(occ2, t2), OccurrenceWithTrack(occ3, t3)),
            edges = listOf(edgeSnap1, edgeSnap2)
        )

        val res = SetTimelineResolver.resolve(detail)
        assertTrue("Must fail with error on triple overlap", res is SetTimelineResolution.Error)
        val error = res as SetTimelineResolution.Error
        assertEquals(AudioErrorCode.A12_TIMELINE_OVERFLOW, error.violationCode)
        assertTrue("Error reason must describe triple overlap", error.reason.contains("triple overlap", ignoreCase = true))
    }

    /**
     * Task 2 & 3: Automated whole-set playback progression across all tracks with accurate prebuffering & handoff.
     */
    @Test
    fun test3_AutomatedWholeSetProgressionAcrossAllTracks() {
        val dbFile = newTempDbFile("s10_set_progress_")
        val diag = DiagnosticsRingBuffer()
        val db = SetFlowProjectDatabase(JdbcSqliteDriver(dbFile), diag)

        val t1 = sampleTrack("t1", "Intro Track", durationUs = 60_000_000L)
        val t2 = sampleTrack("t2", "Middle Track", durationUs = 60_000_000L)
        val t3 = sampleTrack("t3", "Outro Track", durationUs = 60_000_000L)
        db.upsertTrack(t1)
        db.upsertTrack(t2)
        db.upsertTrack(t3)

        val proj = db.createProject("Automated Mix")
        db.addTracksToProject(proj.projectId, proj.editRevision, listOf("t1", "t2", "t3"))
        val detailAfterAdd = db.getProjectDetail(proj.projectId)!!

        val occ1 = detailAfterAdd.occurrences[0]
        val occ2 = detailAfterAdd.occurrences[1]
        val occ3 = detailAfterAdd.occurrences[2]

        // Commit resolved plans on edges
        val edge1 = detailAfterAdd.edges[0]
        val plan1 = buildSamplePlan(t1.trackId, t2.trackId, 50_000_000L, 0L, 8_000_000L)
        saveResolvedPlan(db, edge1.edge.edgeId, plan1)

        val edge2 = detailAfterAdd.edges[1]
        val plan2 = buildSamplePlan(t2.trackId, t3.trackId, 50_000_000L, 0L, 8_000_000L)
        saveResolvedPlan(db, edge2.edge.edgeId, plan2)

        val twoDeck = TwoDeckPlaybackController(db, diag)
        val queue = SetPlaybackQueueController(db, twoDeck, diag)

        // 1. Start continuous set playback
        val started = queue.startSet(proj.projectId)
        assertTrue("Queue must start set playback", started)
        assertEquals(SetQueuePhase.PLAYING_TRACK, queue.queueState.value.phase)
        assertEquals(0, queue.queueState.value.currentOccurrenceIndex)
        assertEquals("Intro Track", queue.queueState.value.currentTrackTitle)

        // 2. Step progress at 30s (steady state on Track 1)
        queue.onStepProgress(30_000_000L)
        assertEquals(SetQueuePhase.PLAYING_TRACK, queue.queueState.value.phase)

        // 3. Step progress at 42s (cue is 50s, so 42s is within 8s prebuffer window!)
        queue.onStepProgress(42_000_000L)
        assertEquals(SetQueuePhase.TRANSITION_ARMED, queue.queueState.value.phase)
        assertEquals(edge1.edge.edgeId, queue.queueState.value.activeEdgeId)

        // 4. Step progress at 50s (cue point -> overlap starts)
        queue.onStepProgress(50_000_000L)
        assertEquals(SetQueuePhase.OVERLAP_RUNNING, queue.queueState.value.phase)

        // 5. Step progress to 58s (overlap ends -> handoff completed!)
        queue.onStepProgress(58_000_000L)
        assertEquals(SetQueuePhase.PLAYING_TRACK, queue.queueState.value.phase)
        assertEquals(1, queue.queueState.value.currentOccurrenceIndex)
        assertEquals("Middle Track", queue.queueState.value.currentTrackTitle)

        // 6. Advance Track 2 through its transition into Track 3
        queue.onStepProgress(42_000_000L) // Arm
        assertEquals(SetQueuePhase.TRANSITION_ARMED, queue.queueState.value.phase)
        queue.onStepProgress(50_000_000L) // Overlap
        assertEquals(SetQueuePhase.OVERLAP_RUNNING, queue.queueState.value.phase)
        queue.onStepProgress(58_000_000L) // Handoff
        assertEquals(SetQueuePhase.PLAYING_TRACK, queue.queueState.value.phase)
        assertEquals(2, queue.queueState.value.currentOccurrenceIndex)
        assertEquals("Outro Track", queue.queueState.value.currentTrackTitle)

        // 7. Advance Track 3 to its end -> SET_COMPLETED
        queue.onStepProgress(60_000_000L)
        assertEquals(SetQueuePhase.SET_COMPLETED, queue.queueState.value.phase)
        assertFalse("Playback stopped after set completed", queue.isPlaying)
    }

    /**
     * Task 7: Bounded decoder lifecycle and memory.
     */
    @Test
    fun test4_BoundedDecoderLifecycleAndMemory() {
        val dbFile = newTempDbFile("s10_decoder_leak_")
        val diag = DiagnosticsRingBuffer()
        val db = SetFlowProjectDatabase(JdbcSqliteDriver(dbFile), diag)

        val t1 = sampleTrack("t1", "Track 1", durationUs = 60_000_000L)
        val t2 = sampleTrack("t2", "Track 2", durationUs = 60_000_000L)
        db.upsertTrack(t1)
        db.upsertTrack(t2)

        val proj = db.createProject("Leak Check")
        db.addTracksToProject(proj.projectId, proj.editRevision, listOf("t1", "t2"))
        val detail = db.getProjectDetail(proj.projectId)!!

        val plan = buildSamplePlan(t1.trackId, t2.trackId, 50_000_000L, 0L, 8_000_000L)
        saveResolvedPlan(db, detail.edges[0].edge.edgeId, plan)

        val twoDeck = TwoDeckPlaybackController(db, diag)
        val queue = SetPlaybackQueueController(db, twoDeck, diag)

        queue.startSet(proj.projectId)

        // In PLAYING_TRACK: only primary deck A is active
        assertTrue(twoDeck.deckA.isPrepared)
        assertFalse(twoDeck.deckB.isPrepared)

        // At cue - 8s: secondary deck B is prebuffered (bounded to exactly 2 decks)
        queue.onStepProgress(42_000_000L)
        assertTrue(twoDeck.deckA.isPrepared)
        assertTrue(twoDeck.deckB.isPrepared)

        // During overlap: exactly 2 decks
        queue.onStepProgress(50_000_000L)
        assertTrue(twoDeck.deckA.isPlaying)
        assertTrue(twoDeck.deckB.isPlaying)

        // After handoff: finished deck A unloaded, deck B promoted, secondary deck cleared!
        queue.onStepProgress(58_000_000L)
        assertTrue(twoDeck.deckA.isPrepared)
        assertFalse("Deck B must be cleared after handoff", twoDeck.deckB.isPrepared)
        assertNull("Deck B occurrence must be null", twoDeck.deckB.occurrenceId)
    }

    /**
     * Task 6: Global timeline seek cleanly restarts active occurrence and disarms secondary deck.
     */
    @Test
    fun test5_GlobalTimelineSeekingAndMidBlendSafety() {
        val dbFile = newTempDbFile("s10_seek_safety_")
        val diag = DiagnosticsRingBuffer()
        val db = SetFlowProjectDatabase(JdbcSqliteDriver(dbFile), diag)

        val t1 = sampleTrack("t1", "Track 1", durationUs = 60_000_000L)
        val t2 = sampleTrack("t2", "Track 2", durationUs = 60_000_000L)
        val t3 = sampleTrack("t3", "Track 3", durationUs = 60_000_000L)
        db.upsertTrack(t1)
        db.upsertTrack(t2)
        db.upsertTrack(t3)

        val proj = db.createProject("Seek Test")
        db.addTracksToProject(proj.projectId, proj.editRevision, listOf("t1", "t2", "t3"))
        val detail = db.getProjectDetail(proj.projectId)!!

        val plan1 = buildSamplePlan(t1.trackId, t2.trackId, 50_000_000L, 0L, 8_000_000L)
        saveResolvedPlan(db, detail.edges[0].edge.edgeId, plan1)
        val plan2 = buildSamplePlan(t2.trackId, t3.trackId, 50_000_000L, 0L, 8_000_000L)
        saveResolvedPlan(db, detail.edges[1].edge.edgeId, plan2)

        val twoDeck = TwoDeckPlaybackController(db, diag)
        val queue = SetPlaybackQueueController(db, twoDeck, diag)

        queue.startSet(proj.projectId)
        // Advance into overlap
        queue.onStepProgress(42_000_000L)
        queue.onStepProgress(52_000_000L)
        assertEquals(SetQueuePhase.OVERLAP_RUNNING, queue.queueState.value.phase)

        val preSeekGen = twoDeck.state.value.generation

        // Seek mid-blend to Track 3 (global ~115s)
        val seekSuccess = queue.seekToGlobalUs(115_000_000L)
        assertTrue(seekSuccess)

        // Verify clean restart on Track 3 with fresh generation
        assertEquals(2, queue.queueState.value.currentOccurrenceIndex)
        assertEquals("Track 3", queue.queueState.value.currentTrackTitle)
        assertTrue(twoDeck.state.value.generation > preSeekGen)
        assertFalse("Secondary deck must be disarmed after mid-blend seek", twoDeck.deckB.isPrepared)
        assertNull(queue.queueState.value.activeEdgeId)
    }

    /**
     * Task 2 & 6: Pop-safe skip to next promotes Deck B immediately.
     */
    @Test
    fun test6_PopSafeSkipToNextAndPrevious() {
        val dbFile = newTempDbFile("s10_skip_safe_")
        val diag = DiagnosticsRingBuffer()
        val db = SetFlowProjectDatabase(JdbcSqliteDriver(dbFile), diag)

        val t1 = sampleTrack("t1", "Track 1", durationUs = 60_000_000L)
        val t2 = sampleTrack("t2", "Track 2", durationUs = 60_000_000L)
        db.upsertTrack(t1)
        db.upsertTrack(t2)

        val proj = db.createProject("Skip Set")
        db.addTracksToProject(proj.projectId, proj.editRevision, listOf("t1", "t2"))
        val detail = db.getProjectDetail(proj.projectId)!!

        val plan = buildSamplePlan(t1.trackId, t2.trackId, 50_000_000L, 0L, 8_000_000L)
        saveResolvedPlan(db, detail.edges[0].edge.edgeId, plan)

        val twoDeck = TwoDeckPlaybackController(db, diag)
        val queue = SetPlaybackQueueController(db, twoDeck, diag)

        queue.startSet(proj.projectId)
        // Enter overlap
        queue.onStepProgress(42_000_000L)
        queue.onStepProgress(52_000_000L)
        assertEquals(SetQueuePhase.OVERLAP_RUNNING, queue.queueState.value.phase)

        // Skip to next during overlap promotes Deck B immediately
        queue.skipToNext()
        assertEquals(SetQueuePhase.PLAYING_TRACK, queue.queueState.value.phase)
        assertEquals(1, queue.queueState.value.currentOccurrenceIndex)
        assertEquals("Track 2", queue.queueState.value.currentTrackTitle)

        // Skip previous restarts current track if > 3s
        queue.onStepProgress(10_000_000L)
        queue.skipToPrevious()
        assertEquals(1, queue.queueState.value.currentOccurrenceIndex)
        assertEquals(0L, queue.queueState.value.currentLocalPositionUs)
    }

    /**
     * Task 4: Audio focus handling (ducking -12 dB, transient pause, restore on gain).
     */
    @Test
    fun test7_AudioFocusLossGainAndTransientDucking() {
        val diag = DiagnosticsRingBuffer()
        val twoDeck = TwoDeckPlaybackController(null, diag)

        val binding = app.setflow.playback.ArmedTransitionBinding(
            projectId = "p1",
            projectRevision = 1L,
            edgeId = "e1",
            edgePlanRevision = 1L,
            outgoingOccurrenceId = "o1",
            incomingOccurrenceId = "o2",
            outgoingTrackId = "t1",
            incomingTrackId = "t2",
            audioKeyA = "k1",
            audioKeyB = "k2",
            exitAnchorUs = 50_000_000L,
            entryAnchorUs = 0L,
            spanUs = 8_000_000L
        )

        twoDeck.prepareTransition(binding)
        assertEquals(1.0f, twoDeck.deckA.volume, 1e-4f)

        // Transient loss with ducking permitted (e.g. navigation voice prompt)
        twoDeck.onAudioFocusChange(focusLost = true, transient = true, canDuck = true)
        assertEquals(0.25f, twoDeck.masterDuckGain, 1e-4f)
        assertEquals(0.25f, twoDeck.deckA.volume, 1e-4f)
        assertEquals(AudioErrorCode.A08_AUDIO_FOCUS_LOST, twoDeck.state.value.lastError)

        // Focus restored
        twoDeck.onAudioFocusChange(focusLost = false)
        assertEquals(1.0f, twoDeck.masterDuckGain, 1e-4f)
        assertEquals(1.0f, twoDeck.deckA.volume, 1e-4f)
        assertNull(twoDeck.state.value.lastError)

        // Permanent focus loss -> pauses playback
        twoDeck.onAudioFocusChange(focusLost = true, transient = false, canDuck = false)
        assertFalse(twoDeck.deckA.isPlaying)
        assertFalse(twoDeck.state.value.isAudioPlaying)
    }

    /**
     * Task 4: Output route change (ACTION_AUDIO_BECOMING_NOISY).
     */
    @Test
    fun test8_AudioBecomingNoisyOutputRouteChange() {
        val diag = DiagnosticsRingBuffer()
        val twoDeck = TwoDeckPlaybackController(null, diag)

        val binding = app.setflow.playback.ArmedTransitionBinding(
            projectId = "p1",
            projectRevision = 1L,
            edgeId = "e1",
            edgePlanRevision = 1L,
            outgoingOccurrenceId = "o1",
            incomingOccurrenceId = "o2",
            outgoingTrackId = "t1",
            incomingTrackId = "t2",
            audioKeyA = "k1",
            audioKeyB = "k2",
            exitAnchorUs = 50_000_000L,
            entryAnchorUs = 0L,
            spanUs = 8_000_000L
        )

        twoDeck.prepareTransition(binding)
        assertTrue(twoDeck.state.value.isAudioPlaying)

        // Route changed (headphone unplugged / BT disconnected)
        twoDeck.onOutputRouteChanged("becoming_noisy")
        assertFalse("Must pause immediately on route change", twoDeck.state.value.isAudioPlaying)
        assertEquals(AudioErrorCode.A09_OUTPUT_ROUTE_CHANGED, twoDeck.state.value.lastError)
        assertFalse("Secondary deck must be flushed", twoDeck.deckB.isPrepared)
    }

    /**
     * Task 5: Safe resume checkpoint persistence and validation against project edits.
     */
    @Test
    fun test9_PlaybackResumeStoreValidationAndRevisionMismatch() {
        val store = PlaybackResumeStore(preferences = null)

        val cp = PlaybackResumeCheckpoint(
            projectId = "proj_alpha",
            projectRevision = 5L,
            occurrenceId = "occ_beta",
            occurrenceIndex = 2,
            localPositionUs = 45_000_000L,
            globalPositionUs = 210_000_000L
        )

        store.saveCheckpoint(cp)
        val loaded = store.loadCheckpoint()
        assertNotNull(loaded)
        assertEquals("proj_alpha", loaded!!.projectId)
        assertEquals(5L, loaded.projectRevision)
        assertEquals("occ_beta", loaded.occurrenceId)
        assertEquals(45_000_000L, loaded.localPositionUs)

        val matchingProject = ProjectDetailSnapshot(
            project = sampleProject("proj_alpha", "Alpha", editRevision = 5L),
            occurrences = listOf(
                OccurrenceWithTrack(
                    OccurrenceEntity("occ_beta", "proj_alpha", "t1", 2),
                    sampleTrack("t1", "Track 1")
                )
            ),
            edges = emptyList()
        )

        // Valid checkpoint match
        assertTrue(store.validateCheckpoint(loaded, matchingProject))

        // Project edited while app paused (revision incremented from 5 to 6) -> REJECT STALE RESUME (Task 5)
        val editedProject = matchingProject.copy(
            project = matchingProject.project.copy(editRevision = 6L)
        )
        assertFalse("Must reject resume checkpoint when project revision changed", store.validateCheckpoint(loaded, editedProject))

        // Occurrence removed while paused -> REJECT
        val emptyOccProject = matchingProject.copy(occurrences = emptyList())
        assertFalse("Must reject resume checkpoint when occurrence was removed", store.validateCheckpoint(loaded, emptyOccProject))

        store.clearCheckpoint()
        assertNull(store.loadCheckpoint())
    }

    /**
     * Task 9: Rapid command stress harness (30 rapid Skip/Pause/Play/Seek operations).
     */
    @Test
    fun test10_RapidCommandStressHarness() {
        val dbFile = newTempDbFile("s10_rapid_stress_")
        val diag = DiagnosticsRingBuffer()
        val db = SetFlowProjectDatabase(JdbcSqliteDriver(dbFile), diag)

        val t1 = sampleTrack("t1", "Track 1", durationUs = 60_000_000L)
        val t2 = sampleTrack("t2", "Track 2", durationUs = 60_000_000L)
        val t3 = sampleTrack("t3", "Track 3", durationUs = 60_000_000L)
        db.upsertTrack(t1)
        db.upsertTrack(t2)
        db.upsertTrack(t3)

        val proj = db.createProject("Rapid Stress")
        db.addTracksToProject(proj.projectId, proj.editRevision, listOf("t1", "t2", "t3"))
        val detail = db.getProjectDetail(proj.projectId)!!

        val plan1 = buildSamplePlan(t1.trackId, t2.trackId, 50_000_000L, 0L, 8_000_000L)
        saveResolvedPlan(db, detail.edges[0].edge.edgeId, plan1)

        val twoDeck = TwoDeckPlaybackController(db, diag)
        val queue = SetPlaybackQueueController(db, twoDeck, diag)

        queue.startSet(proj.projectId)

        var lastGen = twoDeck.state.value.generation

        // Fire 30 rapid commands
        for (i in 1..30) {
            when (i % 5) {
                0 -> queue.pause()
                1 -> queue.resume()
                2 -> queue.skipToNext()
                3 -> queue.skipToPrevious()
                4 -> queue.seekToGlobalUs((i * 5_000_000L) % 100_000_000L)
            }
            val currentGen = twoDeck.state.value.generation
            assertTrue("Generation must increment monotonically (i=$i, lastGen=$lastGen, currentGen=$currentGen)", currentGen >= lastGen)
            lastGen = currentGen
        }

        // Verify clean, uncorrupted final state
        assertNotNull(queue.queueState.value)
        assertTrue(queue.queueState.value.phase != SetQueuePhase.ERROR)
    }
}
