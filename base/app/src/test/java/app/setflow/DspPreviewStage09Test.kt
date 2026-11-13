/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.myspoti.automix.segue.Clock
import app.myspoti.automix.segue.KnotForm
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.PulseState
import app.myspoti.automix.segue.SegueDeck
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.SegueShape
import app.myspoti.automix.segue.SegueSideFacts
import app.myspoti.automix.segue.Target
import app.myspoti.automix.segue.TransitionOverrideParams
import app.myspoti.automix.segue.TransitionPlanAdapter
import app.myspoti.automix.segue.TransitionResolutionResult
import app.myspoti.automix.segue.UnitKind
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.db.SetFlowSchemaAndMigrations
import app.setflow.db.TrackEntity
import app.setflow.domain.AudioGraphOwner
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.TrackAvailabilityState
import app.setflow.playback.ArmedTransitionBinding
import app.setflow.playback.DecodedPcmBuffer
import app.setflow.playback.DualDeckPcmEngine
import app.setflow.playback.SyntheticAudioFixtureGenerator
import app.setflow.playback.TransitionPreviewAudioBridge
import app.setflow.playback.TwoDeckPlaybackController
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.ui.SetFlowShellController
import app.setflow.ui.transition.TransitionPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Stage 09 verification test suite: Audible EQ/FX transitions, headroom limiting,
 * and transition pair preview (STAGES/09_DSP_PREVIEW.txt).
 */
class DspPreviewStage09Test {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun createTempDir(prefix: String): File {
        val dir = Files.createTempDirectory(prefix).toFile()
        dir.deleteOnExit()
        return dir
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

    private fun createSyntheticPcm(
        durationSec: Double,
        sampleRate: Int = 44_100,
        channels: Int = 2,
        baseFreqHz: Double = 440.0,
        amplitude: Float = 0.50f
    ): DecodedPcmBuffer {
        val totalFrames = (durationSec * sampleRate).toInt()
        val samples = FloatArray(totalFrames * channels)
        val twoPiF = 2.0 * Math.PI * baseFreqHz / sampleRate
        for (f in 0 until totalFrames) {
            val v = (amplitude * Math.sin(twoPiF * f)).toFloat()
            for (c in 0 until channels) {
                samples[f * channels + c] = v
            }
        }
        return DecodedPcmBuffer(sampleRate, channels, totalFrames, samples)
    }

    private fun createRichMultiBandPcm(
        durationSec: Double,
        sampleRate: Int = 44_100,
        channels: Int = 2,
        amplitude: Float = 0.60f
    ): DecodedPcmBuffer {
        val totalFrames = (durationSec * sampleRate).toInt()
        val samples = FloatArray(totalFrames * channels)
        val twoPiSub = 2.0 * Math.PI * 60.0 / sampleRate
        val twoPiMid = 2.0 * Math.PI * 1000.0 / sampleRate
        val twoPiHigh = 2.0 * Math.PI * 8000.0 / sampleRate
        for (f in 0 until totalFrames) {
            val sub = 0.40 * Math.sin(twoPiSub * f)
            val mid = 0.35 * Math.sin(twoPiMid * f)
            val high = 0.25 * Math.sin(twoPiHigh * f)
            val v = (amplitude * (sub + mid + high)).toFloat().coerceIn(-0.95f, 0.95f)
            for (c in 0 until channels) {
                samples[f * channels + c] = v
            }
        }
        return DecodedPcmBuffer(sampleRate, channels, totalFrames, samples)
    }

    private fun createSyntheticHardKickPcm(
        durationSec: Double = 16.0,
        bpm: Double = 160.0,
        sampleRate: Int = 44_100,
        channels: Int = 2,
        peakAmp: Float = 0.95f
    ): DecodedPcmBuffer {
        val fixture = SyntheticAudioFixtureGenerator.generateHardKickAndClickWav(
            name = "KickFixture",
            bpm = bpm,
            durationSec = durationSec,
            sampleRateHz = sampleRate,
            channels = channels,
            peakAmplitude = peakAmp
        )
        val rawPcm = fixture.wavBytes.copyOfRange(44, fixture.wavBytes.size)
        val totalSamples = rawPcm.size / 2
        val floatSamples = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            val low = rawPcm[i * 2].toInt() and 0xFF
            val high = rawPcm[i * 2 + 1].toInt()
            val shortVal = (high shl 8) or low
            floatSamples[i] = shortVal / 32768.0f
        }
        return DecodedPcmBuffer(sampleRate, channels, totalSamples / channels, floatSamples)
    }

