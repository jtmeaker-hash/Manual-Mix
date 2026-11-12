/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.domain

/**
 * Canonical audio and storage error taxonomy (A01..A12) from REFERENCE/05_AUDIO_ENGINE_CONTRACT.txt.
 */
enum class AudioErrorCode(
    val code: String,
    val summary: String,
    val recoveryAction: String
) {
    A01_SOURCE_MISSING(
        code = "A01",
        summary = "Source audio file is missing or moved",
        recoveryAction = "Relink file via SAF picker while preserving saved transition plan"
    ),
    A02_UNREADABLE_PERMISSION(
        code = "A02",
        summary = "Read permission revoked or unavailable for source URI",
        recoveryAction = "Reauthorize URI read grant while preserving saved transition plan"
    ),
    A03_UNSUPPORTED_CODEC(
        code = "A03",
        summary = "Audio container or codec is unsupported by device decoder",
        recoveryAction = "Display codec details and allow skipping or external conversion"
    ),
    A04_BAD_ANALYSIS(
        code = "A04",
        summary = "Beat grid confidence insufficient for beat-locked transition",
        recoveryAction = "Use time-based equal-power fade or manual beat grid correction"
    ),
    A05_SOURCE_CHANGED(
        code = "A05",
        summary = "Source file size, duration, or content fingerprint changed",
        recoveryAction = "Mark transition plan stale and prompt explicit review"
    ),
    A06_BUFFER_UNDERRUN(
        code = "A06",
        summary = "Incoming deck buffer underrun before transition window",
        recoveryAction = "Clean retry/prebuffer or preserve outgoing deck with safe fallback"
    ),
    A07_DSP_NONFINITE(
        code = "A07",
        summary = "Non-finite sample (NaN/Inf) detected in DSP chain",
        recoveryAction = "Disarm effect immediately and bypass to prevent output noise"
    ),
    A08_AUDIO_FOCUS_LOST(
        code = "A08",
        summary = "System audio focus lost or interrupted",
        recoveryAction = "Pause or duck per focus policy and stop secondary deck safely"
    ),
    A09_OUTPUT_ROUTE_CHANGED(
        code = "A09",
        summary = "Audio output route changed (e.g., Bluetooth disconnect)",
        recoveryAction = "Pause/flush output sink and safely re-arm current revision"
    ),
    A10_EXPORT_IO_FAILURE(
        code = "A10",
        summary = "Offline export write failed",
        recoveryAction = "Close/clean partial output target and report actionable error"
    ),
    A11_CANCELLED(
        code = "A11",
        summary = "Operation cancelled by user",
        recoveryAction = "Clean up open handles without reporting completion"
    ),
    A12_TIMELINE_OVERFLOW(
        code = "A12",
        summary = "Timeline frame or microsecond arithmetic out of bounds",
        recoveryAction = "Reject invalid plan and preserve prior committed revision"
    )
}

/**
 * Scheduler state machine phases from REFERENCE/03_TARGET_ARCHITECTURE.txt.
 */
enum class SchedulerPhase {
    IDLE,
    PREPARING,
    ARMED,
    RUNNING,
    HANDOFF,
    COMPLETE,
    ERROR,
    SAFE_FALLBACK,
    STOPPED
}

/**
 * Ownership of the single active audio output graph (REFERENCE/03_TARGET_ARCHITECTURE.txt).
 */
enum class AudioGraphOwner {
    NONE,
    TRANSITION_PREVIEW,
    SET_PLAYBACK
}

/**
 * Immutable summary of a local DJ set project for the Sets list shell.
 */
data class SetSummary(
    val setId: String,
    val title: String,
    val trackCount: Int,
    val revision: Long,
    val statusNote: String
)

/**
 * Honest Stage 02 placeholder state for the Set Detail screen.
 */
data class SetDetailPlaceholderState(
    val setId: String,
    val title: String,
    val revision: Long,
    val notImplementedBanner: String,
    val isAudioPlaying: Boolean = false
)

/**
 * Structured diagnostic event (never contains raw audio, file paths, URIs, or tokens).
 */
data class DiagnosticEvent(
    val timestampMs: Long,
    val category: String,
    val phase: SchedulerPhase,
    val errorCode: AudioErrorCode? = null,
    val message: String
)

/**
 * Bounded in-memory diagnostic ring buffer with path/URI/token scrubbing.
 */
class DiagnosticsRingBuffer(val capacity: Int = 128) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val events = ArrayDeque<DiagnosticEvent>(capacity)

    @Synchronized
    fun record(
        category: String,
        phase: SchedulerPhase = SchedulerPhase.IDLE,
        errorCode: AudioErrorCode? = null,
        message: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (events.size >= capacity) {
            events.removeFirst()
        }
        events.addLast(
            DiagnosticEvent(
                timestampMs = timestampMs,
                category = category,
                phase = phase,
                errorCode = errorCode,
                message = redact(message)
            )
        )
    }

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
    }

    companion object {
        private val uriOrPathRegex = Regex("""(?:content|file|https?)://[^\s"']+|/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)+""")
        private val tokenRegex = Regex("""(?i)(token|sig|signature|key|password|bearer)=[^\s&"']+""")

        fun redact(raw: String): String =
            tokenRegex.replace(uriOrPathRegex.replace(raw, "<redacted-path>"), "$1=<redacted>")
    }
}
