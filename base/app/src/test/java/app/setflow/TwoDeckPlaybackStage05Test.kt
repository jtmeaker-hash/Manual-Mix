/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.KnotForm
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueUnit
import app.myspoti.automix.segue.Target
import app.myspoti.automix.segue.UnitKind
import app.setflow.db.JdbcSqliteDriver
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.AudioGraphOwner
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import app.setflow.domain.TrackAvailabilityState
import app.setflow.playback.ArmedTransitionBinding
import app.setflow.playback.DualDeckPcmEngine
import app.setflow.playback.SyntheticAudioFixtureGenerator
import app.setflow.playback.TwoDeckPlaybackController
import com.music.spotui.di.CrossfadeRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs

class TwoDeckPlaybackStage05Test {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun equalPowerFadeCurveUnitInvariantsAndHeadroomProtection() {
        // Verify equal-power identity: cos^2(pi/2 * t) + sin^2(pi/2 * t) == 1.0 across [0, 1]
        for (step in 0..100) {
            val t = step / 100.0f
            val gOut = CrossfadeRamp.outgoingGain(t)
            val gIn = CrossfadeRamp.incomingGain(t)
            val powerSum = gOut * gOut + gIn * gIn
            assertEquals("Equal-power sum of squares must be 1.0 at t=$t", 1.0f, powerSum, 1e-4f)
            if (step > 0) {
                val prevT = (step - 1) / 100.0f
                assertTrue(gOut <= CrossfadeRamp.outgoingGain(prevT) + 1e-6f)
                assertTrue(gIn >= CrossfadeRamp.incomingGain(prevT) - 1e-6f)
            }
        }
        assertEquals(1.0f, CrossfadeRamp.outgoingGain(0.0f), 1e-6f)
        assertEquals(0.0f, CrossfadeRamp.incomingGain(0.0f), 1e-6f)
        assertEquals(0.70710677f, CrossfadeRamp.outgoingGain(0.5f), 1e-4f)
        assertEquals(0.70710677f, CrossfadeRamp.incomingGain(0.5f), 1e-4f)
        assertEquals(0.0f, CrossfadeRamp.outgoingGain(1.0f), 1e-6f)
        assertEquals(1.0f, CrossfadeRamp.incomingGain(1.0f), 1e-6f)

        // Verify correlated near-0 dBFS kicks (T04 170 BPM at 0.98 peak) do not clip above ±0.995f
        val kickA = SyntheticAudioFixtureGenerator.generateHardKickAndClickWav(
            name = "T04_170bpm_A",
            bpm = 170.0,
            durationSec = 6.0,
            peakAmplitude = 0.98f
        )
        val kickB = SyntheticAudioFixtureGenerator.generateHardKickAndClickWav(
            name = "T04_170bpm_B",
            bpm = 170.0,
            durationSec = 6.0,
            peakAmplitude = 0.98f
        )
        val pcmA = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(kickA.wavBytes)
        val pcmB = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(kickB.wavBytes)

        val binding = ArmedTransitionBinding(
            projectId = "proj_headroom",
            projectRevision = 1L,
            edgeId = "edge_headroom",
            edgePlanRevision = 1L,
            outgoingOccurrenceId = "occ_a",
            incomingOccurrenceId = "occ_b",
            outgoingTrackId = "trk_a",
            incomingTrackId = "trk_b",
            audioKeyA = "ak_a",
            audioKeyB = "ak_b",
            exitAnchorUs = 1_000_000L,
            entryAnchorUs = 0L,
            spanUs = 4_000_000L
        )

        val res = DualDeckPcmEngine.renderTransitionWindow(
            binding = binding,
            sourceAProvider = { pcmA },
            sourceBProvider = { pcmB },
            preRollUs = 500_000L,
            postRollUs = 500_000L,
            injectNonFiniteInDeckB = true
        )
        assertTrue("Peak linear must be bounded <= 0.995f by soft-knee headroom limiter", res.metrics.peakLinear <= 0.995f)
        assertTrue("Peak dBFS must remain below 0.0 dBFS", res.metrics.peakDbFs < 0.0)
        assertTrue("Headroom limiter must engage on summed 0.98f correlated kicks", res.metrics.headroomLimiterEngagedFrames > 0)
        assertEquals("Injected NaN across stereo channels must be blocked (A07)", 2, res.metrics.nonFiniteSamplesBlocked)
        assertTrue("Output PCM must contain zero NaN/Inf samples", res.outputPcm.interleavedSamples.all { it.isFinite() })
    }

