# SetFlow Beta Bug Triage Register & Severity Audit

## Bug Severity Taxonomy

| Priority | Definition | Release Policy |
| :--- | :--- | :--- |
| **P0 (Critical)** | Data corruption, loss of saved projects, memory leaks causing OOM, crashes in primary paths (import, edit, playback, export), destructive audio screams/pops, or privacy leaks. | **Absolute Blocker**: Halts release immediately. Must be reproduced with synthetic test fixture, patched with minimal diff, and verified before any distribution. |
| **P1 (High)** | Silent transitions, audio stutters/dropouts during set playback, export failure, rendered WAV differing from preview beyond documented DSP tolerances, media session notifications unresponsive, or broken missing-file recovery. | **Release Blocker**: Must be resolved and verified with regression tests before advancing to Stage 16. |
| **P2 (Medium)** | Non-fatal bad tempo grid analysis requiring manual override, slight UI sluggishness on low-end hardware, or ambiguous validation warning messages. | **Targeted for V1**: Resolved where safe; otherwise documented with clear user remediation. |
| **P3 (Low)** | Minor cosmetic flaws, label alignment, or feature requests outside core scope. | **Backlog**: Deferred to post-V1 release cycle. |

---

## Beta Trial Issue Triage Log

### Incident Log

#### BUG-001 (P0): In-Memory Transition Draft Overwrites Shared State
- **Reported**: Beta testing draft undo/redo across multiple track transitions.
- **Symptom**: Opening Transition Editor for Track A->B, modifying cue points, navigating back to track list without saving, then opening Track C->D showed Track A->B's draft parameters.
- **Root Cause**: ViewModel previously held a single mutable draft reference without binding it to the active `occurrenceId` / `edgeId`.
- **Reproduction**: Synthetic unit test `testTransitionDraftIsolationBetweenNeighborPairs`.
- **Resolution**: Refactored `TransitionEditorState` to key drafts explicitly by `(fromOccurrenceId, toOccurrenceId)`. Draft is strictly scoped to the active pair; opening another pair loads its own SQLite baseline.
- **Regression Test**: `BetaTrialFreezeStage15Test.test02_transitionDraftIsolationBetweenNeighborPairs`.
- **Status**: **RESOLVED** (Commit `6be98304` / `stage-14-ci-legal-security`).

---

#### BUG-002 (P1): 32-Bar Extended Transition Window Truncation
- **Reported**: Beta user created an extended 32-bar harmonic mix transition for 160 BPM hardstyle tracks.
- **Symptom**: Audio playback switched prematurely at 12 seconds instead of rendering the full 32-bar (48.0s) span.
- **Root Cause**: Upstream `SegueCompiler` had a default legacy clamp of 12,000,000 microseconds for automatic segues, which inadvertently applied to explicit user overrides.
- **Reproduction**: Test case constructing 32-bar transition at 160 BPM (`spanUs = 48_000_000L`) and verifying compiled automation lane knot duration.
- **Resolution**: `TransitionPlanAdapter` checks if an explicit user override is present; when present, it respects the requested bar/microsecond duration up to track boundary limits without applying the 12s auto clamp.
- **Regression Test**: `BetaTrialFreezeStage15Test.test03_extended32BarTransitionExecutionWithoutClamping`.
- **Status**: **RESOLVED**.

---

#### BUG-003 (P1): Low-Storage Interruption in Master WAV Export Leaves Dangling Handles
- **Reported**: Exporting a 20-track set when available disk storage is under 100MB causes unhandled `IOException` and leaves corrupt 0-byte or truncated files labeled complete.
- **Symptom**: Partial file showed in file picker with 0 frames; user thought export succeeded.
- **Root Cause**: `OfflineExportEngine` catch block did not cleanly close output stream or trigger document deletion callback before rethrowing.
- **Reproduction**: Synthetic simulated IO failure on frame 50,000 of export.
- **Resolution**: Enclosed export loop in `try-finally` block ensuring output streams are closed and calling cleanup callback on target URI if write fails before writing the finalized WAV header. Error code `A10_EXPORT_IO_FAILURE` is recorded.
- **Regression Test**: `BetaTrialFreezeStage15Test.test05_exportStorageFailureCleansPartialFilesAndReportsError`.
- **Status**: **RESOLVED**.

---

#### BUG-004 (P2): High-Pass Filter Peak Resonance on High-Energy Kicks
- **Reported**: Audible clipping when applying aggressive High-Pass Filter sweep (`FILTER_OPEN`) over near-0dB hardstyle kicks.
- **Symptom**: Brief digital saturation during filter resonance peak at ~120Hz.
- **Root Cause**: State-variable filter Q factor was set to 1.414 without adaptive headroom reduction.
- **Reproduction**: Sine wave sweep through SVF filter with 0dB input signal.
- **Resolution**: Clamped filter resonance Q factor to 1.0 during automated transition curves and added 1.5dB soft-saturation headroom limit during active filter sweeps.
- **Regression Test**: `BetaTrialFreezeStage15Test.test04_highPassFilterSweepPreventsDigitalClipping`.
- **Status**: **RESOLVED**.

---

#### BUG-005 (P3): Track Duration Label Rounded Down by 1 Second
- **Reported**: A track of 3 minutes 45.8 seconds displayed as `3:45` instead of `3:46`.
- **Root Cause**: Integer truncation `seconds = durationUs / 1_000_000` instead of rounding `round(durationUs / 1e6)`.
- **Resolution**: Changed formatting utility to use round-half-up formatting.
- **Status**: **RESOLVED**.

---

## Status Summary

- **Total Issues Triaged**: 5
- **P0 Issues Outstanding**: 0
- **P1 Issues Outstanding**: 0
- **P2 Issues Outstanding**: 0 (all mitigated)
- **P3 Issues Outstanding**: 0 (all addressed)
- **Feature & Dependency Freeze**: **STRICTLY ENFORCED**.
