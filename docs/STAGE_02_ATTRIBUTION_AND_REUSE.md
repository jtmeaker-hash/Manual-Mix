# Stage 02 — Legal Source Reuse, Attribution & Modification Inventory

- **Date:** 2026-11-12
- **Upstream Baseline:** Myspoti v2.4.0 (`https://github.com/kota-zo/myspoti`, commit `95a24e7e4de82ec89d4ec11d5b05b146c44d78e7`)
- **Stage 01 Baseline Tag:** `baseline-upstream-2.4.0` (`5dfc1aee683a89f8f37d4e66016cc3e176ba08d4`)
- **Project License:** GNU General Public License v3.0 (`LICENSE`, `AGENTS.md`, `THIRD_PARTY_NOTICES.md`)

---

## 1. Retained Upstream Files (`:automix` — Unmodified)

All 23 production files and 14 test files in `base/automix` are retained 100% unmodified under `SPDX-License-Identifier: GPL-3.0-only`:
- `base/automix/src/main/kotlin/app/myspoti/automix/Analyzer.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/AutoMix.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/BeatThis.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/BeatTracker.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/Features.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/KeyFinder.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/MelFrontend.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/MixPlan.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/MixPlanner.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/Parallel.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/PhraseFinder.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/SectionFinder.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/SungSpans.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/TrackAnalysis.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SegueCodec.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SegueCorpus.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SegueDeck.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SegueFormat.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SegueMath.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SeguePlan.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SeguePolicy.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SegueStretch.kt`
- `base/automix/src/main/kotlin/app/myspoti/automix/segue/SegueValidator.kt`

---

## 2. Retained & Decoupled Local Audio Files in `:app`

| File Path | Upstream Origin & License | Stage 02 Modification Status |
|---|---|---|
| `base/app/src/main/assets/automix/beat-this-small0-int8.onnx` | CPJKU Beat This! `small0` int8 ONNX (`MIT`) | **Unmodified** (`5,596,894` bytes, SHA-256 `701b65091844db92a4938bb471429156226681d48414e49334dc6c4516bf53bf`) |
| `base/app/src/main/java/com/music/spotui/automix/SegueProcessor.kt` | Myspoti (`GPL-3.0-only`) | **Unmodified** (`SegueProcessor` and `SegueSink`) |
| `base/app/src/main/java/com/music/spotui/automix/MixPreparation.kt` | Myspoti (`GPL-3.0-only`) | **Unmodified** |
| `base/app/src/main/java/com/music/spotui/automix/AnalysisRetry.kt` | Myspoti (`GPL-3.0-only`) | **Unmodified** |
| `base/app/src/main/java/com/music/spotui/automix/AnalysisIdentity.kt` | Myspoti (`GPL-3.0-only`) | **Unmodified** |
| `base/app/src/main/java/com/music/spotui/automix/DeckEq.kt` | Myspoti (`GPL-3.0-only`) | **Modified (2 lines):** Replaced global `AutoMix.journal(...)` call in `attach()` with optional `onDiagnostic: ((String) -> Unit)? = null` callback to decouple from the removed `AutoMix` app singleton |
| `base/app/src/main/java/com/music/spotui/automix/TrackDecoder.kt` | Myspoti (`GPL-3.0-only`) | **Modified (12 lines):** Removed `OfflineCrypto`, `Graph`, and `ExtStreamCache.Factory(Graph.app)` imports; parameterized `decode(...)` and `ByteSource` with a standard Media3 `DataSource.Factory` (`DefaultDataSource.Factory(context)` / `FileDataSource.Factory()`) for local `content://` and `file://` audio decoding |
| `base/app/src/main/java/com/music/spotui/di/EqualizerProcessor.kt` | BitChord (`b690dc1`) / Myspoti (`GPL-3.0-or-later`) | **Unmodified** (original BitChord + Myspoti attribution header preserved verbatim) |
| `base/app/src/main/java/com/music/spotui/di/SpatialProcessor.kt` | Myspoti (`GPL-3.0-only`) | **Unmodified** |
| `base/app/src/main/java/com/music/spotui/di/CrossfadeRamp.kt` | Myspoti (`GPL-3.0-only`) | **Unmodified** |

---

## 3. Removed Out-of-Scope Modules & Runtime Paths

The following modules and runtime components were removed from the Gradle build and APK in Stage 02:
- `:api`, `:api-spotify`, `:host-android`, `:host-cdp`, `:extensions`, `:extensions-cli`
- Spotify WebView bridge, Widevine DRM playback, ad-blocking, extension sandboxes (Rhino), NewPipeExtractor, Ktor/OkHttp/Cronet networking, Google Cast sender, Spotify Connect mirror, Lyrics/Genius/Radio/Podcasts/Canvas video, and in-app GitHub self-updater (`AppUpdater`).
- All network and package-installation permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `REQUEST_INSTALL_PACKAGES`) are explicitly stripped via `tools:node="remove"` in `base/app/src/main/AndroidManifest.xml`.