    @Test
    fun jvmStateMachineRapidCommandsCancellationsAndStaleCallbackRejection() {
        val diagnostics = DiagnosticsRingBuffer(capacity = 128)
        val controller = TwoDeckPlaybackController(database = null, diagnostics = diagnostics)

        val binding = ArmedTransitionBinding(
            projectId = "proj_01",
            projectRevision = 1L,
            edgeId = "edge_01",
            edgePlanRevision = 1L,
            outgoingOccurrenceId = "occ_1",
            incomingOccurrenceId = "occ_2",
            outgoingTrackId = "trk_1",
            incomingTrackId = "trk_2",
            audioKeyA = "ak_1",
            audioKeyB = "ak_2",
            exitAnchorUs = 10_000_000L,
            entryAnchorUs = 2_000_000L,
            spanUs = 4_000_000L
        )

        // 1. Standard lifecycle: IDLE -> PREPARING -> ARMED -> RUNNING -> HANDOFF -> COMPLETE
        assertEquals(SchedulerPhase.IDLE, controller.state.value.phase)
        val genPrep = controller.prepareTransition(binding, owner = AudioGraphOwner.SET_PLAYBACK)
        assertEquals(SchedulerPhase.PREPARING, controller.state.value.phase)
        assertEquals(0.0f, controller.deckB.volume, 1e-6f) // Volume strictly 0 before cue

        assertTrue(controller.onDeckBPrebuffered(genPrep, bufferedDurationMs = 1_500L, decoderReady = true))
        assertEquals(SchedulerPhase.ARMED, controller.state.value.phase)
        assertEquals(0.0f, controller.deckB.volume, 1e-6f) // Still 0 while ARMED before cue

        // Step to 50% of overlap
        assertTrue(controller.onStepOverlap(genPrep, sourcePositionUsA = 12_000_000L))
        assertEquals(SchedulerPhase.RUNNING, controller.state.value.phase)
        assertTrue(controller.deckA.isPlaying && controller.deckB.isPlaying)
        assertEquals(0.7071f, controller.deckA.volume, 1e-2f)
        assertEquals(0.7071f, controller.deckB.volume, 1e-2f)

        // Step to 100% of overlap -> HANDOFF -> COMPLETE
        assertTrue(controller.onStepOverlap(genPrep, sourcePositionUsA = 14_000_000L))
        assertEquals(SchedulerPhase.COMPLETE, controller.state.value.phase)
        assertEquals("occ_2", controller.deckA.occurrenceId)
        assertEquals(1.0f, controller.deckA.volume, 1e-6f)
        assertFalse("Secondary deck must be stopped after handoff so no duplicate audio plays", controller.deckB.isPlaying)

        // 2. S05 & F19: Rapidly fire 30 commands (Play/Pause/Resume/Skip/Seek) and verify stale callbacks are rejected
        var staleGen = controller.state.value.generation
        for (i in 1..30) {
            when (i % 5) {
                0 -> controller.prepareTransition(binding, owner = AudioGraphOwner.SET_PLAYBACK)
                1 -> controller.pause()
                2 -> controller.resume()
                3 -> controller.skipToNext()
                4 -> controller.seekToUs(5_000_000L)
            }
            // Attempt to fire a callback with the previous stale generation token
            assertFalse(
                "Stale callback from generation $staleGen must be rejected",
                controller.onStepOverlap(staleGen, sourcePositionUsA = 11_000_000L)
            )
            staleGen = controller.state.value.generation
        }
        assertEquals(30, controller.staleCallbacksRejectedCount)

        // 3. S04: Single-owner arbitration (TRANSITION_PREVIEW preempts SET_PLAYBACK and restores on exit)
        controller.prepareTransition(binding, owner = AudioGraphOwner.SET_PLAYBACK)
        val previewBinding = binding.copy(edgeId = "edge_preview")
        controller.prepareTransition(previewBinding, owner = AudioGraphOwner.TRANSITION_PREVIEW)
        assertEquals(AudioGraphOwner.TRANSITION_PREVIEW, controller.state.value.activeOwner)
        assertNotNull("SET_PLAYBACK session must be saved when preempted by TRANSITION_PREVIEW", controller.suspendedSetSession)
        assertEquals("occ_1", controller.suspendedSetSession!!.occurrenceId)

        assertTrue(controller.stopPreviewAndRestoreSuspendedSet())
        assertEquals(AudioGraphOwner.SET_PLAYBACK, controller.state.value.activeOwner)
        assertEquals("occ_1", controller.deckA.occurrenceId)
    }