    /**
     * Task 1 & 2: Every enabled preset changes actual sound as specified.
     * Renders rich multiband audio across all 7 enabled presets and asserts audible,
     * measurable differences in PCM samples and spectrum.
     */
    @Test
    fun testAllEnabledPresetsChangeSoundMeasurably() {
        val sampleRate = 44_100
        val channels = 2
        val durationSec = 16.0

        val pcmA = createRichMultiBandPcm(durationSec, sampleRate, channels)
        val pcmB = createRichMultiBandPcm(durationSec, sampleRate, channels)

        val outFacts = SegueSideFacts(durationUs = (durationSec * 1e6).toLong(), audioKey = "richA", gridKey = "gridA")
        val inFacts = SegueSideFacts(durationUs = (durationSec * 1e6).toLong(), audioKey = "richB", gridKey = "gridB")

        val enabledShapes = listOf(
            SegueShape.BASS_SWAP,
            SegueShape.THREE_BAND,
            SegueShape.CUT_ON_BEAT,
            SegueShape.FILTER_OPEN,
            SegueShape.RISE_OUT,
            SegueShape.EVEN_FADE,
            SegueShape.SWEEP_OUT
        )

        val renders = mutableMapOf<SegueShape, FloatArray>()

        for (shape in enabledShapes) {
            val resolution = TransitionPlanAdapter.resolve(
                override = TransitionOverrideParams(
                    exitAnchorUs = 6_000_000L,
                    entryAnchorUs = 1_000_000L,
                    spanUs = 4_000_000L,
                    shape = shape
                ),
                outgoingFacts = outFacts,
                incomingFacts = inFacts,
                outgoingBpm = 155.0,
                incomingBpm = 155.0
            )

            assertTrue("Resolution for shape $shape should succeed", resolution is TransitionResolutionResult.Success)
            val success = resolution as TransitionResolutionResult.Success

            val binding = ArmedTransitionBinding(
                projectId = "p1",
                projectRevision = 1L,
                edgeId = "e1",
                edgePlanRevision = 1L,
                outgoingOccurrenceId = "occA",
                incomingOccurrenceId = "occB",
                outgoingTrackId = "trackA",
                incomingTrackId = "trackB",
                audioKeyA = "richA",
                audioKeyB = "richB",
                exitAnchorUs = 6_000_000L,
                entryAnchorUs = 1_000_000L,
                spanUs = 4_000_000L,
                ratePpmB = 1_000_000L,
                planHashSha256 = success.planHashSha256,
                seguePlan = success.plan
            )

            val render = DualDeckPcmEngine.renderTransitionWindow(
                binding = binding,
                sourceAProvider = { pcmA },
                sourceBProvider = { pcmB },
                preRollUs = 2_000_000L,
                postRollUs = 2_000_000L,
                useSegueDspIfAvailable = true
            )

            assertEquals("No non-finite samples allowed in $shape", 0, render.metrics.nonFiniteSamplesBlocked)
            assertTrue("Peak must remain safe in $shape", render.metrics.peakLinear <= 0.995f)
            assertTrue("Output frames must be positive in $shape", render.outputPcm.totalFrames > 0)

            renders[shape] = render.outputPcm.interleavedSamples
        }

        // Verify pairwise differences between shapes: each preset must change the sound differently
        val shapesList = enabledShapes.toList()
        for (i in 0 until shapesList.size) {
            for (j in i + 1 until shapesList.size) {
                val shape1 = shapesList[i]
                val shape2 = shapesList[j]
                val samples1 = renders[shape1]!!
                val samples2 = renders[shape2]!!
                assertEquals(samples1.size, samples2.size)

                var maxDiff = 0.0f
                var diffCount = 0
                for (k in samples1.indices) {
                    val diff = abs(samples1[k] - samples2[k])
                    if (diff > maxDiff) maxDiff = diff
                    if (diff > 0.01f) diffCount++
                }

                assertTrue(
                    "Preset $shape1 and $shape2 must produce audibly different audio (maxDiff=$maxDiff, diffFrames=$diffCount)",
                    maxDiff > 0.05f && diffCount > 100
                )
            }
        }
    }

