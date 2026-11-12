# Stage 01 — Clean Upstream Build and Audio Baseline Report

- **Date:** 2026-11-12
- **Repository:** `/root/SetFlow` (`https://github.com/jtmeaker-hash/Manual-Mix.git`)
- **Upstream Baseline Commit:** `95a24e7e4de82ec89d4ec11d5b05b146c44d78e7` (`v2.4.0`, `https://github.com/kota-zo/myspoti`)
- **Stage 00 Intake Commit:** `d4eda206c142df09405426ea6e0310a855539d6b` (`stage-00-intake`)
- **Branch:** `audit/stage-00-intake` (mirrored to `main` on `origin`)

---

## 1. Environment & Toolchain Verification (Task 1)

| Component | Verified Value |
|---|---|
| Host OS / Kernel | `Linux localhost 6.17.0-PRoot-Distro #2 SMP PREEMPT Thu Mar 5 05:26:52 UTC 2026 aarch64` |
| System JDK | OpenJDK `21.0.12.1` (`2026-07-23`) at `/usr/lib/jvm/java-21-openjdk-arm64` |
| Gradle Toolchain JDK 17 | Eclipse Adoptium OpenJDK `17` (`aarch64-linux`) at `/root/.gradle/jdks/eclipse_adoptium-17-aarch64-linux.2` |
| Gradle Wrapper | `9.8.2` (`base/gradle/wrapper/gradle-wrapper.properties`, SHA-256 `ec8b1f2dc0dc5fbffd5ffce1efbe2ab68fbde6bc4bf4f67920efad97beabdf6c`) |
| Android SDK | `/root/android-sdk` (`base/local.properties`, untracked) |
| Android SDK Platforms | `android-36`, `android-37.0` (API 37.0 rev 3 installed via `sdkmanager "platforms;android-37.0"`) |
| Android Build Tools / AAPT2 | `36.0.0` (`/root/android-sdk/build-tools/36.0.0/aapt2` via `/root/.gradle/gradle.properties`) |
| FFmpeg (for `:automix:bench`) | `8.1.2` (`/data/data/com.termux/files/usr/bin/ffmpeg`, symlinked at `/usr/local/bin/ffmpeg`) |
| ADB | `/usr/bin/adb` (`Android Debug Bridge version 1.0.41`, `34.0.4-debian`); `adb devices -l` -> empty (no device/emulator attached) |

---

## 2. Unmodified Upstream Gradle Builds & Unit Tests (Tasks 2 & 3)

All commands were executed from `/root/SetFlow/base` against unmodified upstream source files (`95a24e7e4de82ec89d4ec11d5b05b146c44d78e7`).

### 2.1 Executed Build & Test Commands

| Command | Exit Code | Wall Time | Result Summary |
|---|---:|---:|---|
| `./gradlew :automix:test --info` | `0` | `5m 17s` (first run incl. wrapper + JDK 17 download; test execution `14.669s`) | `10` suites, `171` tests, `171` passed, `0` failed, `0` skipped |
| `./gradlew testDebugUnitTest --info` | `1` | `12m 33s` | `:host-android:testDebugUnitTest` passed (`65/65`); `:app:testDebugUnitTest` ran `638` tests (`632` passed, `6` failed in `TvScreenshotTest` due to Robolectric native runtime unsupported on `linux-aarch64`) |
| `./gradlew assembleDebug --info` | `0` | `6m 16s` | Built `:host-android` debug AAR and `:app` debug APKs (`arm64-v8a`, `armeabi-v7a`, `universal`) |
| `./gradlew :app:assembleDebug -Pabis=arm64-v8a -PautomixLab -PappVersionCode=3115 --info` | `0` | `2m 16s` | Built standalone Mix Lab APK (`applicationId=app.myspoti.automixlab`, `versionCode=3115`) |
| `./gradlew :app:assembleDebug -Pabis=arm64-v8a -PautomixLab -PappVersionCode=3115 --offline` | `0` | `15s` | Offline build reproducibility confirmed (`66 actionable tasks: 66 up-to-date`) |
| `./gradlew :api:test :api-spotify:test :extensions:test :extensions-cli:test --info` | `0` | `3m 53s` | `70` suites, `497` tests, `497` passed, `0` failed, `0` skipped |
| `./gradlew :host-cdp:test --info` | `0` | `22s` | `7` suites, `37` tests, `0` failed, `37` skipped (`Assumptions` guard when live Chrome CDP session is absent) |

