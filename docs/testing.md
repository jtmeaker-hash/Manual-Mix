# SetFlow Testing & Quality Gates

## Test Philosophy

SetFlow enforces strict verification gates across JVM unit tests, Android unit tests, audio golden DSP metrics, fault-injection tests, and accessibility benchmarks. No code is merged or released without reproducible local and CI test execution.

## Test Suites

### 1. Pure JVM Suite (`:automix:test`)
- **Location**: `base/automix/src/test/kotlin/`
- **Coverage**:
  - `BeatThisTest`: Validates mel-spectrogram transformation and ONNX model inference contracts.
  - `MixPlannerTest`: Verifies automated track planning, BPM compatibility scoring, and phrase alignment.
  - `SegueCompilerTest`: Validates automation curve generation across all 12 segue styles.
  - `SegueValidatorTest`: Enforces strict schema bounds, knot timestamps, and transition duration limits.
  - `SegueJsonTest`: Tests deterministic serialization/deserialization of segue plans.
  - `SegueDeckTest`: DSP filter stability, gain smoothing, and knot interpolation precision.

### 2. Android Unit Test Suite (`:app:testDebugUnitTest`)
- **Location**: `base/app/src/test/java/app/setflow/`
- **Coverage**:
  - `LocalAudioImportStage03Test`: Audio decoding, metadata extraction, and URI handling.
  - `DatabaseStage04Test`: SQLite schema migrations (v1 -> v2), crash-safe transactions, and DAO invariants.
  - `TwoDeckPlaybackStage05Test`: Deck swapping, state machines, and sample clock time synchronization.
  - `AnalysisGridCacheStage06Test`: Fast cache lookups, cache eviction, and fallback estimation.
  - `SavedTransitionsStage07Test`: Revision-locked transition overrides and staleness detection.
  - `SetBuilderUiStage08Test`: Track reordering, set duration calculations, and validation badges.
  - `DspPreviewStage09Test`: Filter sweeps, 3-band EQ parameters, and volume curves.
  - `WholeSetPlaybackStage10Test`: MediaSessionService lifecycle, notification actions, and cross-deck transitions.
  - `OfflineExportStage11Test`: 16-bit / 24-bit PCM rendering, cancellation cleanup, and progress reporting.
  - `ReliabilityStressStage12Test`: Fault injection, memory pressure recovery, and diagnostics scrubbing.
  - `AccessibilityPolishStage13Test`: Touch targets (48dp), font scaling (2.0x), draft undo/redo, and cue steppers.
  - `CiLegalSecurityStage14Test`: Zero network permissions, license attribution, ProGuard rules, and release hashes.

## Running Tests

### Execute Full Test Matrix
```bash
cd base
./gradlew :automix:test :app:testDebugUnitTest --console=plain
```

### Run Stage-Specific Suites
```bash
cd base
# Stage 14 Quality & Security Gate
./gradlew :app:testDebugUnitTest --tests "app.setflow.CiLegalSecurityStage14Test"

# Reliability & Fault Injection Gate
./gradlew :app:testDebugUnitTest --tests "app.setflow.ReliabilityStressStage12Test"
```

### Validate Release Candidate Pipeline
```bash
./scripts/validate-release.sh
```
