# ADR-004: Beat-Grid Analysis Cache, ONNX Fallback, and Manual Corrections

## Context
Stage 06 requires deriving confident BPM, beat grids, downbeats, and musical phrase boundaries for imported tracks while preventing analysis from thrashing the CPU, leaking memory, or blocking the Compose main thread.
Automated beat tracking via the bundled Beat This! `small0` int8 ONNX model or the pure-Kotlin `BeatTracker` onset flux can produce inaccurate grids in edge cases (e.g., half-time/double-time ambiguity such as 80 BPM vs 160 BPM, non-constant tempo intros/outros, kickless builds, or drifting acoustics).

## Decision

1. **Analysis Keying & Cache Hierarchy**:
   - Cache keys are strictly composed of:
     `recordingIdentity` (source contentFingerprint + audioKey SHA-256) + `analyzerVersion` (`TrackAnalysis.VERSION` = 410) + `modelFingerprint` (SHA-256 of ONNX weights or "kotlin-dsp") + `correctionRevision` (`BeatGridCorrectionEntity.revision`).
   - Caches are tiered:
     - Level 1 (In-Memory LruCache): Bounded LRU holding decoded `TrackAnalysis` instances (default max 64 entries) with weak memory pressure eviction.
     - Level 2 (Persistent SQLite/File Cache): Serialized binary `TrackAnalysis.write` payload in the app's cache directory keyed by the deterministic composite cache key.
   - Cache is keyed to the *unique track recording*, never duplicated per occurrence in playlists or sets.

2. **Deduplication & Concurrency Bounding**:
   - Background analysis is bounded to a maximum of 1-2 worker threads to preserve audio playback headroom and battery life.
   - In-flight requests for the same track share a single `Deferred<TrackAnalysis>` mutex: no simultaneous duplicate analysis of the same recording.
   - Analysis runs on `Dispatchers.Default` (off the Android main/Compose thread).

3. **Segmentation (Head & Tail Grids)**:
   - Uses `Analyzer.HEAD_SEC` (75s) and `Analyzer.TAIL_SEC` (90s) to fit independent head and tail beat grids when a single global grid does not hold steadily across the whole track.
   - Intro edits and outro tempo changes maintain accurate entry and exit grids without assuming global constant BPM.

4. **Beat This! ONNX Model & Pure-Kotlin Fallback**:
   - Attempts to load and execute Beat This! int8 ONNX model via ONNX Runtime (`ai.onnxruntime.OrtEnvironment`).
   - If ONNX Runtime is missing, unsupported on ABI, or the model asset is corrupted/unreadable:
     - Gracefully falls back to pure-Kotlin onset-envelope `BeatTracker` without crashing.
     - If both onsets and model fail to establish steady grids (`!reliable`), the analysis marks `head = null`, `tail = null`, and `MixPlanner` produces safe time-based equal-power crossfades rather than forcing a misaligned bass swap.

5. **Manual Corrections & Confidence States**:
   - Exposes explicit confidence states: `IN_PROGRESS`, `CONFIDENT`, `UNRELIABLE`, `FAILED`.
   - Manual corrections persist in `beat_grid_corrections` table: `bpmOverride`, `phaseAnchorUs`, `beatsPerBar`, and half/double toggle.
   - Manual corrections adjust the effective grid (recomputing beat period and downbeat anchors) but **never fake neural model confidence** (`confidenceOverride` is left honest).
   - Saving a correction increments `revision`, invalidates Level 1/Level 2 caches for that track, and triggers `SetFlowProjectDatabase.recordBackgroundAutoPlanReanalysis` to flag affected transitions as stale without overwriting user overrides.

6. **Prioritized Queueing**:
   - Tracks are queued by priority: `PLAYBACK_ARMED` > `TRANSITION_PREVIEW` > `QUEUE_NEXT` > `IDLE_LIBRARY`.
   - Cancellation is immediate when navigating away or changing tracks.

## Consequences
- Guaranteed zero CPU thrashing on duplicate analysis requests.
- Full offline resilience without requiring external network services or proprietary cloud APIs.
- Predictable transition fallback when grids cannot be established with confidence.