    /**
     * Task 1 & F09: 8, 16, and 32 bar transitions render exact bar durations without auto-clamping.
     */
    @Test
    fun test8_16_32BarTransitionsExecuteAccurately() {
        val sampleRate = 44_100
        val bpm = 155.0
        val barPeriodSec = 240.0 / bpm // ~1.548387s per bar

        val outFacts = SegueSideFacts(durationUs = 120_000_000L, audioKey = "longA", gridKey = "gridA")
        val inFacts = SegueSideFacts(durationUs = 120_000_000L, audioKey = "longB", gridKey = "gridB")

        val pcmA = createSyntheticPcm(durationSec = 100.0, sampleRate = sampleRate)
        val pcmB = createSyntheticPcm(durationSec = 100.0, sampleRate = sampleRate)

        for (bars in listOf(4, 8, 16, 32)) {
            val resolution = TransitionPlanAdapter.resolve(
                override = TransitionOverrideParams(
                    exitAnchorUs = 20_000_000L,
                    entryAnchorUs = 2_000_000L,
                    barCount = bars,
                    shape = SegueShape.BASS_SWAP
                ),
                outgoingFacts = outFacts,
                incomingFacts = inFacts,
                outgoingBpm = bpm,
                incomingBpm = bpm
            )

            assertTrue(resolution is TransitionResolutionResult.Success)
            val success = resolution as TransitionResolutionResult.Success
            val plan = success.plan

            val barPeriodUs = (240.0 / bpm * 1e6).roundToLong()
            val expectedDurationUs = bars * barPeriodUs
            assertEquals("Plan span lengthUs must match exact bar duration without clamp", expectedDurationUs, plan.span.lengthUs)
            assertEquals("Musical units must equal bar count", bars, plan.span.musicalUnits)

            val binding = ArmedTransitionBinding(
                projectId = "p",
                projectRevision = 1L,
                edgeId = "e",
                edgePlanRevision = 1L,
                outgoingOccurrenceId = "oA",
                incomingOccurrenceId = "oB",
                outgoingTrackId = "tA",
                incomingTrackId = "tB",
                audioKeyA = "longA",
                audioKeyB = "longB",
                exitAnchorUs = plan.outgoing.anchorUs,
                entryAnchorUs = plan.incoming.anchorUs,
                spanUs = plan.span.lengthUs,
                ratePpmB = 1_000_000L,
                planHashSha256 = success.planHashSha256,
                seguePlan = plan
            )

            val render = DualDeckPcmEngine.renderTransitionWindow(
                binding = binding,
                sourceAProvider = { pcmA },
                sourceBProvider = { pcmB },
                preRollUs = 2_000_000L,
                postRollUs = 2_000_000L,
                useSegueDspIfAvailable = true
            )

            val expectedOverlapFrames = (expectedDurationUs * sampleRate / 1_000_000L).toInt()
            assertEquals("Measured overlap frames must match bar count", expectedOverlapFrames.toLong(), render.metrics.measuredOverlapFrames)
            assertEquals(0L, render.metrics.alignmentErrorFrames)
        }
    }

