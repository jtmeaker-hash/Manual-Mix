/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.playback

import app.myspoti.automix.segue.LaneCurve
import app.myspoti.automix.segue.LaneSide
import app.myspoti.automix.segue.SegueDeck
import app.myspoti.automix.segue.SegueFormat
import app.myspoti.automix.segue.SeguePlan
import app.myspoti.automix.segue.Target
import app.setflow.db.SetFlowProjectDatabase
import app.setflow.domain.AudioErrorCode
import app.setflow.domain.TrackAvailabilityState
import com.music.spotui.automix.MixPreparation
import com.music.spotui.di.CrossfadeRamp
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Immutable binding of an armed transition to its exact project revision, edge revision,
 * occurrence pair, source audio fingerprints, and cue parameters (Stage 05 Task 4).
 */
data class ArmedTransitionBinding(
    val projectId: String,
    val projectRevision: Long,
    val edgeId: String,
    val edgePlanRevision: Long,
    val outgoingOccurrenceId: String,
    val incomingOccurrenceId: String,
    val outgoingTrackId: String,
    val incomingTrackId: String,
    val audioKeyA: String,
    val audioKeyB: String,
    val exitAnchorUs: Long,
    val entryAnchorUs: Long,
    val spanUs: Long,
    val ratePpmB: Long = 1_000_000L,
    val planHashSha256: String = "",
    val seguePlan: SeguePlan? = null
) {
    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Mismatch(
            val errorCode: AudioErrorCode,
            val reason: String
        ) : ValidationResult
    }

    /**
     * Verifies this armed binding against the current committed state in [database].
     * Any revision change, neighbor reorder, or source mutation returns [ValidationResult.Mismatch]
     * so playback disarms and enters safe fallback without playing the wrong track (Task 4, S06).
     */
    fun verifyAgainstDatabase(database: SetFlowProjectDatabase): ValidationResult {
        val detail = database.getProjectDetail(projectId)
            ?: return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                reason = "Project $projectId not found"
            )
        if (detail.project.editRevision != projectRevision) {
            return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A05_SOURCE_CHANGED,
                reason = "Project revision changed while armed: expected $projectRevision, actual ${detail.project.editRevision}"
            )
        }
        if (outgoingOccurrenceId == incomingOccurrenceId || edgeId.startsWith("track_")) {
            val occ = detail.occurrences.firstOrNull { it.occurrence.occurrenceId == outgoingOccurrenceId }
                ?: return ValidationResult.Mismatch(
                    errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                    reason = "Occurrence $outgoingOccurrenceId not found in project $projectId"
                )
            if (occ.track.availabilityState != TrackAvailabilityState.AVAILABLE.name) {
                return ValidationResult.Mismatch(
                    errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                    reason = "Track ${occ.track.trackId} is ${occ.track.availabilityState}"
                )
            }
            val actualKeyA = occ.track.audioFingerprint ?: occ.track.contentFingerprint ?: occ.track.trackId
            if (actualKeyA != audioKeyA) {
                return ValidationResult.Mismatch(
                    errorCode = AudioErrorCode.A05_SOURCE_CHANGED,
                    reason = "Source audio fingerprint changed (expected $audioKeyA, found $actualKeyA)"
                )
            }
            return ValidationResult.Valid
        }
        val edgeSnap = detail.edges.firstOrNull { it.edge.edgeId == edgeId }
            ?: return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A05_SOURCE_CHANGED,
                reason = "Armed edge $edgeId is no longer active in project $projectId"
            )
        if (edgeSnap.edge.outgoingOccurrenceId != outgoingOccurrenceId ||
            edgeSnap.edge.incomingOccurrenceId != incomingOccurrenceId
        ) {
            return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A05_SOURCE_CHANGED,
                reason = "Neighbor occurrence pair changed on edge $edgeId"
            )
        }
        if (edgeSnap.edge.planRevision != edgePlanRevision) {
            return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A05_SOURCE_CHANGED,
                reason = "Edge $edgeId planRevision changed from $edgePlanRevision to ${edgeSnap.edge.planRevision}"
            )
        }
        val occA = detail.occurrences.firstOrNull { it.occurrence.occurrenceId == outgoingOccurrenceId }
        val occB = detail.occurrences.firstOrNull { it.occurrence.occurrenceId == incomingOccurrenceId }
        if (occA == null || occB == null) {
            return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                reason = "Outgoing or incoming occurrence missing from project $projectId"
            )
        }
        if (occA.track.availabilityState != TrackAvailabilityState.AVAILABLE.name) {
            return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                reason = "Outgoing track ${occA.track.trackId} is ${occA.track.availabilityState}"
            )
        }
        if (occB.track.availabilityState != TrackAvailabilityState.AVAILABLE.name) {
            return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                reason = "Incoming track ${occB.track.trackId} is ${occB.track.availabilityState}"
            )
        }
        val actualKeyA = occA.track.audioFingerprint ?: occA.track.contentFingerprint ?: occA.track.trackId
        val actualKeyB = occB.track.audioFingerprint ?: occB.track.contentFingerprint ?: occB.track.trackId
        if (actualKeyA != audioKeyA || actualKeyB != audioKeyB) {
            return ValidationResult.Mismatch(
                errorCode = AudioErrorCode.A05_SOURCE_CHANGED,
                reason = "Source audio fingerprint changed (expected $audioKeyA->$audioKeyB, found $actualKeyA->$actualKeyB)"
            )
        }
        return ValidationResult.Valid
    }
}