    @Test
    fun deviceClick155ToClick160Prebuffer4sAnd8sFadeWithZeroGapAndZeroDuplicatedFrames() {
        val click155 = SyntheticAudioFixtureGenerator.generateClickAndSubWav(
            name = "T01_click_155bpm",
            bpm = 155.0,
            durationSec = 16.0,
            sampleRateHz = 44_100,
            channels = 2
        )
        val click160 = SyntheticAudioFixtureGenerator.generateHardKickAndClickWav(
            name = "T03_kick_click_160bpm",
            bpm = 160.0,
            durationSec = 16.0,
            sampleRateHz = 44_100,
            channels = 2
        )

        val shaBefore155 = sha256Hex(click155.wavBytes)
        val shaBefore160 = sha256Hex(click160.wavBytes)

        val pcm155 = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(click155.wavBytes)
        val pcm160 = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(click160.wavBytes)

        for (fadeSeconds in listOf(4L, 8L)) {
            val spanUs = fadeSeconds * 1_000_000L
            val exitAnchorUs = 6_000_000L
            // Align entryAnchorUs to exact downbeat 1 (bar 2) of 160 BPM track (4 beats = 1.500s = 1_500_000 us)
            val entryAnchorUs = 1_500_000L
            val ratePpmB = ((155.0 / 160.0) * 1_000_000.0).toLong() // 968_750 ppm (-3.125% to match 155 BPM)

            val seguePlan = SeguePlan(
                origin = SeguePlan.Origin("setflow-stage05", "synthetic"),
                outgoing = SeguePlan.Side("ak_155", "grid_155", exitAnchorUs, SegueFormat.RATE_NOMINAL),
                incoming = SeguePlan.Side("ak_160", "grid_160", entryAnchorUs, ratePpmB),
                span = SeguePlan.Span(spanUs, if (fadeSeconds == 4L) 2 else 4, UnitKind.BAR, Clock.ELAPSED),
                pulse = SeguePlan.Pulse(PulseState.LOCKED, 155_000_000L, 0L),
                lanes = listOf(
                    SeguePlan.Lane(
                        LaneSide.OUTGOING,
                        Target.LEVEL,
                        SegueUnit.DB,
                        listOf(
                            SeguePlan.Knot(0, 0L, KnotForm.Step),
                            SeguePlan.Knot(SegueFormat.T_MAX, null, KnotForm.Ramp)
                        )
                    ),
                    SeguePlan.Lane(
                        LaneSide.INCOMING,
                        Target.LEVEL,
                        SegueUnit.DB,
                        listOf(
                            SeguePlan.Knot(0, null, KnotForm.Step),
                            SeguePlan.Knot(SegueFormat.T_MAX, 0L, KnotForm.Ramp)
                        )
                    )
                )
            )

            val binding = ArmedTransitionBinding(
                projectId = "proj_click_test",
                projectRevision = 1L,
                edgeId = "edge_155_160_${fadeSeconds}s",
                edgePlanRevision = 1L,
                outgoingOccurrenceId = "occ_155",
                incomingOccurrenceId = "occ_160",
                outgoingTrackId = "trk_155",
                incomingTrackId = "trk_160",
                audioKeyA = "ak_155",
                audioKeyB = "ak_160",
                exitAnchorUs = exitAnchorUs,
                entryAnchorUs = entryAnchorUs,
                spanUs = spanUs,
                ratePpmB = ratePpmB,
                seguePlan = seguePlan
            )

            val result = DualDeckPcmEngine.renderTransitionWindow(
                binding = binding,
                sourceAProvider = { pcm155 },
                sourceBProvider = { pcm160 },
                preRollUs = 2_000_000L,
                postRollUs = 2_000_000L
            )
            val m = result.metrics
            println(
                "STAGE05_TRANSITION_METRICS fadeSec=$fadeSeconds expectedFrames=${m.expectedOutputFrames} " +
                    "renderedFrames=${m.renderedOutputFrames} overlapFrames=${m.measuredOverlapFrames} " +
                    "alignErrFrames=${m.alignmentErrorFrames} alignErrMs=${m.alignmentErrorMs} " +
                    "deckBPrebufFrames=${m.deckBPrebufferedFrames} deckBVolPreCue=${m.deckBVolumeBeforeCue} " +
                    "peakDbFs=${"%.2f".format(m.peakDbFs)} preRmsDb=${"%.2f".format(m.preOverlapRmsDbFs)} " +
                    "overlapRmsDb=${"%.2f".format(m.overlapRmsDbFs)} postRmsDb=${"%.2f".format(m.postOverlapRmsDbFs)} " +
                    "silentGapFrames=${m.silentGapFramesInOverlap} dupA=${m.duplicatedSourceFramesA} dupB=${m.duplicatedSourceFramesB}"
            )

            assertFalse(m.fallbackTriggered)
            assertEquals(m.expectedOutputFrames, m.renderedOutputFrames)
            assertEquals(fadeSeconds * 44_100L, m.measuredOverlapFrames)
            assertEquals(0L, m.alignmentErrorFrames)
            assertEquals(0.0, m.alignmentErrorMs, 1e-6)
            assertEquals(0.0f, m.deckBVolumeBeforeCue, 1e-6f)
            assertTrue("Deck B must prebuffer >= 750ms before cue", m.deckBPrebufferedFrames >= 33_075)
            assertEquals("Zero silent gap frames allowed in overlap", 0, m.silentGapFramesInOverlap)
            assertEquals("Zero duplicated frames on Deck A", 0, m.duplicatedSourceFramesA)
            assertEquals("Zero duplicated frames on Deck B", 0, m.duplicatedSourceFramesB)
            assertTrue("Peak must not clip above 0 dBFS", m.peakDbFs < 0.0)
            assertTrue("Overlap RMS must be audibly active (> -25 dBFS)", m.overlapRmsDbFs > -25.0)
        }

        // Verify source WAV byte arrays were never mutated (ACC-40)
        assertEquals(shaBefore155, sha256Hex(click155.wavBytes))
        assertEquals(shaBefore160, sha256Hex(click160.wavBytes))
    }

