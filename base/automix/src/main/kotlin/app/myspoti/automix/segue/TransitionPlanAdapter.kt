/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

import java.math.BigDecimal
import kotlin.math.roundToLong

/**
 * Parameter object capturing user-editable transition override fields (Stage 07, STAGES/07_SAVED_TRANSITIONS.txt).
 * Preserves exact microseconds and integer knots; no lossy string float storage.
 */
data class TransitionOverrideParams(
    val exitAnchorUs: Long? = null,
    val entryAnchorUs: Long? = null,
    val spanUs: Long? = null,
    val barCount: Int? = null,
    val pulseMode: PulseState? = null,
    val plannedRatePpm: Long? = null,
    val shape: SegueShape? = null,
    val customLanesJson: String? = null,
    val swapShare: Double = 0.5,
    val eqEnabled: Boolean = true,
    val echoUs: Long? = null,
    val userEditedFieldsBitset: Int = 0
) {
    companion object {
        const val BIT_EXIT_ANCHOR = 1 shl 0
        const val BIT_ENTRY_ANCHOR = 1 shl 1
        const val BIT_SPAN = 1 shl 2
        const val BIT_BAR_COUNT = 1 shl 3
        const val BIT_PULSE_MODE = 1 shl 4
        const val BIT_PLANNED_RATE = 1 shl 5
        const val BIT_LANE_OVERRIDES = 1 shl 6
        const val BIT_PRESET_ID = 1 shl 7

        fun hasBit(bitset: Int, bit: Int): Boolean = (bitset and bit) != 0
    }
}

/**
 * Result of resolving and validating a transition plan.
 */
sealed class TransitionResolutionResult {
    data class Success(
        val plan: SeguePlan,
        val canonicalJson: String,
        val planHashSha256: String
    ) : TransitionResolutionResult()

    data class Failure(
        val violations: List<SegueViolation>,
        val humanReadableErrors: List<String>
    ) : TransitionResolutionResult()
}

/**
 * Adapter that compiles and resolves an AutoPlan + partial Override into a validated, executable SeguePlan (segue/1).
 *
 * Guarantees:
 * - 4/8/16/32 bar configuration computed with exact microsecond precision; NO 12-second or 16-bar auto clamp.
 * - Source microseconds and normalized 0..1e6 knot timestamps strictly preserved.
 * - Outgoing track plays at nominal speed; incoming track accounts for ratePpm time stretch under Clock.ELAPSED.
 * - SegueValidator.structural and SegueValidator.against are executed on every resolution.
 */
object TransitionPlanAdapter {

    val ENGINE_ORIGIN = SeguePlan.Origin("setflow-engine", "transition-override")