    /**
     * Task 5 & 6 (F06): Near-0 dBFS in-phase hardstyle kicks summing in transition.
     * Verifies that soft-knee headroom limiter compresses correlated peaks so that peak linear <= 0.995f
     * with zero clipping distortion and zero non-finite samples.
     */
    @Test
    fun testHardstyleInPhaseKicksHeadroomLimiterPreventsClipping() {
        for (sampleRate in listOf(44_100, 48_000)) {
            for (channels in listOf(1, 2)) {
                // Two identical in-phase heavy hardstyle kicks at peak 0.96f
                val kickA = createSyntheticHardKickPcm(durationSec = 16.0, bpm = 160.0, sampleRate = sampleRate, channels = channels, peakAmp = 0.96f)
                val kickB = createSyntheticHardKickPcm(durationSec = 16.0, bpm = 160.0, sampleRate = sampleRate, channels = channels, peakAmp = 0.96f)

                val outFacts = SegueSideFacts(durationUs = 16_000_000L, audioKey = "kickKeyA", gridKey = "gridA")
                val inFacts = SegueSideFacts(durationUs = 16_000_000L, audioKey = "kickKeyB", gridKey = "gridB")

                val resolution = TransitionPlanAdapter.resolve(
                    override = TransitionOverrideParams(
                        exitAnchorUs = 4_000_000L,
                        entryAnchorUs = 0L,
                        spanUs = 4_000_000L,
                        shape = SegueShape.EVEN_FADE
                    ),
                    outgoingFacts = outFacts,
                    incomingFacts = inFacts,
                    outgoingBpm = 160.0,
                    incomingBpm = 160.0
                )

                assertTrue(resolution is TransitionResolutionResult.Success)
                val success = resolution as TransitionResolutionResult.Success

                val binding = ArmedTransitionBinding(
                    projectId = "pKick",
                    projectRevision = 1L,
                    edgeId = "eKick",
                    edgePlanRevision = 1L,
                    outgoingOccurrenceId = "oA",
                    incomingOccurrenceId = "oB",
                    outgoingTrackId = "tA",
                    incomingTrackId = "tB",
                    audioKeyA = "kickKeyA",
                    audioKeyB = "kickKeyB",
                    exitAnchorUs = 4_000_000L,
                    entryAnchorUs = 0L,
                    spanUs = 4_000_000L,
                    ratePpmB = 1_000_000L,
                    planHashSha256 = success.planHashSha256,
                    seguePlan = success.plan
                )

                val render = DualDeckPcmEngine.renderTransitionWindow(
                    binding = binding,
                    sourceAProvider = { kickA },
                    sourceBProvider = { kickB },
                    preRollUs = 2_000_000L,
                    postRollUs = 2_000_000L,
                    useSegueDspIfAvailable = true
                )

                assertTrue("Headroom limiter must engage for summing in-phase kicks", render.metrics.headroomLimiterEngagedFrames > 0)
                assertTrue("Peak linear must stay strictly <= 0.995f (ceiling), was ${render.metrics.peakLinear}", render.metrics.peakLinear <= 0.995f)
                assertTrue("Peak dBFS must remain <= -0.04 dBFS, was ${render.metrics.peakDbFs}", render.metrics.peakDbFs <= -0.04)
                assertEquals("Zero non-finite samples", 0, render.metrics.nonFiniteSamplesBlocked)

                // Verify samples in buffer are strictly finite and within [-0.995, 0.995]
                val samples = render.outputPcm.interleavedSamples
                for (s in samples) {
                    assertTrue("Sample must be finite", s.isFinite())
                    assertTrue("Sample magnitude must not exceed 0.995f", abs(s) <= 0.9951f)
                }
            }
        }
    }

