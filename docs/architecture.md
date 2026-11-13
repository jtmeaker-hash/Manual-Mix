# SetFlow Architecture

## System Overview

SetFlow is a 100% offline, local-only Android application designed for DJ set creation, transition editing, beat grid alignment, continuous whole-set playback, and lossless master WAV export.

```
+-------------------------------------------------------------------------------+
|                               SetFlow UI                                      |
|  - SetFlowAppShell (Hero Studio / Status / Mode Tabs)                         |
|  - SetDetailScreen (Track List / Reorder / Transition Overviews)             |
|  - TransitionEditorScreen (Dual-Waveform Canvas / Fine Steppers / Undo Draft) |
+---------------------------------------+---------------------------------------+
                                        | ViewModel & State Flow
+---------------------------------------v---------------------------------------+
|                       Domain & Orchestration Services                         |
|  - SetFlowShellViewModel / SetFlowOrchestrator                                |
|  - AudioContracts & DiagnosticsRingBuffer (Path / Token Scrubbing)            |
+-------------------+-------------------+-------------------+-------------------+
                    |                   |                   |
+-------------------v---+   +-----------v-------+   +-------v-------------------+
|   SQLite Persistence  |   | Beat Grid Engine  |   | Dual-Deck Audio Engine    |
| - SetFlowDatabase     |   | - ML Beat This!   |   | - TwoDeckPlayerController |
| - TrackEntity / DAO   |   |   (int8 ONNX)     |   | - SegueProcessor (Media3) |
| - EdgeEntity / DAO    |   | - Adaptive Grid   |   | - DeckEq / Filter Sweeps  |
| - Room Migrations     |   | - Analysis Cache  |   | - SampleClock Alignment   |
+-----------------------+   +-------------------+   +---------------------------+
                                                            |
                            +-------------------------------+-------------------+
                            |                                                   |
            +---------------v---------------+                   +---------------v---------------+
            |  SetPlaybackService (Stage 10)|                   |  SetExportEngine (Stage 11)   |
            | - MediaSessionService         |                   | - Offline Lossless Rendering  |
            | - Lockscreen / Notification   |                   | - 16-bit / 24-bit Stereo PCM  |
            | - Background Playback         |                   | - DocumentProvider Export     |
            +-------------------------------+                   +-------------------------------+
```

## Module Boundaries

### `:automix` (Pure Kotlin JVM)
- **Namespace**: `app.myspoti.automix`
- **Dependencies**: Microsoft ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime:1.23.2`), JUnit 4
- **Responsibilities**:
  - `BeatThis.kt`: Mel-spectrogram computation and input preparation for the Beat This! ONNX model.
  - `MixPlanner.kt`: Automated tempo matching, phrase alignment, and transition candidate scoring.
  - `segue/SeguePlan.kt`: Transition plan data schema (`Side`, `Span`, `Pulse`, `Lane`, `Gesture`, `Knots`).
  - `segue/SegueCompiler.kt`: Automation lane generation for 12 transition styles (`BASS_SWAP`, `THREE_BAND`, `FILTER_OPEN`, etc.).
  - `segue/SegueValidator.kt`: Strict structural and timeline validation preventing invalid cue anchors or sample rate mismatches.
  - `segue/SegueDeck.kt`: Pure DSP automation computation (EQ filters, gain ramps, resonant filters).

### `:app` (Android Application)
- **Namespace**: `app.setflow`
- **Dependencies**: Jetpack Compose, AndroidX Media3 (ExoPlayer), Room SQLite, AndroidX Lifecycle, ONNX Runtime Android
- **Responsibilities**:
  - `ui/`: Compose UI components including `DualWaveformCanvas`, `TransitionEditorScreen`, `SetDetailScreen`, and `SetFlowAppShell`.
  - `db/`: SQLite database entities, DAOs, and schema migrations (`SetFlowDatabase`, `TrackEntity`, `EdgeEntity`).
  - `playback/`: `TwoDeckPlayerController` and `SetPlaybackService` (MediaSessionService) for live continuous playback.
  - `export/`: `OfflineExportEngine` for multi-track transition rendering to standard stereo WAV format.
  - `domain/`: `DiagnosticsRingBuffer` for zero-leakage in-memory telemetry, error models, and audio contracts.
  - `com.music.spotui.*`: Adapted Media3 audio processors (`SegueProcessor`, `DeckEq`, `MixPreparation`).

## Zero-Network Invariant
The application compiles and executes entirely without network communication. All transitive network permissions are explicitly removed from the release build (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `REQUEST_INSTALL_PACKAGES`).
