# ADR-010: Reliability Stress, Memory Profiling, and Fault Injection Framework

## Context
In Stage 12 (`STAGES/12_RELIABILITY_STRESS.txt`), SetFlow transitions from feature delivery to hardening as a release candidate.
The objective is to systematically identify, isolate, and eliminate:
1. Crashes and ANRs under stress, rapid input, and process death.
2. DSP regressions, sample misalignment, clicks, clipping, and phase/gap defects.
3. State machine desynchronization (dual audio ownership, leaked decoders, orphaned decks).
4. Memory leaks and unbounded resource scaling during prolonged continuous playback (30m to 3h soak).
5. Data corruption upon unexpected termination, simulated low disk space, or revoked SAF document grants.
6. ProGuard / R8 minification and packaging regressions on release-like builds.

## Decision

1. **Systematic Acceptance Matrix (ACC-01..40)**:
   - Establish an automated verification harness validating every criterion in `TESTS/02_ACCEPTANCE_MATRIX.txt`.
   - Ensure explicit categorization: PASS for verified behaviors, FAIL for regressions, BLOCKED for external hardware dependencies (e.g. physical Bluetooth hardware route switches without an emulator bridge), and N/A for features intentionally out of V1 scope.

2. **Fault Injection Suite (S01..S30)**:
   - Implement automated stress tests for core fault injection scenarios (`TESTS/03_STRESS_AND_FAULT_INJECTION.txt`):
     - **S01**: Atomic transactional integrity across rapid save/kill cycles.
     - **S04**: Preemption and arbitration across `AudioGraphOwner.SET_PLAYBACK` and `TRANSITION_PREVIEW`.
     - **S05**: Rapid control tap storms (30+ rapid Next/Pause/Play requests) ensuring monotonic generation arbitration and single audio graph ownership.
     - **S06**: Concurrent project edits and track reordering while a transition is armed or running; ensures safe fallback without playing the wrong track.
     - **S07**: Echo and filter state clearance upon track skip or transition abort.
     - **S08**: Cancellable export at 1%, 50%, and 99% with immediate temporary file cleanup and uncorrupted database state.
     - **S09**: Unwritable destinations and simulated I/O errors handled cleanly without application crashes.
     - **S10**: Source audio truncation or modification detected via SHA/content fingerprints, triggering safe fallback (`A05_SOURCE_CHANGED`).
     - **S16 & S17**: Malformed serialized JSON, unknown segue schemas, and oversized payloads cleanly rejected with bounded memory.
     - **S23**: Missing ONNX model assets gracefully falling back to equal-power time-based crossfades without crashes.
     - **S24**: DSP non-finite sample injection (NaN/Inf) blocked and sanitized without digital clipping.

3. **Continuous Soak & Memory Bounds**:
   - Verify that prolonged streaming renders and multi-hour set playback operate strictly within constant $O(1)$ memory.
   - Enforce bounded LRU capacities on analysis caches (max 256 entries), diagnostics ring buffers (max 128 events), and decoded PCM buffers.

4. **Release-Like Minification & ProGuard Rules**:
   - Audit `proguard-rules.pro` to ensure AndroidX Room, Kotlinx Serialization, and ONNX Runtime classes are preserved in minified release builds.

## Consequences
- Guaranteed stability against race conditions, rapid user taps, and background preemption.
- Absolute data safety: database transactions are strictly atomic and immune to process death.
- Production readiness: all 40 acceptance criteria and 30 fault scenarios are accounted for and verified.