    /**
     * Resolves an executable SeguePlan by combining [autoPlan] with user [override] parameters,
     * checked against [outgoingFacts] and [incomingFacts].
     */
    fun resolve(
        autoPlan: SeguePlan? = null,
        override: TransitionOverrideParams = TransitionOverrideParams(),
        outgoingFacts: SegueSideFacts,
        incomingFacts: SegueSideFacts,
        outgoingBpm: Double? = null,
        incomingBpm: Double? = null,
        tuning: SegueTuning = SegueTuning()
    ): TransitionResolutionResult {
        // 1. Determine effective tempo & pulse state
        val hasGridA = !outgoingFacts.gridKey.isNullOrBlank()
        val hasGridB = !incomingFacts.gridKey.isNullOrBlank()

        val pulseMode = override.pulseMode
            ?: autoPlan?.pulse?.state
            ?: if (hasGridA && hasGridB) PulseState.LOCKED else PulseState.FREE

        val effectiveBpm = outgoingBpm
            ?: autoPlan?.pulse?.tempoMicroPerMinute?.let { it.toDouble() / 1e6 }
            ?: 120.0

        val tempoMicroPerMinute = if (pulseMode == PulseState.FREE) {
            null
        } else {
            autoPlan?.pulse?.tempoMicroPerMinute ?: (effectiveBpm * 1e6).roundToLong().coerceAtLeast(1L)
        }

        // 2. Determine span & duration (4 / 8 / 16 / 32 bars or explicit seconds / spanUs)
        val barCount = override.barCount ?: autoPlan?.span?.musicalUnits
        val barPeriodUs = (240.0 / effectiveBpm * 1e6).roundToLong()

        val lengthUs: Long = when {
            barCount != null && barCount > 0 -> {
                // Exact bar length without auto clamping
                barCount * barPeriodUs
            }
            override.spanUs != null && override.spanUs > 0L -> {
                override.spanUs
            }
            autoPlan?.span?.lengthUs != null && autoPlan.span.lengthUs > 0L -> {
                autoPlan.span.lengthUs
            }
            else -> 8_000_000L // 8s default fallback
        }

        val span = SeguePlan.Span(
            lengthUs = lengthUs,
            musicalUnits = barCount,
            unitKind = if (barCount != null) UnitKind.BAR else UnitKind.BEAT,
            clock = Clock.ELAPSED
        )

        // 3. Determine anchors
        val exitAnchorUs = override.exitAnchorUs
            ?: autoPlan?.outgoing?.anchorUs
            ?: (outgoingFacts.durationUs - lengthUs).coerceAtLeast(0L)

        val entryAnchorUs = override.entryAnchorUs
            ?: autoPlan?.incoming?.anchorUs
            ?: 0L

        // 4. Determine incoming playback rate (ppm)
        val plannedRatePpm = override.plannedRatePpm
            ?: autoPlan?.incoming?.ratePpm
            ?: computeMatchingRatePpm(outgoingBpm, incomingBpm)
            ?: SegueFormat.RATE_NOMINAL

        // 5. Build Side records
        val outgoingSide = SeguePlan.Side(
            audioKey = outgoingFacts.audioKey,
            gridKey = outgoingFacts.gridKey,
            anchorUs = exitAnchorUs,
            ratePpm = SegueFormat.RATE_NOMINAL,
            listenerRate = autoPlan?.outgoing?.listenerRate ?: SegueFormat.RATE_NOMINAL
        )

        val incomingSide = SeguePlan.Side(
            audioKey = incomingFacts.audioKey,
            gridKey = incomingFacts.gridKey,
            anchorUs = entryAnchorUs,
            ratePpm = plannedRatePpm,
            listenerRate = autoPlan?.incoming?.listenerRate ?: SegueFormat.RATE_NOMINAL
        )

        // 6. Pulse record
        val pulse = SeguePlan.Pulse(
            state = pulseMode,
            tempoMicroPerMinute = tempoMicroPerMinute,
            residualUs = if (pulseMode == PulseState.FREE) null else (autoPlan?.pulse?.residualUs ?: 0L)
        )

        // 7. Compile or override automation lanes
        val compiler = SegueCompiler(tuning)
        val effectiveShape = override.shape ?: (if (pulseMode == PulseState.LOCKED) SegueShape.BASS_SWAP else SegueShape.EVEN_FADE)
        val beatShare = if (barCount != null && barCount > 0) 1.0 / (barCount * 4) else 0.1
        val swap = override.swapShare.coerceIn(0.01, 0.99)

        val lanes: List<SeguePlan.Lane> = try {
            if (!override.customLanesJson.isNullOrBlank()) {
                parseLanesJson(override.customLanesJson)
            } else if (override.shape != null) {
                compileShapeLanes(compiler, effectiveShape, swap, beatShare, override.eqEnabled)
            } else if (autoPlan != null && autoPlan.lanes.isNotEmpty()) {
                autoPlan.lanes
            } else {
                compileShapeLanes(compiler, effectiveShape, swap, beatShare, override.eqEnabled)
            }
        } catch (e: Exception) {
            val errors = listOf("Failed to compile or parse automation lanes: ${e.message}")
            return TransitionResolutionResult.Failure(listOf(SegueViolation.DUPLICATE_LANE), errors)
        }

        // 8. Construct SeguePlan
        val plan = SeguePlan(
            origin = autoPlan?.origin ?: ENGINE_ORIGIN,
            outgoing = outgoingSide,
            incoming = incomingSide,
            span = span,
            pulse = pulse,
            lanes = lanes,
            gestures = emptyList(), // Gestures disabled for engine compatibility
            schema = SegueFormat.SCHEMA,
            echoUs = override.echoUs ?: autoPlan?.echoUs
        )

        // 9. Run strict validation against structural rules and track facts
        val violations = SegueValidator.against(plan, outgoingFacts, incomingFacts)
        if (violations.isNotEmpty()) {
            val humanErrors = violations.map { violationToHumanMessage(it, plan, outgoingFacts, incomingFacts) }
            return TransitionResolutionResult.Failure(violations, humanErrors)
        }

        val canonicalJson = SegueCodec.encode(plan)
        val fingerprint = SegueCodec.fingerprint(plan)
        return TransitionResolutionResult.Success(
            plan = plan,
            canonicalJson = canonicalJson,
            planHashSha256 = fingerprint
        )
    }

