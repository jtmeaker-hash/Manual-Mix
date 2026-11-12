# ADR-005: Editable Serializable Transition Override, Resolution Adapter, and Validation

## Context
In Stage 07, SetFlow implements the core user-facing capability of an Android DJ Set Builder:
Every adjacent track pair (Edge) in a project sequence must possess an independent, versioned, editable, saved transition plan. A DJ must be able to draft changes to cue anchors, transition length (in bars or seconds), style shapes, and playback rates, preview them with exact DSP parity, and commit or discard them.

Key engineering challenges:
1. **Schema & Scale Integrity**: Upstream audio engine expects canonical `segue/1` plans using integer microseconds (`us`), normalized knot timestamps (`0..1_000_000`), and scaled millivolts/millibels/milliHz (`VALUE_SCALE = 1000`). Storing floating-point string seconds or lossy conversions in SQLite violates audio precision and causes phase drift.
2. **AutoPlan + Override Adaptation**: Automated analysis proposes default transition plans (`AutoPlanSnapshotEntity`), but user overrides (`OverrideSnapshotEntity`) must be able to partially or fully customize cue anchors, spans, or shapes without losing unedited auto fields.
3. **Bar Length Support (No 12s Auto Clamp)**: While upstream auto-planning heuristic caps transitions at 12–16 seconds to avoid vocals, a DJ explicitly choosing a 4, 8, 16, or 32-bar transition (e.g., ~49.5s at 155 BPM) must have their exact bar length respected without silent truncation or clamping.
4. **Engine Style Support & Disabling Unsupported Features**: The playback engine (`SegueDeck` and `SegueProcessor`) natively supports gain, 3-band EQ, filter sweep, filter rise, room reverb, and echo sends under `Clock.ELAPSED`. Unsupported features (such as `Clock.SOURCE` and `gestures`) must be explicitly disabled and validated.
5. **Non-Destructive Reanalysis (Contract D10)**: Background audio re-analysis or beat-grid adjustments must never delete or overwrite user overrides. Stale analysis simply marks the edge as `STALE_ANALYSIS` ("Needs review"), preserving user intent.
6. **Optimistic Concurrency & Deduplicated Pair Independence**: Multi-row saves verify both edge plan revision and project edit revision. Duplicate occurrences of the same track in a set (e.g. A -> B -> A) maintain separate edges and independent overrides.

## Decision

1. **Resolution Adapter (`TransitionPlanAdapter`)**:
   - Resides in `:automix` (`app.myspoti.automix.segue.TransitionPlanAdapter`) to remain pure Kotlin/JVM testable.
   - Merges `(autoPlan: SeguePlan?, override: TransitionOverrideParams, outgoingFacts: SegueSideFacts, incomingFacts: SegueSideFacts)` into an executable `SeguePlan`.
   - Computes musical bar spans with exact microsecond precision:
     $$\text{lengthUs} = \text{barCount} \times \text{round}\left(\frac{240.0}{\text{BPM}} \times 10^6\right)$$
     Supporting 2, 4, 8, 16, and 32 bars without auto-clamping.
   - Uses `SegueCompiler` to synthesize automation curves for all supported shapes (`BASS_SWAP`, `THREE_BAND`, `CUT_ON_BEAT`, `FILTER_OPEN`, `RISE_OUT`, `ECHO_TAIL`, `EVEN_FADE`, `SWEEP_OUT`, `QUICK_OUT`, `LATE_OUT`, `ECHO_OUT`, `WASH_OUT`).
   - Parses custom user-provided lane knots from JSON when custom automation curves are specified.

2. **Validation and Safe Diagnostics**:
   - Every plan is subjected to `SegueValidator.structural(plan)` and `SegueValidator.against(plan, outgoingFacts, incomingFacts)`.
   - Human-readable error messages are mapped to `AudioErrorCode.A12_TIMELINE_OVERFLOW` or `AudioErrorCode.A04_BAD_ANALYSIS` if invalid.
   - Malformed, non-finite, negative, or out-of-bounds parameters (e.g. anchors exceeding track duration, negative length, NaN knots) are strictly rejected before writing to database or audio sink.

3. **Database Persistence & Transactional Guarantees**:
   - `saveEdgeOverrideAndResolvedPlan` commits both `override_snapshots` and `resolved_plan_snapshots` atomically within a SQLite transaction.
   - Enforces optimistic concurrency on `edge.planRevision` and `project.editRevision`.
   - Fingerprints resolved plans deterministically using SHA-256 (`SegueCodec.fingerprint`). Previews and saves reference the exact same plan hash at a given revision.
   - Resetting an edge to Auto clears the override snapshot and restores the auto plan revision explicitly.

## Consequences
- Guaranteed zero truncation for extended 32-bar transitions.
- Complete offline portability between desktop JVM unit tests and Android playback sinks.
- Full protection of user curation work against automatic reanalysis overwrite.
