# SetFlow

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![CI / CD](https://img.shields.io/badge/CI%2FCD-passing-success.svg)](.github/workflows/android.yml)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029--36)-brightgreen.svg)](base/app/build.gradle.kts)
[![Architecture: Local--Only](https://img.shields.io/badge/Architecture-100%25%20Offline-blueviolet.svg)](#privacy--security-invariants)

**SetFlow** is an open-source, 100% offline Android DJ Set Builder and transition editor for mobile DJ performance, mix preparation, and live set execution. It pairs the intuitive organization of playlist builders with the surgical precision of digital audio workstation transition planning.

---

## Features

### 🎧 Local Audio Import & Library
- **100% DRM-Free**: Import user-owned local tracks via Android Storage Access Framework (`ACTION_OPEN_DOCUMENT`).
- **Broad Format Support**: Decodes WAV (16/24-bit PCM), FLAC, MP3, AAC (M4A), and OGG Vorbis via Android Media3.
- **SQLite Persistence**: Robust Room database schema persisting projects, set tracks, cue points, and revision-locked transition overrides across app restarts.

### ⚡ On-Device Beat Grid & Tempo Analysis
- **Machine Learning Beat Tracking**: Integrated `small0` int8 ONNX beat tracking model (*Beat This!* ISMIR 2024) executing purely on-device via Microsoft ONNX Runtime.
- **Accurate Downbeat Detection**: Estimates BPM, first beat anchor, downbeat phase, and 4-beat bar boundaries.
- **Adaptive Fallback Engine**: Gracefully handles irregular time signatures, floating tempos, and ungridded audio.

### 🎛️ Dual-Deck Interactive Waveform Transition Editor
- **Responsive Dual-Waveform Canvas**: Visualizes outgoing (Deck A, Cyan `#26C6DA`) and incoming (Deck B, Amber `#FFA726`) waveforms with dynamic layout geometry across phones and tablets.
- **Fine-Grained Stepper Granularity**:
  - Gridded mode: `-1 Bar`, `-1 Beat`, `+1 Beat`, `+1 Bar`.
  - Ungridded / fine mode: `-1.0s`, `-100ms`, `+100ms`, `+1.0s`.
- **In-Memory Draft Undo/Redo**: 50-operation undo/redo draft stack for cue point adjustments before committing to disk.
- **Reset-to-Auto Confirmation**: Protected restoration of automated transition recommendations.
- **Accessibility & TalkBack**: Full 48dp minimum touch target compliance and dynamic font scaling up to 2.0x without layout clipping.

### 🎚️ Multi-Lane DSP Transition Engine
- **Automated Mix Transitions**: 12 curated transition styles including `BASS_SWAP`, `THREE_BAND`, `FILTER_OPEN`, `CUT_ON_BEAT`, `ECHO_TAIL`, and `EVEN_FADE`.
- **Parametric Equalization & Filter Sweeps**: State-variable filter sections delivering 3-band EQ (bass, mid, treble) and high-pass / low-pass resonant filter sweeps.
- **Source Presentation Synchronization**: Sample-accurate presentation timestamp alignment between Media3 audio processors and DSP curves.

### 📻 Continuous Whole-Set Playback
- **MediaSessionService Architecture**: `SetPlaybackService` manages uninterrupted background audio playback across the entire set.
- **Android Media Controls**: Full notification controls, lock screen transport controls, and dynamic position reporting.
- **Cross-Deck Timeline Engine**: Glitchless transition scheduling across deck swaps without audio seams or packet dropouts.

### 💾 Offline Master WAV Export
- **Lossless PCM Rendering**: Direct-to-storage offline rendering of the entire compiled set into master 16-bit or 24-bit 44.1kHz stereo WAV files.
- **Background Execution & Progress**: Live progress reporting with cancellation and automatic partial file cleanup via `ACTION_CREATE_DOCUMENT`.

---

## Privacy & Security Invariants

SetFlow is designed under a strict **Zero-Network Policy**:
- **Zero Network Permissions**: The shipping Android manifest completely strips `android.permission.INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, and `REQUEST_INSTALL_PACKAGES` via `tools:node="remove"`.
- **No Analytics / Telemetry**: Zero analytics SDKs, zero crash report uploaders, zero cloud inference, and zero remote tracking.
- **Diagnostic Privacy**: The in-memory `DiagnosticsRingBuffer` automatically scrubs all file URIs (`content://`, `file://`), absolute filesystem paths, and query secrets (`token=`, `key=`, `password=`).
- **No Third-Party Streaming / DRM**: Zero Spotify sign-in, zero DRM bypassing, and zero stream scraping. All features operate exclusively on user-provided local audio files.

---

## Build & Verification

### Prerequisites
- **JDK**: OpenJDK 21 (Temurin recommended)
- **Android SDK**: Compile SDK 37, Target SDK 36, Min SDK 29, Build-tools 36.0.0
- **Gradle**: 9.8.2 (via pinned Gradle wrapper)

### Command Reference
```bash
# Clone the repository
git clone https://github.com/jtmeaker-hash/Manual-Mix.git
cd Manual-Mix/base

# Run all unit tests (automix JVM suite + app Android unit tests)
./gradlew :automix:test :app:testDebugUnitTest

# Build debug APK
./gradlew assembleDebug

# Build release candidate APKs and collect signed artifacts with SHA256 checksums
./gradlew assembleRelease :app:collectApks

# Inspect collected release artifacts
ls -la app/build/release-apks/
cat app/build/release-apks/SHA256SUMS.txt
```

### Release Artifacts
`assembleRelease :app:collectApks` generates:
- `SetFlow-<version>-ARMv8.apk`: 64-bit ARM architecture (most modern phones and tablets).
- `SetFlow-<version>-ARMv7.apk`: 32-bit legacy ARM architecture.
- `SetFlow-<version>-universal.apk`: Universal multi-architecture build.
- `SHA256SUMS.txt`: Cryptographic SHA-256 verification hashes for all generated APKs.

---

## Documentation

- [Architecture Overview](docs/architecture.md)
- [Testing & Quality Gates](docs/testing.md)
- [Known Issues & Limitations](docs/known-issues.md)
- [Release Management & Signing](docs/release.md)
- [Software Bill of Materials (SBOM)](docs/SBOM.md)
- [Architectural Decision Records (ADRs)](docs/decisions/)
- [Third-Party Notices](THIRD_PARTY_NOTICES.md)
- [Legal Notice & Attribution](NOTICE.md)

---

## Licensing & Provenance

SetFlow is licensed under the **GNU General Public License v3.0** (see [LICENSE](LICENSE)).

### Upstream Attribution
SetFlow is derived from the open-source **Myspoti** project (v2.4.0, commit `95a24e7e4de82ec89d4ec11d5b05b146c44d78e7`), created by kota-zo and contributors:
- Upstream: https://github.com/kota-zo/myspoti
- In compliance with upstream `AGENTS.md` and GPL-3.0, upstream copyright notices and licenses are preserved in full.
- All Spotify web, ad-blocking, network extension, Cast, and DRM components were removed; only the pure Kotlin `:automix` engine and local Media3 audio processing components were adapted.

### Third-Party Credits
- **Beat This!** (MIT): Francesco Foscarin, Jan Schlüter, and Gerhard Widmer (CPJKU, Johannes Kepler University Linz).
- **ONNX Runtime** (MIT): Copyright Microsoft Corporation.
- **BitChord** (GPL-3.0-or-later): Equalizer processor and UI inspiration.
- **AndroidX & Jetpack Compose** (Apache-2.0): The Android Open Source Project.
- **Media3** (Apache-2.0): The Android Open Source Project.

See [NOTICE.md](NOTICE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for full licensing details.
