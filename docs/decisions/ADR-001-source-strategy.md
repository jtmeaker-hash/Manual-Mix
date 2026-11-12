# ADR-001: Source Strategy — Isolating `:automix` and Local Audio Components vs. In-Place Pruning of Upstream `:app`

- **Date:** 2026-11-12
- **Status:** Proposed (Tentative selection pending Stage 01 baseline build verification and Stage 02 execution)
- **Owner:** SetFlow Engineering / Agent
- **Stage:** Stage 00 (`00_REPO_INTAKE`)

---

## 1. Problem & Measured Evidence

Upstream Myspoti (`https://github.com/kota-zo/myspoti` at `95a24e7e4de82ec89d4ec11d5b05b146c44d78e7`, `v2.4.0`) contains two distinct architectural halves inside `base/`:

1. **A self-contained, high-value local audio analysis and transition engine:**
   - `:automix` (`base/automix/src/main/kotlin/app/myspoti/automix/`, 23 source files + 14 test files): **0 Android imports, 0 Spotify imports.** Depends only on the Kotlin standard library and `com.microsoft.onnxruntime:onnxruntime:1.23.2`.
   - `base/app/src/main/assets/automix/beat-this-small0-int8.onnx` (MIT-licensed int8 Beat This! `small0` model).
   - `base/app/src/main/java/com/music/spotui/automix/`:
     - `SegueProcessor.kt` (107 lines): **0 Spotify/app imports**; pure Media3 `BaseAudioProcessor` + `ForwardingAudioSink` wrapping `SegueDeck`.
     - `MixPreparation.kt` (39 lines), `AnalysisRetry.kt` (19 lines), `DeckEq.kt` (54 lines): pure helpers with minimal coupling.
     - `TrackDecoder.kt` (346 lines): MediaCodec/MediaExtractor decoder resampling to 22.05 kHz mid/side; only coupled at 1 line (`ExtStreamCache.Factory(Graph.app).createDataSource()`).
   - `base/app/src/main/java/com/music/spotui/di/`:
     - `EqualizerProcessor.kt` (BitChord-derived GPL-3.0-or-later state-variable filter AudioProcessor), `SpatialProcessor.kt`, `CrossfadeRamp.kt`.

2. **A deeply coupled Spotify WebPlayer, extension, and streaming application:**
   - Modules `:api`, `:api-spotify`, `:host-android`, `:host-cdp`, `:extensions`, `:extensions-cli`.
   - In `:app`: over 90% of `com.music.spotui.*` (`data/api/*`, `data/offline/*`, `extensions/*`, `cast/*`, `connect/*`, `radio/*`, `video/*`, `podcast/*`, `ui/immersive/*`, `ui/screens/*`, `di/SongPlayer.kt`) is tightly bound to `Graph.api` (`MyspotiRuntime.api`), Spotify account state, encrypted extension downloads (`OfflineStore`, `ExtStreamCache`), and WebView ad-blocking.
   - Even `LocalPlayer.kt` (2,061 lines) interleaves its two-deck AutoMix state machine (lines 503–1320) with `SongPlayer`, `CastPlayback`, `ExtensionAudio`, `ExtStreamCache`, `OfflineStore`, `QueueSync`, `ConnectMirror`, and `LyricsApi`.

We must decide how to structure the repository from Stage 02 onward after verifying the untouched upstream baseline in Stage 01.

---

## 2. Decision Drivers

1. **Legal, Privacy & Security Isolation:** Zero Spotify WebView, DRM/Widevine, ad-blocking, download extensions, or `INTERNET` / `REQUEST_INSTALL_PACKAGES` permissions in the shipping app (`REFERENCE/07_LICENSE_PRIVACY_SECURITY.txt`).
2. **GPL-3.0 Compliance:** Retain full upstream `LICENSE`, `AGENTS.md`, `THIRD_PARTY_NOTICES.md`, and per-file `SPDX-License-Identifier` headers (`GPL-3.0-only` / `GPL-3.0-or-later`).
3. **Audio Engine Integrity:** Preserve exact behavior and test coverage of `:automix`, `SeguePlan`, `SegueValidator`, `SegueDeck`, `SegueProcessor`, `TrackDecoder`, and two-deck `ExoPlayer` scheduling without introducing regressions.
4. **Build Stability & Minimal Churn:** Avoid unnecessary Gradle build-system rewrites while ensuring clean compile-time boundaries so Spotify/network code cannot accidentally be called or packaged.
5. **Maintainability for SetFlow V1 Requirements:** Support occurrence-based set lists, per-edge `AutoPlan` + `Override` -> `ResolvedPlan` persistence (Room), pair preview, whole-set playback, and offline WAV export.