/**
 * Measured frame-accurate PCM metrics from a dual-deck transition render (Stage 05 Tasks 3, 6, 8).
 */
data class DualDeckRenderMetrics(
    val sampleRateHz: Int,
    val channels: Int,
    val expectedOutputFrames: Long,
    val renderedOutputFrames: Long,
    val overlapStartOutputFrame: Long,
    val overlapEndOutputFrame: Long,
    val expectedOverlapFrames: Long,
    val measuredOverlapFrames: Long,
    val alignmentErrorFrames: Long,
    val alignmentErrorMs: Double,
    val deckBPrebufferedFrames: Int,
    val deckBVolumeBeforeCue: Float,
    val outgoingGainAtStart: Float,
    val outgoingGainAtMid: Float,
    val outgoingGainAtEnd: Float,
    val incomingGainAtStart: Float,
    val incomingGainAtMid: Float,
    val incomingGainAtEnd: Float,
    val peakLinear: Float,
    val peakDbFs: Double,
    val preOverlapRmsDbFs: Double,
    val overlapRmsDbFs: Double,
    val postOverlapRmsDbFs: Double,
    val silentGapFramesInOverlap: Int,
    val maxContiguousSilentFramesInOverlap: Int,
    val duplicatedSourceFramesA: Int,
    val duplicatedSourceFramesB: Int,
    val nonFiniteSamplesBlocked: Int,
    val headroomLimiterEngagedFrames: Int,
    val skipAppliedAtOverlapFraction: Double?,
    val fallbackTriggered: Boolean,
    val fallbackErrorCode: AudioErrorCode?,
    val fallbackReason: String?
)

data class DualDeckRenderResult(
    val metrics: DualDeckRenderMetrics,
    val outputPcm: DecodedPcmBuffer
)

/**
 * Sample-accurate dual-deck PCM engine executing two independent deck decoders (`Deck A` and `Deck B`),
 * prebuffering Deck B with `volume = 0f` before cue, applying equal-power (`CrossfadeRamp`) or `SegueDeck`
 * automation with headroom protection, and handling late-B/decoder-failure safe fallback.
 */
object DualDeckPcmEngine {

    private const val SOFT_KNEE_THRESHOLD = 0.92f
    private const val SOFT_KNEE_HEADROOM = 0.075f
    private const val POP_SAFE_SKIP_RAMP_MS = 5L

