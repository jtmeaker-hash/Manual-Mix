# ADR-013: Beta Trial, Bug Triage Framework, Regression Freeze, and Real-World Validation

## Context
In Stage 15 (`STAGES/15_EXTERNAL_BETA.txt`), SetFlow transitions from isolated developer testing to external beta trial validation, bug triage, and feature freeze.
The full V1 feature set was implemented and refined across Stages 01 through 13:
- Local audio import, on-device ML beat tracking, and SQLite Room persistence.
- Dual-deck sample-accurate playback, transition preview DSP, whole-set background playback service, and offline master WAV export.
- Automated CI quality gates, license attribution, merged manifest zero-network security, and reproducible release builds (Stage 14).

Prior to general stable release (Stage 16), the application must be validated under realistic user conditions, diverse hardware environments, complex multi-track DJ set arrangements, and fault conditions.

## Decisions

### 1. Feature and Dependency Freeze
- Strict feature freeze enforced: No new functional features or architecture changes are accepted during Stage 15.
- Dependency freeze: All Gradle dependencies, plugins, and libraries remain strictly pinned at their validated versions (Media3 1.8.0, ONNX Runtime 1.23.2, AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.06.01).
- Any bug fix must be preceded by a failing regression test and implemented with minimal diff footprint.

### 2. Bug Triage & Severity Taxonomy
All reported issues are classified into a 4-tier priority framework:
- **P0 (Critical Blocker)**: Data loss/corruption, source file modification/deletion, privacy leaks, crashes during primary user workflows (import, edit, playback, export), or destructive audio output (loud pops/screeches). *Action: Immediate release stop; fix before any beta distribution.*
- **P1 (High)**: Audio dropout/silence during transitions, failure to export, export differing audibly from preview beyond documented DSP tolerances, broken MediaSession background controls, or failure to relink missing tracks. *Action: Release blocker; must be resolved before Stage 16.*
- **P2 (Medium)**: Non-fatal bad tempo grid analysis requiring manual correction, minor waveform rendering sluggishness on low-end hardware, or non-blocking validation warning ambiguity. *Action: Address where feasible without architectural disruption; otherwise document in Known Limitations.*
- **P3 (Low)**: Cosmetic alignment issues or minor copy inconsistencies not impacting accessibility or audio integrity. *Action: Deferred to post-V1 backlog.*

### 3. Standardized Beta Tester Script
To guarantee repeatable user feedback without collecting private media, testers execute an 8-step script:
1. **Import Local Audio**: Import 5+ local DRM-free audio files (WAV, FLAC, MP3, AAC) via Storage Access Framework.
2. **Set Construction**: Create a new set, add tracks, and verify automated beat grid alignment and transition plan recommendations.
3. **Transition Editing**: Open the Transition Editor for two independent track pairs; customize cue entry/exit points, audition fine steppers (+/-1 beat, +/-100ms), apply distinct styles (e.g., `BASS_SWAP` and `FILTER_OPEN`), and test draft undo/redo before saving.
4. **Process Death / Relaunch**: Force-stop the app process (`am force-stop app.setflow.dev`) and reopen; verify all custom cue points and transition overrides remain intact.
5. **Continuous Playback**: Initiate whole-set playback; verify seamless background playback with screen locked, notification controls, and audio focus ducking/pausing during interruptions.
6. **Master WAV Export**: Render the 5-track set to a 16-bit 44.1kHz stereo WAV file; verify file size, frame count, and header finalization.
7. **Backup Roundtrip**: Export a JSON project backup; delete the test set; re-import the backup and confirm complete reconstruction.
8. **Missing File Recovery**: Move or rename a source audio track; relaunch app; verify error code `A01_SOURCE_MISSING` and successfully relink via the SAF document picker.

### 4. Privacy-Preserving Diagnostic Reporting
- No automatic crash report or telemetry upload endpoints exist in the codebase.
- Testers report issues manually using a structured template: Device model, Android OS version, track codec/sample rate, exact reproduction steps, expected vs. actual behavior, and optional manual export of the in-memory `DiagnosticsRingBuffer` snapshot.
- The ring buffer automatically redacts all file URIs (`content://`, `file://`), absolute filesystem paths, and query secrets.

### 5. Export Integrity & Storage Interruption Handling
- Direct-to-storage rendering streams samples in 4096-frame buffers directly to the target `OutputStream`.
- On low storage, IO error, or cancellation, the partial output file is immediately closed and deleted where supported by the `DocumentProvider`.
- SetFlow never reports success unless the WAV header frame count has been written and the file handle closed cleanly.

## Consequences
- The release candidate is hardened against unexpected hardware quirks and real-world DJ workflows.
- Zero risk of regression in core DSP, persistence, or timeline scheduling algorithms.
- Full compliance with GPL-3.0, privacy invariants, and the 40-item acceptance matrix (`ACC-01` through `ACC-40`).