    private fun compileShapeLanes(
        compiler: SegueCompiler,
        shape: SegueShape,
        swap: Double,
        beatShare: Double,
        eq: Boolean
    ): List<SeguePlan.Lane> {
        return when (shape) {
            SegueShape.BASS_SWAP,
            SegueShape.THREE_BAND,
            SegueShape.CUT_ON_BEAT,
            SegueShape.FILTER_OPEN,
            SegueShape.RISE_OUT,
            SegueShape.ECHO_TAIL -> {
                compiler.locked(shape, swap, beatShare, matchA = 1.0, matchB = 1.0, eq = eq)
            }
            SegueShape.SWEEP_OUT,
            SegueShape.EVEN_FADE,
            SegueShape.QUICK_OUT,
            SegueShape.LATE_OUT,
            SegueShape.ECHO_OUT,
            SegueShape.WASH_OUT -> {
                compiler.filtered(shape, beatShare, matchA = 1.0, matchB = 1.0, eq = eq, at = swap)
            }
        }
    }

    private fun computeMatchingRatePpm(outgoingBpm: Double?, incomingBpm: Double?): Long? {
        if (outgoingBpm == null || incomingBpm == null || outgoingBpm <= 0.0 || incomingBpm <= 0.0) return null
        val ratio = outgoingBpm / incomingBpm
        // Limit stretch between 0.85 and 1.15
        if (ratio !in 0.85..1.15) return null
        return (ratio * SegueFormat.RATE_NOMINAL).roundToLong()
    }

    private fun parseLanesJson(jsonStr: String): List<SeguePlan.Lane> {
        val root = SegueJson.read(jsonStr) as? List<*> ?: throw SegueFormatException("lanes must be a JSON array")
        return root.map { any ->
            val m = any as? Map<*, *> ?: throw SegueFormatException("lane item must be an object")
            val sideWire = m["side"] as? String ?: throw SegueFormatException("missing side")
            val targetWire = m["target"] as? String ?: throw SegueFormatException("missing target")
            val unitWire = m["unit"] as? String ?: throw SegueFormatException("missing unit")

            val side = LaneSide.values().firstOrNull { it.wire == sideWire }
                ?: throw SegueFormatException("invalid side: $sideWire")
            val target = Target.values().firstOrNull { it.wire == targetWire }
                ?: throw SegueFormatException("invalid target: $targetWire")
            val unit = SegueUnit.values().firstOrNull { it.wire == unitWire }
                ?: throw SegueFormatException("invalid unit: $unitWire")

            val rawKnots = m["knots"] as? List<*> ?: throw SegueFormatException("missing knots array")
            val knots = rawKnots.map { k ->
                val arr = k as? List<*> ?: throw SegueFormatException("knot must be [t, value, form]")
                if (arr.size != 3) throw SegueFormatException("knot needs 3 elements: [t, value, form]")
                val t = when (val tVal = arr[0]) {
                    is BigDecimal -> tVal.intValueExact()
                    is Number -> tVal.toInt()
                    else -> throw SegueFormatException("knot t must be numeric")
                }
                val valueMilli: Long? = when (val vVal = arr[1]) {
                    null -> null
                    is BigDecimal -> vVal.movePointRight(3).longValueExact()
                    is Number -> (vVal.toDouble() * 1000.0).roundToLong()
                    else -> throw SegueFormatException("knot value must be numeric or null")
                }
                val formWire = arr[2] as? String ?: throw SegueFormatException("knot form must be string")
                val form = KnotForm.parse(formWire) ?: throw SegueFormatException("invalid knot form: $formWire")
                SeguePlan.Knot(t, valueMilli, form)
            }
            SeguePlan.Lane(side, target, unit, knots)
        }
    }

