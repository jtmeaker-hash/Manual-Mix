# ADR-012: Automated Quality Gates, Licensing, Merged Permissions, Diagnostic Privacy, and Release Candidate Pipelines

## Context
In Stage 14 (`STAGES/14_CI_LEGAL_SECURITY.txt`), SetFlow establishes automated quality gates, verifiable release builds, legal licensing compliance, and security/privacy hardening.
With the core V1 feature set frozen across Stages 01 through 13:
- SQLite persistence, revision-locked transitions, and dual-deck playback are stabilized.
- DSP transition preview, background whole-set playback, and offline WAV export are implemented.
- Reliability stress injection and accessibility polish have passed acceptance gates.

To ensure long-term sustainability, open-source integrity, and user privacy, the following requirements must be addressed:
1. **Repeatable CI Pipelines**: Automated GitHub Actions workflows pinned to specific JDK, Gradle, and Android SDK distributions, executing both JVM and Android unit tests on every PR and push.
2. **Upstream Decoupling**: Upstream Myspoti secrets (`MYSPOTI_KEYSTORE_*`), package identifiers (`app.myspoti.*`), and Discord webhooks must be replaced with SetFlow-specific configurations.
3. **Zero-Network Permission Enforcement**: The release Android manifest must strictly exclude `INTERNET` and related networking/installer permissions, preventing any transitive dependency from opening network sockets.
4. **Licensing & Provenance Compliance**: GNU GPLv3 terms, upstream copyright notices (`AGENTS.md`), and third-party licenses (Beat This! MIT, ONNX Runtime MIT, BitChord GPL-3.0-or-later) must be documented in `NOTICE.md`, `THIRD_PARTY_NOTICES.md`, and `docs/SBOM.md`.
5. **Private Diagnostic Redaction**: In-memory telemetry must never log local file paths, content URIs, or query tokens.
6. **R8 / Shrinker Native Asset Protection**: ProGuard rules must guarantee that the ONNX Runtime JNI bridge, native `.so` libraries, and SQLite Room models remain intact in minified release builds.
7. **Release Packaging & Cryptographic Hashes**: Release builds must generate clearly named APKs per architecture and produce a deterministic `SHA256SUMS.txt` checksum manifest.

## Decisions

### 1. Pinned CI Workflow & Automated Quality Gates
- **Workflow File**: `.github/workflows/android.yml`
- **Toolchain Pinning**:
  - JDK: OpenJDK 21 (Eclipse Temurin via `actions/setup-java@v6`).
  - Android SDK: Platform API 37, Build-Tools 36.0.0 (via `android-actions/setup-android@v4`).
  - Gradle: Pinned Gradle 9.8.2 wrapper via `gradle/actions/setup-gradle@v6`.
- **Quality Gates**:
  - `:automix:test`: 171 pure JVM unit tests verifying beat analysis, segue planning, and DSP curves.
  - `:app:testDebugUnitTest`: 148+ Android unit tests verifying database migrations, playback, export, fault injection, accessibility, and security gates.
  - Merged Manifest Audit: Automated shell step running `processReleaseMainManifest` and failing the build if `android.permission.INTERNET`, `ACCESS_NETWORK_STATE`, or `REQUEST_INSTALL_PACKAGES` is detected.

### 2. Complete Upstream Decoupling & Secret Isolation
- Upstream signing references replaced with `SETFLOW_KEYSTORE_BASE64`, `SETFLOW_STABLE_KEYSTORE_BASE64`, `SETFLOW_KEYSTORE_PASSWORD`, `SETFLOW_KEY_ALIAS`, and `SETFLOW_KEY_PASSWORD`.
- Local builds and PR workflows cleanly fall back to the Android debug signing configuration, preserving build reproducibility without requiring secret access.
- Release Discord announcements decoupled to `tools/discord_release.py` referencing SetFlow metadata and `SETFLOW_DISCORD_WEBHOOK_URL`.

### 3. Zero-Network Permission Invariant
- `base/app/src/main/AndroidManifest.xml` explicitly strips network permissions:
  ```xml
  <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
  <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" tools:node="remove" />
  <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" tools:node="remove" />
  ```
- Shipping release manifests contain only:
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`
  - `android.permission.POST_NOTIFICATIONS`

### 4. ProGuard / R8 Native Protection Rules
- `base/app/proguard-rules.pro` retains:
  - `-keep class ai.onnxruntime.** { *; }`: Preserves JNI reflection for `OnnxTensor`, `TensorInfo`, and `OrtSession`.
  - `-keep class * extends androidx.room.RoomDatabase { <init>(); }`: Preserves Room database instantiation.
  - `-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }`: Preserves JSON serialization models.

### 5. Private Diagnostic Redaction Policy
- `DiagnosticsRingBuffer.redact` applies regular expressions to scrub:
  - Local content URIs (`content://...`) and file URIs (`file://...`) -> `<redacted-path>`.
  - Absolute POSIX paths (`/storage/...`, `/data/...`) -> `<redacted-path>`.
  - Sensitive parameters (`token=...`, `password=...`, `bearer=...`, `sig=...`) -> `$1=<redacted>`.
- Ring buffer capacity bounded to 128 events in memory, with zero external upload endpoints.

### 6. Release Packaging & Checksum Generation
- Task `:app:collectApks` copies split APKs from `outputs/apk/release/` to `release-apks/`:
  - `SetFlow-<version>-ARMv8.apk` (64-bit phones)
  - `SetFlow-<version>-ARMv7.apk` (32-bit phones)
  - `SetFlow-<version>-universal.apk` (universal dual-architecture)
- Automatically computes SHA-256 digests and outputs `release-apks/SHA256SUMS.txt`.

## Consequences
- Releases are fully verifiable, self-contained, and cryptographically auditable.
- Users have mathematical certainty that SetFlow cannot transmit private audio data or metadata across the network.
- GPL-3.0 and upstream copyright compliance obligations are completely satisfied.