### 2.2 Complete Unit Test Inventory Across All 8 Modules

| Module & Task | Suites | Total Tests | Passed | Failed | Skipped | Test Time (s) | Notes |
|---|---:|---:|---:|---:|---:|---:|---|
| `:automix:test` | 10 | 171 | 171 | 0 | 0 | 14.669 | All DSP, beat grid, planner, `SeguePlan`, `SegueValidator`, `SegueDeck`, `SegueBench`, and `SegueStretch` tests pass |
| `:host-android:testDebugUnitTest` | 10 | 65 | 65 | 0 | 0 | 1.037 | All Android host unit tests pass |
| `:app:testDebugUnitTest` | 89 | 638 | 632 | 6 | 0 | 32.562 | All 6 failures are in `com.music.spotui.ui.tv.TvScreenshotTest` (`artist`, `player`, `search`, `home`, `list`, `devices`): `AssertionError: The Robolectric native runtime is not supported on Linux (aarch64)`. All `:app` Automix/Segue tests (`SegueProcessorTest`, `AutomixServiceTest`) pass |
| `:api:test` | 0 | 0 | 0 | 0 | 0 | 0.000 | No test sources in `:api` |
| `:api-spotify:test` | 39 | 247 | 247 | 0 | 0 | 190.258 | Out-of-scope Spotify API module |
| `:extensions:test` | 28 | 232 | 232 | 0 | 0 | 24.787 | Out-of-scope extensions module |
| `:extensions-cli:test` | 3 | 18 | 18 | 0 | 0 | 5.323 | Out-of-scope extensions CLI module |
| `:host-cdp:test` | 7 | 37 | 0 | 0 | 37 | 0.032 | Out-of-scope live Chrome CDP tests (skipped via JUnit assumption when `-Dmyspoti.cdp` is empty) |
| **Grand Total** | **186** | **1,408** | **1,365** | **6** | **37** | **268.668** | **100% of in-scope `:automix` (171/171) and `:app` Automix/Segue unit tests pass** |

### 2.3 `:automix:test` Suite Breakdown

| Suite | Tests | Passed | Failed | Skipped | Time (s) |
|---|---:|---:|---:|---:|---:|
| `app.myspoti.automix.AnalysisStructureTest` | 9 | 9 | 0 | 0 | 0.365 |
| `app.myspoti.automix.AutoMixTest` | 13 | 13 | 0 | 0 | 12.824 |
| `app.myspoti.automix.MixLevelsTest` | 2 | 2 | 0 | 0 | 0.094 |
| `app.myspoti.automix.MixPlannerPolicyTest` | 19 | 19 | 0 | 0 | 0.080 |
| `app.myspoti.automix.SungSpansTest` | 6 | 6 | 0 | 0 | 0.011 |
| `app.myspoti.automix.segue.SegueBenchTest` | 11 | 11 | 0 | 0 | 0.206 |
| `app.myspoti.automix.segue.SegueDeckTest` | 20 | 20 | 0 | 0 | 0.399 |
| `app.myspoti.automix.segue.SegueFormatTest` | 29 | 29 | 0 | 0 | 0.110 |
| `app.myspoti.automix.segue.SeguePolicyTest` | 47 | 47 | 0 | 0 | 0.546 |
| `app.myspoti.automix.segue.SegueStretchTest` | 15 | 15 | 0 | 0 | 0.034 |

---

## 3. APK, Manifest, Target ABI & Native/Model Asset Inspection (Task 3)

### 3.1 Built APK Artifacts