---

## 3. Options Considered

### Option A: Clean Local-Only Shell in `:app` (or `:setflow-app`) Retaining `:automix` Intact & Extracting Decoupled Local Audio Primitives (Recommended)

- **Description:**
  - Keep the repository root (`LICENSE`, `AGENTS.md`, `THIRD_PARTY_NOTICES.md`) and `base/automix` (`:automix`) **100% intact** with its existing unit tests and desktop bench.
  - After Stage 01 verifies the unmodified baseline build and unit tests, Stage 02 reconfigures `base/settings.gradle.kts` and `base/app` (under a new unique namespace/applicationId such as `app.setflow` and package `app.setflow.*`) so `:app` depends **only** on `:automix`, AndroidX Media3, Room, Compose, and ONNX Runtime — dropping `:host-android`, `:api-spotify`, `:api`, `:extensions`, Ktor, OkHttp, Cronet, and NewPipeExtractor from `:app` dependencies.
  - Extract and keep only the verified local-audio classes (`SegueProcessor.kt`, `MixPreparation.kt`, `DeckEq.kt`, `AnalysisRetry.kt`, `AnalysisIdentity.kt`, `TrackDecoder.kt` bound to local `ContentResolver`/`MediaDataSource`, `EqualizerProcessor.kt`, `SpatialProcessor.kt`, `CrossfadeRamp.kt`, `beat-this-small0-int8.onnx`), and adapt the two-deck `ExoPlayer` transition engine from `LocalPlayer.kt` into a clean, lifecycle-owned playback controller without Spotify/Cast/Extension dependencies.
- **Pros:**
  - Guarantees at compile time and Gradle dependency-graph level that no Spotify, WebView, Widevine, extension, or network library is packaged into the APK.
  - Zero changes required to `:automix` pure Kotlin engine.
  - Avoids playing whack-a-mole with hundreds of broken references across 150+ Spotify UI/data files in `com.music.spotui.*`.
- **Cons / Risks:**
  - Requires careful extraction of the two-deck `ExoPlayer` scheduling and `TrackDecoder` local `DataSource` from `LocalPlayer.kt` / `TrackDecoder.kt` in Stages 02–05 so no subtle timing behavior (`setEnableAudioTrackPlaybackParams(true)`, `SegueSink` presentation timestamp offset, `startLead` calibration) is lost.

### Option B: Incremental In-Place Pruning of Existing `com.music.spotui.*` `:app` and `:host-android`

- **Description:**
  - Keep `:app` and `:host-android` wired together and delete Spotify screens/classes piecemeal while trying to keep `Graph`, `SongPlayer`, and `LocalPlayer` compiling.
- **Pros:**
  - `LocalPlayer.kt` compiles initially without refactoring its internal calls to `SongPlayer` and `Graph`.
- **Cons / Risks:**
  - **High coupling hazard:** `LocalPlayer.kt`, `SongPlayer.kt`, `Graph.kt`, and `MainActivity.kt` reference `:host-android` (`MyspotiRuntime`, `WebPlayerHost`, `PlaybackService`), `:api-spotify`, `:extensions`, `CastPlayback`, `OfflineStore`, and `LyricsApi` in dozens of places. Pruning in-place either leaves dead Spotify/WebView/network code in the binary (violating Stage 02 and `07_LICENSE_PRIVACY_SECURITY.txt`) or triggers cascading compile errors across the entire legacy UI.
  - Upstream's `LocalPlayer` is a queue-based singleton designed around Spotify track URIs, not an occurrence/edge-indexed Set Builder with per-edge saved `SeguePlan` overrides.