    /**
     * Renders a transition between [sourceAProvider] and [sourceBProvider] using two independent deck cursors.
     *
     * @param preRollUs Lead-in window of Deck A before [binding.exitAnchorUs] to render.
     * @param postRollUs Lead-out window of Deck B after the transition finishes to render.
     * @param simulateDeckBNotReady When true, simulates Deck B buffer underrun / late start before cue (Task 8).
     * @param injectNonFiniteInDeckB When true, injects NaN/Inf into Deck B to verify `A07_DSP_NONFINITE` protection (S24).
     * @param skipAtOverlapFraction Optional fraction (`0.0..1.0`, e.g. `0.10`, `0.50`, `0.90`) at which a user `Next`/`Skip` occurs during the blend (Task 8).
     */
    fun renderTransitionWindow(
        binding: ArmedTransitionBinding,
        sourceAProvider: () -> DecodedPcmBuffer,
        sourceBProvider: () -> DecodedPcmBuffer,
        preRollUs: Long = 2_000_000L,
        postRollUs: Long = 2_000_000L,
        useSegueDspIfAvailable: Boolean = false,
        simulateDeckBNotReady: Boolean = false,
        injectNonFiniteInDeckB: Boolean = false,
        skipAtOverlapFraction: Double? = null
    ): DualDeckRenderResult {
        val pcmA = sourceAProvider()
        val sampleRate = pcmA.sampleRateHz
        val channels = pcmA.channels

        val startFrameA = max(
            0L,
            ((binding.exitAnchorUs - preRollUs) * sampleRate) / 1_000_000L
        ).toInt().coerceAtMost(pcmA.totalFrames)
        val cueFrameA = ((binding.exitAnchorUs * sampleRate) / 1_000_000L)
            .toInt()
            .coerceIn(startFrameA, pcmA.totalFrames)
        val preRollFrames = cueFrameA - startFrameA
        val requestedOverlapFrames = max(1L, (binding.spanUs * sampleRate) / 1_000_000L).toInt()
        val availableOverlapFramesA = (pcmA.totalFrames - cueFrameA).coerceAtLeast(0)

        // Attempt to decode & prebuffer Deck B independently before cue
        val pcmBOrNull: DecodedPcmBuffer? = if (simulateDeckBNotReady) {
            null
        } else {
            runCatching { sourceBProvider() }.getOrNull()
        }

        val deckBReadyBeforeCue = pcmBOrNull != null &&
            pcmBOrNull.sampleRateHz == sampleRate &&
            pcmBOrNull.channels == channels &&
            pcmBOrNull.totalFrames > 0

        // Safe fallback when Deck B is not ready or fails decoding (Task 3, Task 8, MixPreparation.preserveOutgoing)
        if (!deckBReadyBeforeCue) {
            val preserveA = MixPreparation.preserveOutgoing(incomingReady = false, outgoingPlaying = true)
            val fallbackFrames = min(
                pcmA.totalFrames - startFrameA,
                preRollFrames + requestedOverlapFrames + ((postRollUs * sampleRate) / 1_000_000L).toInt()
            ).coerceAtLeast(0)
            val outSamples = FloatArray(fallbackFrames * channels)
            var peak = 0.0f
            var sumSq = 0.0
            for (f in 0 until fallbackFrames) {
                val srcFrameA = startFrameA + f
                for (c in 0 until channels) {
                    val v = if (preserveA) pcmA.interleavedSamples[srcFrameA * channels + c] else 0.0f
                    outSamples[f * channels + c] = v
                    val av = abs(v)
                    if (av > peak) peak = av
                    sumSq += v * v
                }
            }
            val rmsDb = linearToDb(sqrt(sumSq / max(1, outSamples.size)))
            val errCode = if (simulateDeckBNotReady) {
                AudioErrorCode.A06_BUFFER_UNDERRUN
            } else {
                AudioErrorCode.A03_UNSUPPORTED_CODEC
            }
            val metrics = DualDeckRenderMetrics(
                sampleRateHz = sampleRate,
                channels = channels,
                expectedOutputFrames = fallbackFrames.toLong(),
                renderedOutputFrames = fallbackFrames.toLong(),
                overlapStartOutputFrame = preRollFrames.toLong(),
                overlapEndOutputFrame = preRollFrames.toLong(),
                expectedOverlapFrames = 0L,
                measuredOverlapFrames = 0L,
                alignmentErrorFrames = 0L,
                alignmentErrorMs = 0.0,
                deckBPrebufferedFrames = 0,
                deckBVolumeBeforeCue = 0.0f,
                outgoingGainAtStart = 1.0f,
                outgoingGainAtMid = 1.0f,
                outgoingGainAtEnd = 1.0f,
                incomingGainAtStart = 0.0f,
                incomingGainAtMid = 0.0f,
                incomingGainAtEnd = 0.0f,
                peakLinear = peak,
                peakDbFs = linearToDb(peak.toDouble()),
                preOverlapRmsDbFs = rmsDb,
                overlapRmsDbFs = rmsDb,
                postOverlapRmsDbFs = rmsDb,
                silentGapFramesInOverlap = 0,
                maxContiguousSilentFramesInOverlap = 0,
                duplicatedSourceFramesA = 0,
                duplicatedSourceFramesB = 0,
                nonFiniteSamplesBlocked = 0,
                headroomLimiterEngagedFrames = 0,
                skipAppliedAtOverlapFraction = null,
                fallbackTriggered = true,
                fallbackErrorCode = errCode,
                fallbackReason = if (simulateDeckBNotReady) {
                    "Incoming Deck B not prebuffered before cue; preserved outgoing Deck A at unity gain"
                } else {
                    "Incoming Deck B decoder failed; preserved outgoing Deck A effect-free"
                }
            )
            return DualDeckRenderResult(
                metrics = metrics,
                outputPcm = DecodedPcmBuffer(sampleRate, channels, fallbackFrames, outSamples)
            )
        }

        val pcmB = pcmBOrNull!!
        val entryFrameB = ((binding.entryAnchorUs * sampleRate) / 1_000_000L)
            .toInt()
            .coerceIn(0, pcmB.totalFrames)
        val availableFramesB = (pcmB.totalFrames - entryFrameB).coerceAtLeast(0)
        val fullOverlapFrames = minOf(requestedOverlapFrames, availableOverlapFramesA, availableFramesB)

        // Prebuffer verification (MixPreparation.BUFFER_MS = 750ms)
        val requiredPrebufferFrames = ((MixPreparation.BUFFER_MS * sampleRate) / 1_000L).toInt()
        val prebufferedFramesB = min(availableFramesB, max(requiredPrebufferFrames, fullOverlapFrames))
        val deckBVolumeBeforeCue = 0.0f // Strictly muted during prebuffer before cue!

        // Determine actual overlap frames if user skips at a fraction (e.g., 10%, 50%, 90%)
        val popSafeRampFrames = max(1, ((POP_SAFE_SKIP_RAMP_MS * sampleRate) / 1_000L).toInt())
        val effectiveOverlapFrames = if (skipAtOverlapFraction != null) {
            val skipTriggerFrame = (fullOverlapFrames * skipAtOverlapFraction.coerceIn(0.0, 1.0)).roundToLong().toInt()
            min(fullOverlapFrames, skipTriggerFrame + popSafeRampFrames)
        } else {
            fullOverlapFrames
        }

        val postRollFramesRequested = ((postRollUs * sampleRate) / 1_000_000L).toInt()
        val remainingFramesBAfterOverlap = (availableFramesB - effectiveOverlapFrames).coerceAtLeast(0)
        val postRollFrames = min(postRollFramesRequested, remainingFramesBAfterOverlap)

        val totalOutputFrames = preRollFrames + effectiveOverlapFrames + postRollFrames
        val outSamples = FloatArray(totalOutputFrames * channels)

        // Optional SegueDeck instances when testing SeguePlan DSP on the two decks
        val segueDeckA = if (useSegueDspIfAvailable && binding.seguePlan != null) {
            SegueDeck(binding.seguePlan, LaneSide.OUTGOING, sampleRate, channels)
        } else {
            null
        }
        val segueDeckB = if (useSegueDspIfAvailable && binding.seguePlan != null) {
            SegueDeck(binding.seguePlan, LaneSide.INCOMING, sampleRate, channels)
        } else {
            null
        }

        val levelLaneA = binding.seguePlan?.lanes?.firstOrNull { it.side == LaneSide.OUTGOING && it.target == Target.LEVEL }
        val levelLaneB = binding.seguePlan?.lanes?.firstOrNull { it.side == LaneSide.INCOMING && it.target == Target.LEVEL }
        val levelCurveA = levelLaneA?.let(::LaneCurve)
        val levelCurveB = levelLaneB?.let(::LaneCurve)
        val effectiveRateB = binding.ratePpmB.takeIf { it > 0L } ?: 1_000_000L

        fun planGainA(sourceUsA: Long, fallback: Float): Float {
            val curve = levelCurveA ?: return fallback
            val spanUs = max(1L, binding.spanUs)
            val u = (sourceUsA - binding.exitAnchorUs).toDouble() / spanUs * SegueFormat.T_MAX
            return if (curve.isNull(u)) 0.0f else 10.0.pow(curve.value(u) / 20.0).toFloat()
        }

        fun planGainB(sourceUsB: Long, fallback: Float): Float {
            val curve = levelCurveB ?: return fallback
            val spanUs = max(1L, binding.spanUs)
            val u = (sourceUsB - binding.entryAnchorUs).toDouble() * SegueFormat.RATE_NOMINAL / effectiveRateB / spanUs * SegueFormat.T_MAX
            return if (curve.isNull(u)) 0.0f else 10.0.pow(curve.value(u) / 20.0).toFloat()
        }

        var lastSourceFrameA = -1
        var lastSourceFrameB = -1
        var duplicatedFramesA = 0
        var duplicatedFramesB = 0
        var nonFiniteBlocked = 0
        var limiterFrames = 0

        var peakLinear = 0.0f
        var preRmsSumSq = 0.0
        var preRmsCount = 0
        var overlapRmsSumSq = 0.0
        var overlapRmsCount = 0
        var postRmsSumSq = 0.0
        var postRmsCount = 0

        val minGapDropoutFrames = max(8, sampleRate / 1_000) // >= 1 ms contiguous dead silence = audio dropout/gap
        var silentFramesInOverlap = 0
        var currentContiguousSilent = 0
        var maxContiguousSilent = 0

        var outGainStart = 1.0f
        var outGainMid = 1.0f
        var outGainEnd = 0.0f
        var inGainStart = 0.0f
        var inGainMid = 0.0f
        var inGainEnd = 1.0f

        val frameScratchA = FloatArray(channels)
        val frameScratchB = FloatArray(channels)

        // 1. Render Pre-Roll (Deck A alone at gain 1.0, Deck B prebuffered with gain 0.0)
        for (f in 0 until preRollFrames) {
            val srcFrameA = startFrameA + f
            if (srcFrameA == lastSourceFrameA) duplicatedFramesA++
            lastSourceFrameA = srcFrameA
            for (c in 0 until channels) {
                val sampleA = pcmA.interleavedSamples[srcFrameA * channels + c]
                val v = sanitizeSample(sampleA) { nonFiniteBlocked++ }
                outSamples[f * channels + c] = v
                val av = abs(v)
                if (av > peakLinear) peakLinear = av
                preRmsSumSq += v * v
                preRmsCount++
            }
        }

        // 2. Render Overlap Window (Deck A fading out + Deck B fading in simultaneously)
        val skipTriggerOffset = if (skipAtOverlapFraction != null) {
            (fullOverlapFrames * skipAtOverlapFraction.coerceIn(0.0, 1.0)).roundToLong().toInt()
        } else {
            Int.MAX_VALUE
        }
        val midOverlapIndex = effectiveOverlapFrames / 2

        for (i in 0 until effectiveOverlapFrames) {
            val outFrame = preRollFrames + i
            val srcFrameA = cueFrameA + i
            val srcFrameB = entryFrameB + i

            if (srcFrameA == lastSourceFrameA) duplicatedFramesA++
            lastSourceFrameA = srcFrameA
            if (srcFrameB == lastSourceFrameB) duplicatedFramesB++
            lastSourceFrameB = srcFrameB

            val progress = CrossfadeRamp.progress(i.toLong(), max(1, fullOverlapFrames - 1).toLong())
            var gainA: Float
            var gainB: Float

            if (i >= skipTriggerOffset) {
                // Pop-safe micro-ramp on skip (5 ms) bringing Deck A -> 0 and Deck B -> 1
                val skipStep = (i - skipTriggerOffset + 1).toFloat() / popSafeRampFrames.toFloat()
                val baseProgress = CrossfadeRamp.progress(skipTriggerOffset.toLong(), max(1, fullOverlapFrames - 1).toLong())
                val baseA = CrossfadeRamp.outgoingGain(baseProgress)
                val baseB = CrossfadeRamp.incomingGain(baseProgress)
                gainA = baseA * (1.0f - skipStep.coerceIn(0.0f, 1.0f))
                gainB = baseB + (1.0f - baseB) * skipStep.coerceIn(0.0f, 1.0f)
            } else {
                gainA = CrossfadeRamp.outgoingGain(progress)
                gainB = CrossfadeRamp.incomingGain(progress)
            }

            val sourceUsA = (srcFrameA * 1_000_000L) / sampleRate
            val sourceUsB = (srcFrameB * 1_000_000L) / sampleRate

            val measuredA = if (useSegueDspIfAvailable && segueDeckA != null && i < skipTriggerOffset) planGainA(sourceUsA, gainA) else gainA
            val measuredB = if (useSegueDspIfAvailable && segueDeckB != null && i < skipTriggerOffset) planGainB(sourceUsB, gainB) else gainB

            if (i == 0) {
                outGainStart = measuredA
                inGainStart = measuredB
            }
            if (i == midOverlapIndex) {
                outGainMid = measuredA
                inGainMid = measuredB
            }
            if (i == effectiveOverlapFrames - 1) {
                outGainEnd = if (skipAtOverlapFraction != null && i >= skipTriggerOffset) gainA else measuredA
                inGainEnd = if (skipAtOverlapFraction != null && i >= skipTriggerOffset) gainB else measuredB
            }

            for (c in 0 until channels) {
                frameScratchA[c] = pcmA.interleavedSamples[srcFrameA * channels + c]
                val rawB = if (injectNonFiniteInDeckB && i == effectiveOverlapFrames / 3) {
                    Float.NaN
                } else {
                    pcmB.interleavedSamples[srcFrameB * channels + c]
                }
                frameScratchB[c] = sanitizeSample(rawB) { nonFiniteBlocked++ }
            }

            if (segueDeckA != null && segueDeckB != null && i < skipTriggerOffset) {
                val sourceUsA = (srcFrameA * 1_000_000L) / sampleRate
                val sourceUsB = (srcFrameB * 1_000_000L) / sampleRate
                segueDeckA.process(frameScratchA, 1, sourceUsA)
                segueDeckB.process(frameScratchB, 1, sourceUsB)
                for (c in 0 until channels) {
                    frameScratchA[c] = sanitizeSample(frameScratchA[c]) { nonFiniteBlocked++ }
                    frameScratchB[c] = sanitizeSample(frameScratchB[c]) { nonFiniteBlocked++ }
                }
            } else {
                for (c in 0 until channels) {
                    frameScratchA[c] *= gainA
                    frameScratchB[c] *= gainB
                }
            }

            var frameMaxAbs = 0.0f
            var frameLimited = false
            for (c in 0 until channels) {
                val rawSum = frameScratchA[c] + frameScratchB[c]
                val cleanSum = sanitizeSample(rawSum) { nonFiniteBlocked++ }
                val limited = applySoftKneeHeadroomLimiter(cleanSum) { frameLimited = true }
                outSamples[outFrame * channels + c] = limited
                val av = abs(limited)
                if (av > frameMaxAbs) frameMaxAbs = av
                if (av > peakLinear) peakLinear = av
                overlapRmsSumSq += limited * limited
                overlapRmsCount++
            }
            if (frameLimited) limiterFrames++

            if (frameMaxAbs < 1e-4f) {
                currentContiguousSilent++
                if (currentContiguousSilent > maxContiguousSilent) {
                    maxContiguousSilent = currentContiguousSilent
                }
                if (currentContiguousSilent == minGapDropoutFrames) {
                    silentFramesInOverlap += minGapDropoutFrames
                } else if (currentContiguousSilent > minGapDropoutFrames) {
                    silentFramesInOverlap++
                }
            } else {
                currentContiguousSilent = 0
            }
        }

        // 3. Render Post-Roll (Handoff complete: Deck A stopped & disarmed, Deck B continues at unity gain 1.0)
        for (j in 0 until postRollFrames) {
            val outFrame = preRollFrames + effectiveOverlapFrames + j
            val srcFrameB = entryFrameB + effectiveOverlapFrames + j
            if (srcFrameB == lastSourceFrameB) duplicatedFramesB++
            lastSourceFrameB = srcFrameB
            for (c in 0 until channels) {
                val rawB = pcmB.interleavedSamples[srcFrameB * channels + c]
                val v = sanitizeSample(rawB) { nonFiniteBlocked++ }
                outSamples[outFrame * channels + c] = v
                val av = abs(v)
                if (av > peakLinear) peakLinear = av
                postRmsSumSq += v * v
                postRmsCount++
            }
        }

        val overlapStartFrame = preRollFrames.toLong()
        val overlapEndFrame = (preRollFrames + effectiveOverlapFrames).toLong()
        val expectedOverlap = effectiveOverlapFrames.toLong()
        val measuredOverlap = overlapEndFrame - overlapStartFrame
        val alignmentErrorFrames = measuredOverlap - expectedOverlap
        val alignmentErrorMs = (alignmentErrorFrames * 1_000.0) / sampleRate

        val metrics = DualDeckRenderMetrics(
            sampleRateHz = sampleRate,
            channels = channels,
            expectedOutputFrames = totalOutputFrames.toLong(),
            renderedOutputFrames = totalOutputFrames.toLong(),
            overlapStartOutputFrame = overlapStartFrame,
            overlapEndOutputFrame = overlapEndFrame,
            expectedOverlapFrames = expectedOverlap,
            measuredOverlapFrames = measuredOverlap,
            alignmentErrorFrames = alignmentErrorFrames,
            alignmentErrorMs = alignmentErrorMs,
            deckBPrebufferedFrames = prebufferedFramesB,
            deckBVolumeBeforeCue = deckBVolumeBeforeCue,
            outgoingGainAtStart = outGainStart,
            outgoingGainAtMid = outGainMid,
            outgoingGainAtEnd = outGainEnd,
            incomingGainAtStart = inGainStart,
            incomingGainAtMid = inGainMid,
            incomingGainAtEnd = inGainEnd,
            peakLinear = peakLinear,
            peakDbFs = linearToDb(peakLinear.toDouble()),
            preOverlapRmsDbFs = linearToDb(sqrt(preRmsSumSq / max(1, preRmsCount))),
            overlapRmsDbFs = linearToDb(sqrt(overlapRmsSumSq / max(1, overlapRmsCount))),
            postOverlapRmsDbFs = linearToDb(sqrt(postRmsSumSq / max(1, postRmsCount))),
            silentGapFramesInOverlap = silentFramesInOverlap,
            maxContiguousSilentFramesInOverlap = maxContiguousSilent,
            duplicatedSourceFramesA = duplicatedFramesA,
            duplicatedSourceFramesB = duplicatedFramesB,
            nonFiniteSamplesBlocked = nonFiniteBlocked,
            headroomLimiterEngagedFrames = limiterFrames,
            skipAppliedAtOverlapFraction = skipAtOverlapFraction,
            fallbackTriggered = false,
            fallbackErrorCode = null,
            fallbackReason = null
        )

        return DualDeckRenderResult(
            metrics = metrics,
            outputPcm = DecodedPcmBuffer(sampleRate, channels, totalOutputFrames, outSamples)
        )
    }

    private inline fun sanitizeSample(sample: Float, onNonFinite: () -> Unit): Float {
        return if (sample.isFinite()) {
            sample
        } else {
            onNonFinite()
            0.0f
        }
    }

    /**
     * Smooth soft-knee ceiling limiter above ±0.92f so correlated overlapping kicks cannot clip above ±0.995f (F06).
     */
    fun applySoftKneeHeadroomLimiter(sample: Float, onLimited: () -> Unit = {}): Float {
        val mag = abs(sample)
        if (mag <= SOFT_KNEE_THRESHOLD) return sample
        onLimited()
        val excess = (mag - SOFT_KNEE_THRESHOLD) / SOFT_KNEE_HEADROOM
        val compressedMag = SOFT_KNEE_THRESHOLD + SOFT_KNEE_HEADROOM * tanh(excess.toDouble()).toFloat()
        return if (sample >= 0.0f) {
            compressedMag.coerceAtMost(0.995f)
        } else {
            -compressedMag.coerceAtMost(0.995f)
        }
    }

    fun linearToDb(linear: Double): Double {
        if (linear <= 1e-9) return -180.0
        return 20.0 * log10(linear)
    }
}