| Variant | Output File | Size (Bytes) | SHA-256 | `applicationId` | `versionCode` | `versionName` | `minSdk` / `targetSdk` / `compileSdk` |
|---|---|---:|---|---|---:|---|---|
| Default Debug (`arm64-v8a`) | `app-arm64-v8a-debug.apk` | `140,678,621` | `c61f20b9ebe161be28bd8fe52471b4b4e593dc1c129a6e8f56f121f4ff1c4c1f` | `app.myspoti.dev` | `200` | `2.4.0` | `29` / `36` / `37` |
| Default Debug (`armeabi-v7a`) | `app-armeabi-v7a-debug.apk` | `135,139,801` | `927fc2e324e901fd439445d0f4773fb9e299a66b3a8ac6cbffeef13ce2394335` | `app.myspoti.dev` | `200` | `2.4.0` | `29` / `36` / `37` |
| Default Debug (`universal`) | `app-universal-debug.apk` | `155,357,979` | `e329c402d233a0306e89458a1fe36ec201a0c7d8e5e728fa5152acadf1d79d0c` | `app.myspoti.dev` | `200` | `2.4.0` | `29` / `36` / `37` |
| Mix Lab Debug (`-Pabis=arm64-v8a -PautomixLab -PappVersionCode=3115`) | `app-debug.apk` | `140,678,629` | `f85ad7ba4c358c6ae3277e9ab6e36ecd5c72b76642ebadef93981cb43332531f` | `app.myspoti.automixlab` | `3115` | `2.4.0` | `29` / `36` / `37` |

### 3.2 Bundled Native Libraries & ONNX Model Asset (`arm64-v8a` & Mix Lab APKs)

Verified via `unzip -l` and `aapt2 dump badging`:
- `lib/arm64-v8a/libonnxruntime.so` — `19,347,616` bytes (present)
- `lib/arm64-v8a/libonnxruntime4j_jni.so` — `100,648` bytes (present)
- `lib/arm64-v8a/libzstd-kmp.so` — `731,872` bytes (present)
- `lib/arm64-v8a/libandroidx.graphics.path.so` — `10,096` bytes (present)
- `assets/automix/beat-this-small0-int8.onnx` — `5,596,894` bytes (present, SHA-256 `701b65091844db92a4938bb471429156226681d48414e49334dc6c4516bf53bf`)
- Mix Lab APK `application-label`: `'Myspoti Mix Lab'` and distinct `applicationId` `'app.myspoti.automixlab'` (guaranteed not to overwrite stable Myspoti).

---

## 4. Synthetic Test Harness & `:automix:bench` Baseline Measurements (Task 4)

Because upstream private benchmark songs (`/tmp/automix-bench`) are not distributed in the repository, a deterministic synthetic fixture generator (`/tmp/setflow-bench-stage01/generate_fixtures.py`, kept outside the git worktree) was created to synthesize 44.1 kHz stereo float (`.f32`) and 16-bit PCM WAV (`.wav`) tracks with exact analytical beat/downbeat ground truth (`.beats` and `.json`):

1. **`s01` (`155.000 BPM`, 64 bars = 256 beats, offset `0.500 s`, duration `101.597 s`, `4,480,418` frames):**
   - Exact metronome click burst on every beat (`1600 Hz` 15 ms with 2 ms Hann window at `-6 dBFS` on downbeat beat 1; `900 Hz` 12 ms at `-10 dBFS` on beats 2–4) + pitch-drop kick (`120->45 Hz`), snare, eighth-note hi-hat, and 4-bar sub-bass progression (`55/65.4/73.4/55 Hz`).
   - `.f32` SHA-256: `a5d073cb6cb905f27c34f0b1e7310c45c8fe05ead591666fad3e32dde75ecf6b`
   - `.wav` SHA-256: `a1cee560f7c089d9c389dfb8b835bedd5cc340756ec261e4a748eef7524f44e0`
2. **`s02` (`160.000 BPM`, 64 bars = 256 beats, offset `0.500 s`, duration `98.500 s`, `4,343,850` frames):**
   - Same structure at `160.000 BPM` (`+3.226%` tempo delta relative to `155.000 BPM`).
   - `.f32` SHA-256: `3dc29e01eccffd7951ba23d35ba29e4043c64e31a67ba099ae739d812ad2e48a`
   - `.wav` SHA-256: `ce4dd75786f6f1316ea42c96c1c718ef814b19d3af88a00a3af1f1cd76bc61bc`