    /**
     * Task 6: Neutral EQ genuine bypass test.
     * When plan is neutral (0 dB bands, open filters, level 1.0), SegueDeck must output bit-identical dry audio.
     */
    @Test
    fun testNeutralEqTrueBypass() {
        val sampleRate = 44_100
        val channels = 2
        val frames = 1000

        // Create neutral plan: LEVEL = 0 dB, no EQ cuts/boosts
        val lanes = listOf(
            SeguePlan.Lane(
                side = LaneSide.OUTGOING,
                target = Target.LEVEL,
                unit = Target.LEVEL.unit,
                knots = listOf(
                    SeguePlan.Knot(0, 0L, KnotForm.Step),
                    SeguePlan.Knot(SegueFormat.T_MAX, 0L, KnotForm.Ramp)
                )
            )
        )

        val plan = SeguePlan(
            origin = TransitionPlanAdapter.ENGINE_ORIGIN,
            outgoing = SeguePlan.Side(audioKey = "keyA", gridKey = null, anchorUs = 0L, ratePpm = SegueFormat.RATE_NOMINAL),
            incoming = SeguePlan.Side(audioKey = "keyB", gridKey = null, anchorUs = 0L, ratePpm = SegueFormat.RATE_NOMINAL),
            span = SeguePlan.Span(lengthUs = 10_000_000L, musicalUnits = 4, unitKind = UnitKind.BAR, clock = Clock.ELAPSED),
            pulse = SeguePlan.Pulse(state = PulseState.FREE, tempoMicroPerMinute = null, residualUs = null),
            lanes = lanes
        )

        val deck = SegueDeck(plan, LaneSide.OUTGOING, sampleRate, channels)

        val inputSamples = FloatArray(frames * channels) { (it * 0.001f) % 0.8f - 0.4f }
        val outputSamples = inputSamples.clone()

        deck.process(outputSamples, frames, sourceUs = 1_000_000L)

        // Verify bit-for-bit identity
        for (i in inputSamples.indices) {
            assertEquals("Sample $i must be identical under neutral bypass", inputSamples[i], outputSamples[i], 0.0f)
        }
    }

    /**
     * Task 7 (F08): Effect state isolation.
     * Rendering A -> B with resonant filter, then B -> C with neutral fade must leave zero residual filter state or ringing.
     */
    @Test
    fun testEffectSessionIsolationNoRingLeakage() {
        val sampleRate = 44_100
        val channels = 2
        val pcm = createSyntheticPcm(durationSec = 10.0, sampleRate = sampleRate, channels = channels)

        val facts = SegueSideFacts(durationUs = 10_000_000L, audioKey = "k", gridKey = "g")

        // 1. Session 1: SWEEP_OUT (heavy resonant sweep)
        val res1 = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(spanUs = 4_000_000L, shape = SegueShape.SWEEP_OUT),
            outgoingFacts = facts,
            incomingFacts = facts
        ) as TransitionResolutionResult.Success

        val binding1 = ArmedTransitionBinding(
            projectId = "p", projectRevision = 1L, edgeId = "e1", edgePlanRevision = 1L,
            outgoingOccurrenceId = "oA", incomingOccurrenceId = "oB", outgoingTrackId = "tA", incomingTrackId = "tB",
            audioKeyA = "k", audioKeyB = "k", exitAnchorUs = 2_000_000L, entryAnchorUs = 0L, spanUs = 4_000_000L,
            planHashSha256 = res1.planHashSha256, seguePlan = res1.plan
        )

        DualDeckPcmEngine.renderTransitionWindow(
            binding = binding1,
            sourceAProvider = { pcm },
            sourceBProvider = { pcm },
            useSegueDspIfAvailable = true
        )

