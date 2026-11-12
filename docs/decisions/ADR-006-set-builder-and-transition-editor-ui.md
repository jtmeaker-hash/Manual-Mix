# ADR-006: Spotify-Simple Set List and Focused Transition Editor UI Architecture

## Context
In Stage 08, SetFlow implements the user-facing Jetpack Compose UI for the core DJ workflow:
1. **Set Detail Screen**: A single, clean vertical playlist showing track occurrences and inline transition chips between neighboring tracks.
2. **Transition Editor Screen**: A focused, zoomable dual-waveform transition editor allowing precise adjustment of cue points, overlap duration, and transition styles.

Key design & engineering challenges:
1. **LazyColumn Keying and List Integrity (Bug Trap 38)**:
   In Compose, using positional indices as keys in `LazyColumn` causes severe state recycling bugs, stale animations, and wrong edge targets when tracks are reordered or removed. Keys must be immutable domain identifiers (`occurrenceId` for track cards, `edgeId` for transition chips).
2. **Truth in Waveform Visualization (Task 4 & Bug Trap 39)**:
   Waveforms must never be generated from random numbers or decorative bars pretending to be real audio. They must represent real analyzed or decoded energy data (RMS / peak envelopes). Large audio files cannot be decoded on the main UI thread or held as unbounded uncompressed arrays in memory without causing ANRs and OutOfMemoryErrors on small phones.
3. **Draft Separation & Dirty Confirmation (Task 5)**:
   Editing a transition must operate on an isolated in-memory draft (`EdgeDraftState`). Committed database state and resolved plan hashes must remain completely unchanged until the user explicitly taps [Save Transition]. Navigating away or pressing back when modifications exist must prompt for confirmation to prevent accidental loss or premature persistence.
4. **Adjacency-Scoped Edge Rebuild on Reorder (Task 7)**:
   Reordering tracks (e.g., A-B-C to A-C-B) changes only the adjacent boundaries (A-C and C-B). The system must rebuild only the affected edges within an atomic database transaction, leaving all other custom transitions intact, with Undo support.
5. **Strict Feature Truth in Presets (Task 8)**:
   Buttons or presets must never merely change a UI label without corresponding audio engine support. Presets supported by the DSP engine (`BASS_SWAP`, `THREE_BAND`, `CUT_ON_BEAT`, `FILTER_OPEN`, `RISE_OUT`, `EVEN_FADE`, `SWEEP_OUT`) are enabled; unsupported experimental options are clearly marked disabled with explanatory reasons.
6. **Accessibility & Touch Targets (Task 6)**:
   All touch targets must satisfy the $\ge 48\,\text{dp}$ guideline. Waveforms and cue handles must be accessible to TalkBack screen readers with meaningful semantic descriptions. UI must support dynamic font scaling up to 1.5x and landscape/portrait orientations without clipping.

## Decision

1. **Compose Architecture & Stable Keying**:
   - `SetDetailScreen` renders a `LazyColumn` where each track item is keyed by `item.occurrenceId` and each inline transition chip is keyed by `item.edgeId`.
   - Track cards display title, artist, duration, BPM/key badges, availability status, reorder controls, and remove actions.
   - Inline `TransitionChipItem` displays boundary origin (`AUTO`, `CUSTOM`, `NEEDS_REVIEW`), length in bars and seconds, style name, and an edit affordance.

2. **Waveform Decimation and Tile Cache (`WaveformTileCache`)**:
   - Decoupled waveform data model (`WaveformPeaksData`) providing multi-resolution decimated peak/RMS points (e.g. 100 points/sec for high zoom, 10 points/sec for overview).
   - Audio decoding or analysis cache extraction runs strictly off the main thread (`Dispatchers.Default` / `Dispatchers.IO`).
   - Waveform cache is bounded by an in-memory LRU cache keyed by `trackId:contentFingerprint:resolution`.
   - Stable fixed-height placeholder renders while waveform data loads asynchronously, avoiding visual layout shifts.

3. **Focused Transition Editor (`TransitionEditorScreen`)**:
   - Overlapping dual waveform canvas with high-contrast distinct colors: Track A (outgoing/exit) in Cyan/Teal (`#26C6DA`) and Track B (incoming/entry) in Amber/Orange (`#FFA726`).
   - Draggable handles for Track A exit anchor (`exitAnchorUs`) and Track B entry anchor (`entryAnchorUs`) with real-time numeric/bar position feedback and boundary clamping within track duration.
   - Transition length buttons: 4, 8, 16, 32 bars (when beat grid is valid) and a microsecond/second slider fallback for ungridded tracks.
   - Preset selector with honest status: active presets are bound to `SegueShape`, while unsupported presets are explicitly disabled.
   - Action footer: [Preview] (marked "PREVIEW UNSAVED"), [Cancel] / [Back] (with dirty confirmation dialog), [Reset to Auto], and [Save Transition].

4. **Draft State & Atomic Persistence**:
   - Entering the editor initializes an immutable `EdgeDraftState` derived via `TransitionOverrideManager.getDraft(edgeId)`.
   - Modifications update the draft in `SetFlowShellController`. The committed database is never touched during editing.
   - [Save Transition] validates the draft via `TransitionOverrideManager.saveOverride`, commits atomically, updates the project revision, and navigates back.
   - [Reset to Auto] invokes `TransitionOverrideManager.resetToAuto` and updates the active draft.
   - Dirty check compares the active draft against the initial snapshot. If dirty, Back or Cancel shows an `AlertDialog` offering "Discard Changes", "Keep Editing", or "Save Transition".

5. **Accessibility & Ergonomics**:
   - Touch targets for chips, cue handles, and buttons have minimum bounds of $48 \times 48\,\text{dp}$.
   - Draggable cue handles include TalkBack semantic actions (`setPoint`, custom actions, and value descriptions).
   - High contrast colors on dark background surfaces (`#0B0E14`, `#151A24`, `#1F2735`).

## Consequences
- Guaranteed stability during list reordering with zero wrong-edge associations.
- Fast, non-blocking UI navigation with zero audio decoding on the main thread.
- Full protection against accidental loss of user edits through robust dirty draft management.
- Complete adherence to open-source attribution, accessibility standards, and product specifications.