3. **Analytical Equal-Power Crossfade Reference (`ref_equal_power_s01_s02.wav`):**
   - 8-bar (`32`-beat, `546,271`-frame) equal-power crossfade (`gainA = cos(pi/2 * u)`, `gainB = sin(pi/2 * u)`) with `s02` resampled by `speedB = 155/160 = 0.96875`.
   - `peakBlendLinear = 1.385929` (`+2.83 dBFS`), `rmsBlendDbfs = -16.98 dBFS`, `clippingFrames = 270`, WAV SHA-256 `166c1b294d6e057807fb410270b8fc262f7934ab076924901c86ad63c99967c6`.
   - Demonstrates why un-attenuated equal-power summing of beat-aligned kicks/sub-bass exceeds `0 dBFS` (`+2.83 dBFS`) without bass-swap or headroom limiting.

### 4.1 `:automix:bench` Execution & Objective Audio Metrics

#### A. `./gradlew :automix:bench --args="analyze /tmp/setflow-bench-stage01" --console=plain` (Exit Code `0`)

```text
s01  8003ms bpm 155.00 steady true  head 155.00/1.00/0ms/3.6✓ tail 155.00/1.00/0ms/3.6✓ key 21(0.55) loud -10.2 content 0.48-99.58 phrase 4
      grid 155.00/1.00/0ms/3.6✓ q 1.00/2ms/255 gridF head 1.00 tail 1.00 | modelF head 1.00 tail 1.00 | grid-vs-model head 1.00 tail 1.00
      head/tail grid downF 1.00 / 1.00
      ref bpm 155.00 | beatF 1.00 (half 0.67, double 0.67) offset +13.3ms | downF 0.99
s02  6410ms bpm 160.00 steady true  head 160.00/1.00/0ms/3.7✓ tail 160.00/1.00/0ms/3.7✓ key 21(0.56) loud -10.2 content 0.48-96.48 phrase 3
      grid 160.00/1.00/0ms/3.7✓ q 1.00/2ms/255 gridF head 1.00 tail 1.00 | modelF head 1.00 tail 1.00 | grid-vs-model head 1.00 tail 1.00
      head/tail grid downF 1.00 / 1.00
      ref bpm 160.00 | beatF 1.00 (half 0.67, double 0.67) offset  +5.3ms | downF 0.99
```

#### B. `./gradlew :automix:bench --args="matrix /tmp/setflow-bench-stage01 fresh" --console=plain` (Exit Code `0`)

```text
track	s01	analysed	8063.7 ms	155.00 BPM	3 sections	musicEnd 99.6	lastLoud 99.6
track	s02	analysed	6469.9 ms	160.00 BPM	2 sections	musicEnd 96.5	lastLoud 96.5
pair	style	seconds	bars	exitA	entryB	speedB	plan_ms	leftA	reason
s01,s02	BLEND	18.581	12.0	76.384	0.505	0.96875	36.286	4.6	A section at 76.4, B first downbeat at 0.5, 155.0→160.0 BPM, key compatible, score 5.7
s02,s01	BLEND	24.000	16.0	71.005	0.513	1.03226	4.287	1.5	A section at 71.0, B first downbeat at 0.5, 160.0→155.0 BPM, key compatible, score 7.2
summary	pairs=2	BLEND=2	FILTER=0	CUT=0	FADE=0	blend_12plus_bars=2	median_seconds=18.58	median_leftA=1.5	plan_median_ms=4.287	plan_p95_ms=4.287
```

#### C. `./gradlew :automix:bench --args="plan /tmp/setflow-bench-stage01 s01,s02 s02,s01 render" --console=plain` (Exit Code `0`)

```text
s01→s02 BLEND A 88.77→98.06 (9.3 s, 24 beats) B from 6.51 ×0.9688 swap 0.50 gainB 1.00 | A phrase at 88.8, B phrase at 6.5, 155.0→160.0 BPM, key compatible, score 4.5
      ref beats: median 8 ms, 90% 8 ms over 24 B beats | downbeats on A downbeats: median 8 ms
s02→s01 BLEND A 71.01→95.01 (24.0 s, 64 beats) B from 0.51 ×1.0323 swap 0.50 gainB 1.00 | A section at 71.0, B first downbeat at 0.5, 160.0→155.0 BPM, key compatible, score 6.6
      ref beats: median 8 ms, 90% 8 ms over 64 B beats | downbeats on A downbeats: median 8 ms
```
- Rendered `mixplanner-s01-s02.wav` SHA-256: `2b499c6100503b0188d8d0b9406a0cd5d2d27dc27d0fbede4e7af40f552fb4e3`
- Rendered `mixplanner-s02-s01.wav` SHA-256: `65eba0f662474ae960679a0ff18bd0e39a68b40d18b751501e6bc091e1ca28c9`