    @Test
    fun skipDuringOverlapAt10Percent50PercentAnd90PercentAppliesPopSafeRampWithoutGapOrRepeat() {
        val click155 = SyntheticAudioFixtureGenerator.generateClickAndSubWav("T01_155", 155.0, 14.0)
        val click160 = SyntheticAudioFixtureGenerator.generateClickAndSubWav("T03_160", 160.0, 14.0, subHz = 65.0)
        val pcm155 = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(click155.wavBytes)
        val pcm160 = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(click160.wavBytes)

        val binding = ArmedTransitionBinding(
            projectId = "proj_skip",
            projectRevision = 1L,
            edgeId = "edge_skip",
            edgePlanRevision = 1L,
            outgoingOccurrenceId = "occ_155",
            incomingOccurrenceId = "occ_160",
            outgoingTrackId = "trk_155",
            incomingTrackId = "trk_160",
            audioKeyA = "ak_155",
            audioKeyB = "ak_160",
            exitAnchorUs = 4_000_000L,
            entryAnchorUs = 1_000_000L,
            spanUs = 8_000_000L
        )

        for (skipFraction in listOf(0.10, 0.50, 0.90)) {
            val res = DualDeckPcmEngine.renderTransitionWindow(
                binding = binding,
                sourceAProvider = { pcm155 },
                sourceBProvider = { pcm160 },
                preRollUs = 1_000_000L,
                postRollUs = 2_000_000L,
                skipAtOverlapFraction = skipFraction
            )
            val m = res.metrics
            println(
                "STAGE05_SKIP_METRICS skipFraction=$skipFraction overlapFrames=${m.measuredOverlapFrames} " +
                    "outGainEnd=${"%.4f".format(m.outgoingGainAtEnd)} inGainEnd=${"%.4f".format(m.incomingGainAtEnd)} " +
                    "silentGapFrames=${m.silentGapFramesInOverlap} dupA=${m.duplicatedSourceFramesA} dupB=${m.duplicatedSourceFramesB}"
            )
            assertEquals(skipFraction, m.skipAppliedAtOverlapFraction!!, 1e-6)
            assertEquals(0.0f, m.outgoingGainAtEnd, 1e-4f)
            assertEquals(1.0f, m.incomingGainAtEnd, 1e-4f)
            assertEquals(0, m.silentGapFramesInOverlap)
            assertEquals(0, m.duplicatedSourceFramesA)
            assertEquals(0, m.duplicatedSourceFramesB)
        }
    }