### Option C: Clean-Room Reimplementation of Analysis & Transition DSP

- **Description:**
  - Discard `:automix` and rewrite beat tracking, `SeguePlan`, `SegueValidator`, and `SegueDeck` from scratch.
- **Pros:**
  - No upstream architectural legacy.
- **Cons / Risks:**
  - **Explicitly prohibited by `00_START_HERE.txt` Hard Stop Conditions** ("Agent proposes replacing the proven engine wholesale without a measured, reviewable ADR").
  - High bug risk and loss of tested `SeguePlan`/`SegueValidator`/`SegueDeck`/`BeatThis` functionality.

---

## 4. Tentative Decision & Rationale

**Tentative Selection: Option A** (Retain `:automix` + `beat-this-small0-int8.onnx` + `SegueProcessor` / `MixPreparation` / `DeckEq` / `TrackDecoder` / `EqualizerProcessor` intact in the GPL-3.0 repository; sever `:host-android`, `:api-spotify`, `:api`, and `:extensions` from `:app` in Stage 02; build the local-only shell and dedicated two-deck Media3 controller around the extracted local audio primitives).

Final confirmation of Option A will occur at the conclusion of **Stage 01 (`01_BASELINE_BUILD.txt`)** once we have measured the unmodified upstream build and unit test execution times on this machine.

---

## 5. Components Affected (Stage 02+ Preview)

- **Retained Unmodified (`:automix`):**
  - `base/automix/build.gradle.kts`
  - `base/automix/src/main/kotlin/app/myspoti/automix/**`
  - `base/automix/src/test/kotlin/app/myspoti/automix/**`
  - `base/app/src/main/assets/automix/beat-this-small0-int8.onnx`
- **Retained & Decoupled in `:app` (Stage 02–05):**
  - `SegueProcessor.kt`, `MixPreparation.kt`, `DeckEq.kt`, `AnalysisRetry.kt`, `AnalysisIdentity.kt` (file identity), `TrackDecoder.kt` (local `ContentResolver`/`MediaDataSource`), `EqualizerProcessor.kt`, `SpatialProcessor.kt`, `CrossfadeRamp.kt`, and the two-deck `ExoPlayer` + `SegueSink` + PLL synchronization logic from `LocalPlayer.kt`.
- **Disconnected from Build Graph in Stage 02:**
  - `:api`, `:api-spotify`, `:host-android`, `:host-cdp`, `:extensions`, `:extensions-cli`, and Spotify/network/extension packages in `:app`.

---

## 6. License Audit & Rollback Path

- **License Audit:** All retained code is `GPL-3.0-only` (Myspoti) or `GPL-3.0-or-later` (BitChord `EqualizerProcessor.kt`), with MIT model/runtime (`Beat This!`, `ONNX Runtime`) and Apache-2.0 AndroidX/Compose libraries. Full `LICENSE`, `AGENTS.md`, `THIRD_PARTY_NOTICES.md`, and per-file headers are preserved.
- **Rollback Path:** The unmodified upstream commit `95a24e7e4de82ec89d4ec11d5b05b146c44d78e7` (`v2.4.0`) and the Stage 00/01 tags remain in Git history; any extraction step in Stage 02 can be reverted atomically to the Stage 01 baseline tag.

---

## 7. Stage 01 Build Plan & Tests to Prove Success

1. **Stage 01 (`01_BASELINE_BUILD.txt`):**
   - Verify Android SDK platform `android-37` availability in `/root/android-sdk`.
   - Run `./gradlew :automix:test` and `./gradlew testDebugUnitTest` from `base/`.
   - Build `:app:assembleDebug` (including `-PautomixLab` isolated Mix Lab variant).
2. **Stage 02 (`02_LOCAL_ONLY_SHELL.txt`):**
   - Prove the new local-only application ID builds, launches cold with zero network/Spotify permissions in merged manifest, and passes `:automix:test` + `:app:testDebugUnitTest`.