#### D. `./gradlew :automix:bench --args="segue /tmp/setflow-bench-stage01 matrix" --console=plain` (Exit Code `0`)

```text
songs	2	with phase on tail 2	on head 2	reliable tail 2
pair	level	seconds	exit	entry	rate	score	ms	shape	why
s01,s02	LOCKED_SHORT	12.397	76.380	2.003	968437	1.919	562.12	BASS_SWAP	LOCKED_LONG: NO_GRID ( of 0)
s02,s01	LOCKED_LONG	24.002	71.006	2.062	1032172	2.010	34.62	BASS_SWAP	
summary	pairs=2	LOCKED_LONG=1	LOCKED_SHORT=1	not_reproducible=0	invalid=0
summary	plan_median_ms=34.62	plan_p95_ms=34.62	median_seconds=12.4	median_cut_seconds=1.5
cause	LOCKED_LONG:NO_GRID	1
```

#### E. `./gradlew :automix:bench --args="segue /tmp/setflow-bench-stage01 measure" --console=plain` (Exit Code `0`)

```text
pair	level	seconds	peak	clipped	jump_db	click	gap_ms	residual_ms	measured_ms
s01,s02	LOCKED_SHORT	12.397	0.980	0	0.86	0.4	1.2	3.5	-3.7
s02,s01	LOCKED_LONG	24.002	0.980	0	2.88	0.4	0.6	4.0	2.1
summary	all	n=2	peak_p95=0.98	with_clipping=0	jump_median=0.86	jump_p95=0.86	click_p95=0.36	gap_p95_ms=0.6	residual_median_ms=3.5	measured_median_ms=2.1	measured_p95_ms=2.1
summary	LOCKED_LONG	n=1	peak_p95=0.98	with_clipping=0	jump_median=2.88	jump_p95=2.88	click_p95=0.38	gap_p95_ms=0.6	residual_median_ms=4.0	measured_median_ms=2.1	measured_p95_ms=2.1
summary	LOCKED_SHORT	n=1	peak_p95=0.98	with_clipping=0	jump_median=0.86	jump_p95=0.86	click_p95=0.36	gap_p95_ms=1.2	residual_median_ms=3.5	measured_median_ms=3.7	measured_p95_ms=3.7
```

#### F. `./gradlew :automix:bench --args="segue /tmp/setflow-bench-stage01 render s01,s02 s02,s01" --console=plain` (Exit Code `0`)

```text
s01,s02 LOCKED_SHORT 99906c9184df7b0e99eeb69a39b72234f25d765d94cea1a8516eff6c25f314a0 | LOCKED_LONG: NO_GRID ( of 0)
s02,s01 LOCKED_LONG 9c903098d2008a90e50c8599941dfaf4503986faf993ecbab4011099e771b29b | 
```
- Rendered `segue-s01-s02.wav` SHA-256: `1f00f1e806697bde5d095a0e05180a1f68c7309470663d572514990cb111ab75`
- Rendered `segue-s02-s01.wav` SHA-256: `0fa2327e5e2daa6711357802c906e61a129ea428c6236980ff4bbbb72aa00f51`

---

## 5. Physical Device / Emulator Playback Gate (Task 5)

- **Status:** `BLOCKED` (no Android device or emulator attached to `adb` inside the build container; `/usr/bin/adb devices -l` returned an empty device list).
- **Artifact Staging for Manual Device Testing:** The verified Mix Lab APK (`app-debug.apk`, `applicationId=app.myspoti.automixlab`, `versionCode=3115`) and synthetic DRM-free 155/160 BPM WAV fixtures (`s01.wav`, `s02.wav`, `segue-s01-s02.wav`, `segue-s02-s01.wav`, `ref_equal_power_s01_s02.wav`) are copied to `/sdcard/Download/SetFlow_Stage01_MixLab/` so they can be installed and auditioned on the Android host device without overwriting any stable Myspoti installation.