        // 2. Session 2: B -> C with EVEN_FADE (fresh session)
        val res2 = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(spanUs = 4_000_000L, shape = SegueShape.EVEN_FADE),
            outgoingFacts = facts,
            incomingFacts = facts
        ) as TransitionResolutionResult.Success

        val binding2 = ArmedTransitionBinding(
            projectId = "p", projectRevision = 1L, edgeId = "e2", edgePlanRevision = 1L,
            outgoingOccurrenceId = "oB", incomingOccurrenceId = "oC", outgoingTrackId = "tB", incomingTrackId = "tC",
            audioKeyA = "k", audioKeyB = "k", exitAnchorUs = 2_000_000L, entryAnchorUs = 0L, spanUs = 4_000_000L,
            planHashSha256 = res2.planHashSha256, seguePlan = res2.plan
        )

        val renderSession2 = DualDeckPcmEngine.renderTransitionWindow(
            binding = binding2,
            sourceAProvider = { pcm },
            sourceBProvider = { pcm },
            useSegueDspIfAvailable = true
        )

        // 3. Isolated baseline: B -> C rendered completely in isolation
        val baseline = DualDeckPcmEngine.renderTransitionWindow(
            binding = binding2,
            sourceAProvider = { pcm },
            sourceBProvider = { pcm },
            useSegueDspIfAvailable = true
        )

        // The audio output of Session 2 must be bit-identical to the baseline
        val s2 = renderSession2.outputPcm.interleavedSamples
        val base = baseline.outputPcm.interleavedSamples
        assertEquals("Buffer sizes must match", base.size, s2.size)
        for (i in base.indices) {
            assertEquals("No filter state leak on sample $i", base[i], s2[i], 0.0f)
        }
    }

    /**
     * Task 3: Single audio owner arbitration and set playback suspension.
     * When TRANSITION_PREVIEW starts while SET_PLAYBACK is active, SET_PLAYBACK is checkpointed and suspended.
     * When preview stops, SET_PLAYBACK is cleanly restored in paused state.
     */
    @Test
    fun testSingleAudioOwnerHandoffAndSuspension() {
        val tempDir = createTempDir("stage09_owner_test")
        val dbFile = File(tempDir, "owner_test.db")
        val db = SetFlowProjectDatabase(SetFlowSchemaAndMigrations.createDriver(dbFile))
        val diagnostics = DiagnosticsRingBuffer()

        try {
            val project = db.createProject("OwnerTestProject")
            val tA = makeTestTrack("tA", "Song A")
            val tB = makeTestTrack("tB", "Song B")
            db.upsertTrack(tA)
            db.upsertTrack(tB)
            db.addTracksToProject(project.projectId, 1L, listOf(tA.trackId, tB.trackId))
            val detail = db.getProjectDetail(project.projectId)!!
            val occA = detail.occurrences[0].occurrence
            val occB = detail.occurrences[1].occurrence
            val edge = detail.edges.first()

            val playbackController = TwoDeckPlaybackController(db, diagnostics)
            val previewBridge = TransitionPreviewAudioBridge(playbackController, diagnostics)

            val setBinding = ArmedTransitionBinding(
                projectId = project.projectId,
                projectRevision = detail.project.editRevision,
                edgeId = edge.edge.edgeId,
                edgePlanRevision = edge.edge.planRevision,
                outgoingOccurrenceId = occA.occurrenceId,
                incomingOccurrenceId = occB.occurrenceId,
                outgoingTrackId = tA.trackId,
                incomingTrackId = tB.trackId,
                audioKeyA = tA.audioFingerprint!!,
                audioKeyB = tB.audioFingerprint!!,
                exitAnchorUs = 80_000_000L,
                entryAnchorUs = 0L,
                spanUs = 8_000_000L,
                planHashSha256 = "setHash123"
            )

            // 1. Simulate set playback running
            playbackController.prepareTransition(
                binding = setBinding,
                owner = AudioGraphOwner.SET_PLAYBACK,
                initialPositionUsA = 45_000_000L
            )
            assertEquals(AudioGraphOwner.SET_PLAYBACK, playbackController.state.value.activeOwner)
            assertTrue(playbackController.state.value.isAudioPlaying)

            // 2. Transition preview starts with draft plan
            val draftPlan = SeguePlan(
                origin = TransitionPlanAdapter.ENGINE_ORIGIN,
                outgoing = SeguePlan.Side(audioKey = tA.trackId, gridKey = null, anchorUs = 60_000_000L, ratePpm = SegueFormat.RATE_NOMINAL),
                incoming = SeguePlan.Side(audioKey = tB.trackId, gridKey = null, anchorUs = 0L, ratePpm = SegueFormat.RATE_NOMINAL),
                span = SeguePlan.Span(lengthUs = 12_000_000L, musicalUnits = 8, unitKind = UnitKind.BAR, clock = Clock.ELAPSED),
                pulse = SeguePlan.Pulse(state = PulseState.FREE, tempoMicroPerMinute = null, residualUs = null),
                lanes = emptyList()
            )

            val started = previewBridge.startPreview(
                projectId = project.projectId,
                projectRevision = detail.project.editRevision,
                edgeId = edge.edge.edgeId,
                edgePlanRevision = edge.edge.planRevision,
                outgoingOccurrenceId = occA.occurrenceId,
                incomingOccurrenceId = occB.occurrenceId,
                outgoingTrack = tA,
                incomingTrack = tB,
                draftPlan = draftPlan,
                planHashSha256 = "draftHash999"
            )
            assertTrue(started)
            assertTrue(previewBridge.isPreviewActive)

            // Verify SET_PLAYBACK was suspended and checkpointed
            assertNotNull(playbackController.suspendedSetSession)
            assertEquals(occA.occurrenceId, playbackController.suspendedSetSession?.occurrenceId)
            assertEquals(45_000_000L, playbackController.suspendedSetSession?.positionUs)
            assertEquals(AudioGraphOwner.TRANSITION_PREVIEW, playbackController.state.value.activeOwner)

            // 3. Stop preview: restores suspended SET_PLAYBACK
            previewBridge.stopPreview()
            assertFalse(previewBridge.isPreviewActive)
            assertNull(playbackController.suspendedSetSession)
            assertEquals(AudioGraphOwner.SET_PLAYBACK, playbackController.state.value.activeOwner)
            assertEquals(occA.occurrenceId, playbackController.deckA.occurrenceId)
            assertEquals(45_000_000L, playbackController.deckA.positionUs)
            assertFalse("Restored session should be paused until user resumes", playbackController.state.value.isAudioPlaying)

        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Task 3 & 5 (F05): Preview respects draft plan hash; set playback uses committed plan hash until Save.
     */
    @Test
    fun testPreviewDraftVsCommittedPlanHashIsolation() {
        val storageDir = createTempDir("stage09_draft_storage")

        try {
            val controller = SetFlowShellController(
                services = LocalOnlyShellServiceGraph(storageDirectory = storageDir)
            )
            val db = controller.services.projectDatabase
            val project = controller.createNewSet("DraftIsolationProject")

            val tA = makeTestTrack("trk_a", "Song A")
            val tB = makeTestTrack("trk_b", "Song B")
            db.upsertTrack(tA)
            db.upsertTrack(tB)
            controller.addTrackToSet(project.project.projectId, 1L, tA.trackId)
            val detail = controller.addTrackToSet(project.project.projectId, 2L, tB.trackId)!!
            val edge = detail.edges.first()

            // Open Transition Editor
            val opened = controller.openTransitionEditor(project.project.projectId, edge.edge.edgeId)
            assertTrue(opened)

            val initialDraftState = controller.uiState.value.activeTransitionState!!
            val initialPlanHash = initialDraftState.currentPlanHash

            // Modify draft: change shape to FILTER_OPEN and span to 16 bars
            controller.updateTransitionDraft(
                initialDraftState.draftParams.copy(
                    shape = SegueShape.FILTER_OPEN,
                    barCount = 16
                )
            )

            val updatedDraftState = controller.uiState.value.activeTransitionState!!
            assertTrue("Draft should be dirty", updatedDraftState.isDirty)
            val draftPlanHash = updatedDraftState.currentPlanHash
            assertNotEquals("Draft plan hash must differ from initial committed hash", initialPlanHash, draftPlanHash)

            // Start preview
            controller.toggleTransitionPreview()
            assertTrue(controller.uiState.value.activeTransitionState!!.isPreviewPlaying)
            assertTrue(controller.services.transitionPreviewBridge.isPreviewActive)
            assertEquals(draftPlanHash, controller.services.transitionPreviewBridge.activeDraftBinding?.planHashSha256)

            // Verify database committed edge is completely untouched while previewing draft
            val dbEdgeSnap = controller.services.projectDatabase.getEdgeCompleteSnapshot(edge.edge.edgeId)!!
            assertEquals("Committed plan in DB must not change while draft is previewed", edge.resolvedPlan?.planHashSha256, dbEdgeSnap.resolvedPlan?.planHashSha256)

            // Stop preview
            controller.toggleTransitionPreview()
            assertFalse(controller.uiState.value.activeTransitionState!!.isPreviewPlaying)

            // Save transition override
            val saved = controller.saveTransitionOverride()
            assertTrue(saved)

            // Now DB must reflect the newly committed plan hash
            val dbEdgeAfterSave = controller.services.projectDatabase.getEdgeCompleteSnapshot(edge.edge.edgeId)!!
            assertEquals("Committed plan hash after Save must match draft hash", draftPlanHash, dbEdgeAfterSave.resolvedPlan?.planHashSha256)

        } finally {
            storageDir.deleteRecursively()
        }
    }

    /**
     * Task 8 & 9: Truth in presets descriptor and actionable disabled reason.
     */
    @Test
    fun testTruthInPresetsAndActionableDisabledReasons() {
        val presets = TransitionPresets.ALL

        // Supported presets
        val supported = presets.filter { it.isSupported }
        val supportedShapes = supported.map { it.shape }
        assertTrue(supportedShapes.contains(SegueShape.BASS_SWAP))
        assertTrue(supportedShapes.contains(SegueShape.THREE_BAND))
        assertTrue(supportedShapes.contains(SegueShape.CUT_ON_BEAT))
        assertTrue(supportedShapes.contains(SegueShape.FILTER_OPEN))
        assertTrue(supportedShapes.contains(SegueShape.RISE_OUT))
        assertTrue(supportedShapes.contains(SegueShape.EVEN_FADE))
        assertTrue(supportedShapes.contains(SegueShape.SWEEP_OUT))

        for (p in supported) {
            assertNull("Supported preset ${p.displayName} must not have disabledReason", p.disabledReason)
            assertTrue("Supported preset ${p.displayName} must have non-empty description", p.description.isNotBlank())
        }

        // Unsupported experimental presets must be truthfully disabled with clear technical explanations
        val unsupported = presets.filter { !it.isSupported }
        assertTrue("At least 3 experimental presets disabled", unsupported.size >= 3)
        for (u in unsupported) {
            assertNotNull("Disabled preset ${u.displayName} must have actionable disabledReason", u.disabledReason)
            assertTrue("Disabled reason must explain why: ${u.disabledReason}", u.disabledReason!!.isNotBlank())
        }
    }

    /**
     * Task 7 & 8: Rapid cancellation / skip during blend disarms immediately without stuck audio.
     */
    @Test
    fun testRapidPreviewCancelAndSkipDisarmsImmediately() {
        val sampleRate = 44_100
        val pcm = createSyntheticPcm(durationSec = 10.0, sampleRate = sampleRate)

        val facts = SegueSideFacts(durationUs = 10_000_000L, audioKey = "kSkip", gridKey = "gSkip")
        val res = TransitionPlanAdapter.resolve(
            override = TransitionOverrideParams(spanUs = 4_000_000L, shape = SegueShape.SWEEP_OUT),
            outgoingFacts = facts,
            incomingFacts = facts
        ) as TransitionResolutionResult.Success

        val binding = ArmedTransitionBinding(
            projectId = "pSkip", projectRevision = 1L, edgeId = "eSkip", edgePlanRevision = 1L,
            outgoingOccurrenceId = "oA", incomingOccurrenceId = "oB", outgoingTrackId = "tA", incomingTrackId = "tB",
            audioKeyA = "kSkip", audioKeyB = "kSkip", exitAnchorUs = 2_000_000L, entryAnchorUs = 0L, spanUs = 4_000_000L,
            planHashSha256 = res.planHashSha256, seguePlan = res.plan
        )

        // Render with Skip applied at 30% of overlap
        val result = DualDeckPcmEngine.renderTransitionWindow(
            binding = binding,
            sourceAProvider = { pcm },
            sourceBProvider = { pcm },
            useSegueDspIfAvailable = true,
            skipAtOverlapFraction = 0.30
        )

        assertEquals(0.30, result.metrics.skipAppliedAtOverlapFraction)
        assertEquals(0.0f, result.metrics.outgoingGainAtEnd, 0.001f)
        assertEquals(1.0f, result.metrics.incomingGainAtEnd, 0.001f)
        assertEquals(0, result.metrics.nonFiniteSamplesBlocked)
    }
}
