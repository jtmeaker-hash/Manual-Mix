# SetFlow v1.0.0 Release Summary

**Release Tag**: `v1.0.0`  
**Internal Build Code**: `versionCode = 16`  
**License**: GNU General Public License v3.0 (GPL-3.0-only)  
**Source Repository**: [https://github.com/jtmeaker-hash/Manual-Mix](https://github.com/jtmeaker-hash/Manual-Mix)  
**Date**: November 2026  

---

## 1. Executive Summary

SetFlow v1.0.0 is a standalone, offline Android DJ set builder designed for local audio files. It automates seamless, beat-matched transitions across entire tracklists while providing complete manual override control over transition anchors, tempo ramps, filter sweeps, and 3-band EQ curves. 

Built with privacy as an architectural invariant, SetFlow operates with **zero network permissions**, requires no account or cloud connection, collects no telemetry, and executes all AI beat tracking and DSP rendering on-device.

---

## 2. Core Shipped Features

1. **Local Audio Ingestion (SAF)**:
   - Zero-copy audio ingestion via Android Storage Access Framework (SAF).
   - Lossless and lossy decoder verification: WAV (16/24-bit PCM), FLAC (16/24-bit), MP3 (CBR/VBR), AAC/M4A, OGG Vorbis.
   - Robust relinking workflow for missing or moved audio files.
2. **Beat-Grid & Tempo Analysis**:
   - On-device AI beat and downbeat tracking using the `Beat This!` neural model via ONNX Runtime with ARM NEON acceleration.
   - Deterministic pure-Kotlin spectral-flux fallback on low-power devices.
   - Two-tier LRU memory and persistent disk caching for instant subsequent set loads.
   - Manual beat-grid nudging, phase shifting, and half/double BPM correction without fake confidence scoring.
3. **Set Planning & Non-Destructive Editing**:
   - Crash-safe SQLite persistence with optimistic concurrency revision tracking.
   - Full drag-and-drop set tracklist reordering.
   - Per-transition custom override editor: entry/exit anchor adjustment, tempo ramp curves (Flat, Ramp, Smooth, Instant), resonant filter sweeps, and 3-band EQ kill curves.
   - Dual-occurrence support (the same track can appear multiple times in a set with independent transition parameters).
4. **Deterministic Audio DSP Engine**:
   - Multi-deck sample-clock state machine with equal-power crossfading.
   - Bi-quad resonant filtering and soft-knee headroom limiting to eliminate clipping distortion during overlapping tracks.
   - Isolated transition pair preview deck with zero state pollution to the active timeline.
5. **Continuous Set Playback**:
   - Background audio playback service with Android MediaSession integration, notification controls, and audio focus management.
   - Uninterrupted set playback across 20+ tracks and 3-hour marathon listening sessions.
6. **Master Quality Offline WAV Export**:
   - Bit-exact offline rendering matching real-time DSP playback behavior.
   - Export progress tracking, cancellation support, and disk space pre-flight validation.
7. **Portability & Recovery**:
   - Atomic project backup export and import (JSON format).
   - Forward and backward compatible database migration.

---

## 3. Privacy, Security & Compliance

- **Zero Network Permissions**:
  - `android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`, `android.permission.ACCESS_WIFI_STATE`, and `android.permission.REQUEST_INSTALL_PACKAGES` are explicitly removed from the final merged manifest.
- **Copyleft (GPL-3.0)**:
  - Source code published alongside release binaries in full compliance with GNU GPLv3.
  - Complete third-party licenses documented in `THIRD_PARTY_NOTICES.md` and `docs/SBOM.md`.
  - Upstream Myspoti author attribution preserved in all notices.
- **Privacy-Preserving Diagnostics**:
  - In-app diagnostics scrubbing removes sensitive paths, filenames, and tokens before presentation to the user.

---

## 4. Hardware & Audio Compatibility Matrix

| Category | Supported / Verified | Unsupported / Caveats |
|---|---|---|
| **Operating System** | Android 10 (API 29) through Android 15 (API 36) | Android 9 (API 28) and older |
| **Architectures** | `arm64-v8a` (NEON accelerated), `armeabi-v7a`, `universal` | x86/x86_64 emulators (functional via translation) |
| **Audio Codecs** | WAV (16/24-bit PCM), FLAC (16/24-bit), MP3 (CBR/VBR), AAC/M4A, OGG Vorbis | DRM streams (Widevine, FairPlay), multichannel audio (>2ch downmixed) |
| **Sample Rates** | 44.1 kHz, 48.0 kHz native; >96 kHz downsampled | Non-standard arbitrary integer rates |

---

## 5. Residual Known Limitations (P2/P3)

- **BUG-004 (P2)**: When picking files from a local directory containing >500 audio files, thumbnail generation in the system file picker may cause brief UI stutter. Workaround: organize tracks into album or playlist subfolders.
- **BUG-005 (P3)**: Unusually long track titles (>40 characters) may truncate on compact phone displays (<=360dp width) in the transition override panel.

---

## 6. Binary Manifest & Cryptographic Hashes

Release APKs assembled via `./gradlew assembleRelease :app:collectApks`:

| Artifact Filename | Target ABI | Size | SHA-256 Checksum |
|---|---|---|---|
| `SetFlow-1.0.0-ARMv8.apk` | 64-bit ARM (ARMv8-a) | ~26.0 MB | Verified in `SHA256SUMS.txt` |
| `SetFlow-1.0.0-ARMv7.apk` | 32-bit ARM (ARMv7-a) | ~20.8 MB | Verified in `SHA256SUMS.txt` |
| `SetFlow-1.0.0-universal.apk` | All supported ABIs | ~39.8 MB | Verified in `SHA256SUMS.txt` |
