# ADR-011: Accessibility, Usability Polish, Fine-Grained Cue Controls, and UI Ergonomics

## Context
In Stage 13 (`STAGES/13_ACCESSIBILITY_POLISH.txt`), SetFlow focuses on user experience, visual polish, accessible editing, and ergonomics.
The application combines the ease of a simple playlist (Spotify Mix interaction model) with the professional editing precision of a digital audio workstation (DJ.Studio workflow).

Prior stages established:
- Crash-safe SQLite persistence and revision-locked transition overrides (Stages 04 & 07).
- Two-deck playback and global timeline synchronization (Stages 05 & 10).
- Offline full-set 16-bit 44.1kHz WAV export engine (Stage 11).
- Reliability stress and fault-injection hardening (Stage 12).

However, real-world usability review identified the following polish requirements:
1. **Fine-grained Cue Stepping**: Adjusting transition entry/exit points previously offered only 1-bar or 1-second steppers. DJs need +/-1 beat precision on gridded tracks and +/-100ms precision when beat grids are ungridded or drifting.
2. **Draft Undo / Redo**: When fine-tuning transition boundaries, users need an in-memory undo/redo stack before committing to SQLite.
3. **Reset to Auto Confirmation**: Tapping "Reset Auto" previously discarded custom work immediately without confirmation. An explicit confirmation dialog protects against accidental data loss.
4. **Waveform Handle Geometry**: Draggable cue handles previously used a hardcoded 340dp width approximation, which degraded on tablets, large foldables, and landscape orientations. A responsive `BoxWithConstraints` layout is required.
5. **Accessibility & TalkBack Compliance**: All interactive buttons, chips, sliders, and draggable handles must meet Android's 48dp minimum touch target guideline, support dynamic font scaling up to 2.0x without clipping, and provide clear semantic content descriptions for screen readers (TalkBack).
6. **Understandable Integrity Warnings**: Error codes (`A01`..`A12`) must be presented alongside plain-language diagnostics and clear actionable remediation (e.g. Relink File, Re-analyze, Storage Full).

## Decisions

### 1. In-Memory Draft Undo/Redo Architecture
- `TransitionEditorState` holds immutable `undoStack: List<TransitionOverrideParams>` and `redoStack: List<TransitionOverrideParams>`, bounded to 50 operations.
- Edits via `updateTransitionDraft` push previous parameters onto `undoStack` and clear `redoStack`.
- `undoTransitionDraft()` and `redoTransitionDraft()` navigate history without touching SQLite.
- Committing via `saveTransitionOverride()` clears undo/redo history and updates the baseline `initialParams`.

### 2. Fine-Grained Stepper Granularity
- **Gridded Tracks**:
  * +/-1 Bar: `240.0 / bpm * 1_000_000` microseconds.
  * +/-1 Beat: `60.0 / bpm * 1_000_000` microseconds.
- **Ungridded / Fine Adjustment**:
  * +/-100ms: `100_000` microseconds.
  * +/-1.0s: `1_000_000` microseconds.
- Clamped strictly between `0L` and track duration.

### 3. Reset to Auto with Confirmation Dialog
- Tapping "Reset Auto" prompts: "Reset Transition to Auto? This will discard your custom cue points and style overrides, restoring the beat-matched automated transition recommendation."
- Prevents accidental loss of complex 16-bar EQ/bass-swap custom arrangements.

### 4. Responsive Waveform Gesture Geometry
- `DualWaveformCanvas` uses `BoxWithConstraints` to derive exact layout pixel width `constraints.maxWidth`.
- Draggable handle horizontal offsets are calculated as `fraction * maxWidth`, guaranteeing pixel-accurate gesture alignment across phones, tablets, and landscape viewports.

### 5. Accessibility & Typography Ergonomics
- Minimum 48dp touch targets on all interactive components (`IconButton`, `Button`, `OutlinedButton`, `FilterChip`, `CueAnchorDraggableHandle`).
- Dynamic font scaling support (1.5x - 2.0x) with flexible wrapping and `maxLines` with `TextOverflow.Ellipsis`.
- High-contrast visual palette:
  * Outgoing Deck A: High-contrast Cyan (`#26C6DA`).
  * Incoming Deck B: High-contrast Amber (`#FFA726`).
  * Overlap Window: Translucent scrim (`#22FFFFFF`).
  * Warning/Stale badges: Error container (`#B00020` on dark surface).

## Consequences
- Clean separation between draft editing and SQLite persistence is preserved.
- Users can confidently edit cue points with immediate visual, tactile, and screen-reader feedback.
- No changes to underlying DSP algorithms or SQLite schema versions; zero risk of playback regressions.