---

## 6. Upstream Compiler Warnings, Duplicate Tests & Baseline Observations (Task 6)

1. **Compiler Warnings (`230` unique warning locations across unmodified upstream):**
   - `:automix` (`main`): `3` warnings:
     - `base/automix/src/main/kotlin/app/myspoti/automix/Analyzer.kt:105:41`: Unnecessary non-null assertion (`!!`) on a non-null receiver of type `Grid`.
     - `base/automix/src/main/kotlin/app/myspoti/automix/Features.kt:153:63`: Redundant call of conversion method.
     - `base/automix/src/main/kotlin/app/myspoti/automix/MixPlanner.kt:267:34`: Unnecessary non-null assertion (`!!`) on a non-null receiver of type `MixPlanner.TempoMatch`.
   - `:automix` (`test`): `3` warnings:
     - `base/automix/src/test/kotlin/app/myspoti/automix/AutoMixTest.kt:65:24`: Unnecessary non-null assertion (`!!`) on a non-null receiver of type `Grid`.
     - `base/automix/src/test/kotlin/app/myspoti/automix/segue/SeguePolicyTest.kt:260:47`: Unnecessary non-null assertion (`!!`) on a non-null receiver of type `SeguePlan`.
     - `base/automix/src/test/kotlin/app/myspoti/automix/segue/SegueStretchTest.kt:95:39`: Unnecessary non-null assertion (`!!`) on a non-null receiver of type `SeguePlan`.
   - `:app` (`main` `128`, `test` `3`), `:host-android` (`main` `90`), `:extensions` (`test` `2`), `:api-spotify` (`main` `1`).
   - All `Segue*` production files in `base/automix/src/main/kotlin/app/myspoti/automix/segue/` compiled with `0` warnings.
2. **Duplicate Test Names / Structure Observations:**
   - Within `:automix:test` (`10` suites, `171` tests), there are `0` duplicate test method names.
   - Within `:app:testDebugUnitTest`, `EqualizerProcessorTest` and `SpatialProcessorTest` share 3 identical bypass-verification method names (`offIsAnExactCopy`, `floatOffIsAnExactCopy`, `turningOffGlidesThenCopies`).
3. **Architecture-Specific Unit Test Limitation (`linux-aarch64`):**
   - `com.music.spotui.ui.tv.TvScreenshotTest` (6 Roborazzi/Robolectric tests in `:app`) fails on `linux-aarch64` with `java.lang.AssertionError: The Robolectric native runtime is not supported on Linux (aarch64)`. This is in the out-of-scope Spotify TV UI module that will be removed in Stage 02.
4. **ONNX Runtime GPU Discovery Warning on Linux Container:**
   - On startup, `onnxruntime4j` logs `[W:onnxruntime:ort-java, device_discovery.cc:164 DiscoverDevicesForPlatform] GPU device discovery failed: Error: std::error_code with category name: generic, value: 13, message: Permission denied` due to PRoot `/sys`/`/dev` permissions, then cleanly falls back to CPU execution (`BeatThis` completes 100s track inference in ~6–8s on 2 threads).
5. **Dual Planner Coexistence in `:automix`:**
   - Upstream `:automix` contains both the legacy `MixPlanner` (`MixPlan`, `Render`) and the newer `SeguePolicy` (`SeguePlan`, `SegueValidator`, `SegueDeck`, `SegueStretch`). On `s01 -> s02` (`155 -> 160 BPM`), `SeguePolicy` rejected `LOCKED_LONG` (`NO_GRID` over the long window) and selected `LOCKED_SHORT` (`12.397 s`, `BASS_SWAP`), whereas `s02 -> s01` (`160 -> 155 BPM`) selected `LOCKED_LONG` (`24.002 s`, `BASS_SWAP`). Both produced `0` clipped samples (`peak = 0.980`) and sub-4ms beat alignment (`measured_ms = -3.7 ms` and `+2.1 ms`).