    fun violationToHumanMessage(
        v: SegueViolation,
        plan: SeguePlan,
        outgoingFacts: SegueSideFacts,
        incomingFacts: SegueSideFacts
    ): String = when (v) {
        SegueViolation.SCHEMA -> "Unsupported transition plan schema: ${plan.schema} (expected ${SegueFormat.SCHEMA})"
        SegueViolation.AUDIO_KEY_MISSING -> "Audio key fingerprint is missing on one of the transition sides"
        SegueViolation.RATE_NOT_POSITIVE -> "Playback rate must be positive (got ${plan.incoming.ratePpm} ppm)"
        SegueViolation.LENGTH_NOT_POSITIVE -> "Transition length must be strictly positive (got ${plan.span.lengthUs} us)"
        SegueViolation.ANCHOR_NEGATIVE -> "Anchor position cannot be negative (outgoing=${plan.outgoing.anchorUs} us, incoming=${plan.incoming.anchorUs} us)"
        SegueViolation.LOCKED_NEEDS_GRID_KEYS -> "Beat-locked transition requires confident beat grids on both tracks"
        SegueViolation.LOCKED_NEEDS_TEMPO -> "Beat-locked transition requires a positive tempo"
        SegueViolation.FREE_HAS_TEMPO -> "Unsynced free fade cannot specify locked tempo"
        SegueViolation.DUPLICATE_LANE -> "Duplicate automation lane target detected in plan"
        SegueViolation.UNIT_MISMATCH -> "Automation lane unit does not match target unit"
        SegueViolation.KNOTS_NOT_INCREASING -> "Automation knots must be strictly increasing in time"
        SegueViolation.KNOTS_OUT_OF_RANGE -> "Automation knot time outside normalized bounds [0, 1000000]"
        SegueViolation.KNOTS_NOT_SPANNING -> "Automation knots must span the entire transition from t=0 to t=1000000"
        SegueViolation.FORM_NEEDS_FREQUENCY -> "Octave knot curvature requires Hz frequency unit"
        SegueViolation.FREQUENCY_NOT_POSITIVE -> "Filter frequency value must be strictly positive"
        SegueViolation.EASE_NOT_POSITIVE -> "Ease parameter must be positive"
        SegueViolation.GESTURE_INVALID -> "Invalid gesture parameters in plan"
        SegueViolation.ECHO_INVALID -> "Echo send delay outside permitted bounds [1..2000000 us]"
        SegueViolation.WINDOW_PAST_OUTGOING -> {
            val endUs = SegueValidator.windowEnd(plan, plan.outgoing)
            String.format(
                java.util.Locale.ROOT,
                "Transition window extends to %.2fs in outgoing track, exceeding duration of %.2fs",
                endUs / 1e6,
                outgoingFacts.durationUs / 1e6
            )
        }
        SegueViolation.WINDOW_PAST_INCOMING -> {
            val endUs = SegueValidator.windowEnd(plan, plan.incoming)
            String.format(
                java.util.Locale.ROOT,
                "Transition window extends to %.2fs in incoming track, exceeding duration of %.2fs",
                endUs / 1e6,
                incomingFacts.durationUs / 1e6
            )
        }
        SegueViolation.STALE_OUTGOING_AUDIO -> "Outgoing track audio content changed (stale audio fingerprint)"
        SegueViolation.STALE_OUTGOING_GRID -> "Outgoing track beat grid changed (stale grid key)"
        SegueViolation.STALE_INCOMING_AUDIO -> "Incoming track audio content changed (stale audio fingerprint)"
        SegueViolation.STALE_INCOMING_GRID -> "Incoming track beat grid changed (stale grid key)"
    }
}