    @Test
    fun pauseResumeLateDeckBMissingSourceDecoderFailureAndRouteChangeDuringBlend() {
        val click155 = SyntheticAudioFixtureGenerator.generateClickAndSubWav("T01_155", 155.0, 10.0)
        val pcm155 = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(click155.wavBytes)

        val binding = ArmedTransitionBinding(
            projectId = "proj_faults",
            projectRevision = 2L,
            edgeId = "edge_faults",
            edgePlanRevision = 1L,
            outgoingOccurrenceId = "occ_a",
            incomingOccurrenceId = "occ_b",
            outgoingTrackId = "trk_a",
            incomingTrackId = "trk_b",
            audioKeyA = "ak_a",
            audioKeyB = "ak_b",
            exitAnchorUs = 3_000_000L,
            entryAnchorUs = 0L,
            spanUs = 4_000_000L
        )

        // 1. Late Deck B / Buffer Underrun -> Safe fallback preserves Deck A at unity gain
        val lateBResult = DualDeckPcmEngine.renderTransitionWindow(
            binding = binding,
            sourceAProvider = { pcm155 },
            sourceBProvider = { pcm155 },
            simulateDeckBNotReady = true
        )
        assertTrue(lateBResult.metrics.fallbackTriggered)
        assertEquals(AudioErrorCode.A06_BUFFER_UNDERRUN, lateBResult.metrics.fallbackErrorCode)
        assertEquals(1.0f, lateBResult.metrics.outgoingGainAtEnd, 1e-6f)
        assertTrue(lateBResult.metrics.preOverlapRmsDbFs > -25.0)

        // 2. Corrupt / Failing Decoder on Deck B -> Effect-free safety preserving Deck A
        val corruptBResult = DualDeckPcmEngine.renderTransitionWindow(
            binding = binding,
            sourceAProvider = { pcm155 },
            sourceBProvider = { throw IllegalArgumentException("Corrupt FLAC frame header in Deck B") }
        )
        assertTrue(corruptBResult.metrics.fallbackTriggered)
        assertEquals(AudioErrorCode.A03_UNSUPPORTED_CODEC, corruptBResult.metrics.fallbackErrorCode)
        assertEquals(1.0f, corruptBResult.metrics.outgoingGainAtEnd, 1e-6f)

        // 3. Database-backed revision & source checks (S06: reorder while armed; A01: missing source; A08/A09: focus & route change)
        val dbDir = Files.createTempDirectory("stage05_db_faults").toFile()
        dbDir.deleteOnExit()
        val dbFile = File(dbDir, "faults.db")

        SetFlowProjectDatabase(JdbcSqliteDriver(dbFile)).use { db ->
            val trackA = TrackEntity(
                trackId = "trk_a", displayName = "a.wav", title = "A", artist = "Art", album = "Alb",
                durationUs = 10_000_000L, contentUri = "content://test/a.wav", mime = "audio/wav",
                size = 1000L, modifiedTime = 1000L, contentFingerprint = "fp_a", audioFingerprint = "ak_a",
                lastVerified = 1000L, availabilityState = TrackAvailabilityState.AVAILABLE.name
            )
            val trackB = trackA.copy(trackId = "trk_b", displayName = "b.wav", title = "B", contentFingerprint = "fp_b", audioFingerprint = "ak_b")
            val trackC = trackA.copy(trackId = "trk_c", displayName = "c.wav", title = "C", contentFingerprint = "fp_c", audioFingerprint = "ak_c")
            db.upsertTrack(trackA)
            db.upsertTrack(trackB)
            db.upsertTrack(trackC)

            val proj = db.createProject("Live Set", projectId = "proj_faults")
            val detail = db.addTracksToProject(proj.projectId, proj.editRevision, listOf("trk_a", "trk_b", "trk_c"))
            val occA = detail.occurrences[0].occurrence.occurrenceId
            val occB = detail.occurrences[1].occurrence.occurrenceId
            val occC = detail.occurrences[2].occurrence.occurrenceId
            val edgeAB = detail.edges[0].edge

            val controller = TwoDeckPlaybackController(database = db)
            val dbBinding = binding.copy(
                projectRevision = detail.project.editRevision,
                edgeId = edgeAB.edgeId,
                edgePlanRevision = edgeAB.planRevision,
                outgoingOccurrenceId = occA,
                incomingOccurrenceId = occB
            )

            val gen = controller.prepareTransition(dbBinding)
            assertTrue(controller.onDeckBPrebuffered(gen, bufferedDurationMs = 1_500L))
            assertEquals(SchedulerPhase.ARMED, controller.state.value.phase)

            // Pause & Resume during ARMED
            val pausedGen = controller.pause()
            assertFalse(controller.state.value.isAudioPlaying)
            val resumedGen = controller.resume()
            assertTrue(controller.state.value.isAudioPlaying)

            // Audio focus transient duck (A08) & regain
            controller.onAudioFocusChange(focusLost = true, transient = true, canDuck = true)
            assertEquals(0.25f, controller.masterDuckGain, 1e-6f)
            controller.onAudioFocusChange(focusLost = false)
            assertEquals(1.0f, controller.masterDuckGain, 1e-6f)

            // Route change during blend (Bluetooth disconnect -> A09 safe pause)
            controller.onStepOverlap(controller.state.value.generation, sourcePositionUsA = 4_000_000L)
            assertEquals(SchedulerPhase.RUNNING, controller.state.value.phase)
            controller.onOutputRouteChanged("Builtin_Speaker_After_BT_Disconnect")
            assertEquals(SchedulerPhase.SAFE_FALLBACK, controller.state.value.phase)
            assertEquals(AudioErrorCode.A09_OUTPUT_ROUTE_CHANGED, controller.state.value.lastError)
            assertFalse(controller.state.value.isAudioPlaying)

            // Re-arm and then reorder the set [A, B, C] -> [A, C, B] while ARMED (S06)
            val genReArm = controller.prepareTransition(dbBinding)
            assertTrue(controller.onDeckBPrebuffered(genReArm, bufferedDurationMs = 1_500L))
            db.reorderOccurrences(proj.projectId, detail.project.editRevision, listOf(occA, occC, occB))

            // Next overlap step detects project revision mismatch and disarms safely instead of playing wrong track!
            assertFalse(controller.onStepOverlap(genReArm, sourcePositionUsA = 3_500_000L))
            assertEquals(SchedulerPhase.SAFE_FALLBACK, controller.state.value.phase)
            assertEquals(AudioErrorCode.A05_SOURCE_CHANGED, controller.state.value.lastError)

            // Missing track source (A01)
            db.updateTrackAvailability("trk_c", TrackAvailabilityState.MISSING_OR_REVOKED)
            val reorderedDetail = db.getProjectDetail(proj.projectId)!!
            val edgeAC = reorderedDetail.edges[0].edge
            val missingBinding = dbBinding.copy(
                projectRevision = reorderedDetail.project.editRevision,
                edgeId = edgeAC.edgeId,
                outgoingOccurrenceId = occA,
                incomingOccurrenceId = occC,
                incomingTrackId = "trk_c",
                audioKeyB = "ak_c"
            )
            controller.prepareTransition(missingBinding)
            assertEquals(SchedulerPhase.SAFE_FALLBACK, controller.state.value.phase)
            assertEquals(AudioErrorCode.A01_SOURCE_MISSING, controller.state.value.lastError)
        }
    }

    @Test
    fun twentyMinuteEquivalentRepeatedABTransitionSoakWithCpuMemoryAndZeroUnderruns() {
        val click155 = SyntheticAudioFixtureGenerator.generateClickAndSubWav("Soak_155", 155.0, 10.0)
        val click160 = SyntheticAudioFixtureGenerator.generateClickAndSubWav("Soak_160", 160.0, 10.0, subHz = 65.0)
        val pcm155 = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(click155.wavBytes)
        val pcm160 = SyntheticAudioFixtureGenerator.decodeWavBytesToFloatPcm(click160.wavBytes)

        val runtime = Runtime.getRuntime()
        System.gc()
        val heapBeforeBytes = runtime.totalMemory() - runtime.freeMemory()
        var peakHeapBytes = heapBeforeBytes

        val totalCycles = 150 // 150 cycles * 8.0s rendered audio per cycle = 1,200 seconds (20.0 minutes) of stereo 44.1 kHz audio!
        var totalRenderedFrames = 0L
        var totalUnderrunsOrFallbacks = 0
        var totalSilentGapFrames = 0
        var totalDuplicatedFrames = 0

        val startWallNs = System.nanoTime()
        for (cycle in 1..totalCycles) {
            val aToB = (cycle % 2 == 1)
            val binding = ArmedTransitionBinding(
                projectId = "proj_soak",
                projectRevision = 1L,
                edgeId = "edge_soak_$cycle",
                edgePlanRevision = 1L,
                outgoingOccurrenceId = if (aToB) "occ_155" else "occ_160",
                incomingOccurrenceId = if (aToB) "occ_160" else "occ_155",
                outgoingTrackId = if (aToB) "trk_155" else "trk_160",
                incomingTrackId = if (aToB) "trk_160" else "trk_155",
                audioKeyA = if (aToB) "ak_155" else "ak_160",
                audioKeyB = if (aToB) "ak_160" else "ak_155",
                exitAnchorUs = 4_000_000L,
                entryAnchorUs = 1_000_000L,
                spanUs = 4_000_000L
            )
            val res = DualDeckPcmEngine.renderTransitionWindow(
                binding = binding,
                sourceAProvider = { if (aToB) pcm155 else pcm160 },
                sourceBProvider = { if (aToB) pcm160 else pcm155 },
                preRollUs = 2_000_000L,
                postRollUs = 2_000_000L
            )
            totalRenderedFrames += res.metrics.renderedOutputFrames
            if (res.metrics.fallbackTriggered) totalUnderrunsOrFallbacks++
            totalSilentGapFrames += res.metrics.silentGapFramesInOverlap
            totalDuplicatedFrames += res.metrics.duplicatedSourceFramesA + res.metrics.duplicatedSourceFramesB

            if (cycle % 25 == 0) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                if (used > peakHeapBytes) peakHeapBytes = used
            }
        }
        val elapsedWallMs = (System.nanoTime() - startWallNs) / 1_000_000L
        System.gc()
        val heapAfterBytes = runtime.totalMemory() - runtime.freeMemory()
        val heapDeltaBytes = (heapAfterBytes - heapBeforeBytes).coerceAtLeast(0L)
        val renderedAudioSeconds = totalRenderedFrames.toDouble() / 44_100.0
        val realtimeFactor = (renderedAudioSeconds * 1_000.0) / elapsedWallMs.coerceAtLeast(1L)

        println(
            "STAGE05_SOAK_20MIN cycles=$totalCycles renderedFrames=$totalRenderedFrames " +
                "renderedAudioSec=${"%.1f".format(renderedAudioSeconds)} wallTimeMs=$elapsedWallMs " +
                "realtimeSpeedup=${"%.1f".format(realtimeFactor)}x " +
                "heapBeforeBytes=$heapBeforeBytes heapAfterBytes=$heapAfterBytes heapDeltaBytes=$heapDeltaBytes " +
                "underruns=$totalUnderrunsOrFallbacks silentGapFrames=$totalSilentGapFrames duplicatedFrames=$totalDuplicatedFrames"
        )

        assertEquals("Must render 20 full minutes (1200s = 52,920,000 frames) of dual-deck audio", 52_920_000L, totalRenderedFrames)
        assertEquals(0, totalUnderrunsOrFallbacks)
        assertEquals(0, totalSilentGapFrames)
        assertEquals(0, totalDuplicatedFrames)
        assertTrue("Post-GC heap delta after 150 cycles must remain < 16 MiB", heapDeltaBytes < 16L * 1024L * 1024L)
    }
}
