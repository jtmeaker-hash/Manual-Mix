# Software Bill of Materials (SBOM)

## Project Information
- **Product Name**: SetFlow
- **Version**: 1.0.0
- **License**: GNU General Public License v3.0 (GPL-3.0-only)
- **Primary Package**: `app.setflow`
- **Source Repository**: https://github.com/jtmeaker-hash/Manual-Mix

## Direct Runtime Dependencies

| Group / Artifact Coordinate | Version | SPDX License | Purpose |
| :--- | :--- | :--- | :--- |
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.23.2 | MIT | Beat This! ONNX beat tracking inference |
| `androidx.core:core-ktx` | 1.17.0 | Apache-2.0 | Android core Kotlin extensions |
| `androidx.appcompat:appcompat` | 1.7.1 | Apache-2.0 | Material compatibility layer |
| `com.google.android.material:material` | 1.14.0 | Apache-2.0 | Material Design design tokens |
| `androidx.compose.ui:ui` | BOM 2026.06.01 | Apache-2.0 | Modern declarative UI framework |
| `androidx.compose.ui:ui-graphics` | BOM 2026.06.01 | Apache-2.0 | Waveform rendering and canvas |
| `androidx.compose.material3:material3` | 1.5.0-beta02 | Apache-2.0 | Material 3 UI design components |
| `androidx.compose.foundation:foundation` | BOM 2026.06.01 | Apache-2.0 | Gestures, scrolling, and layouts |
| `androidx.activity:activity-compose` | 1.10.0 | Apache-2.0 | Activity Compose integration |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.0 | Apache-2.0 | State flow collection in Compose |
| `androidx.navigation:navigation-compose` | 2.9.0 | Apache-2.0 | In-app screen navigation |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.0 | Apache-2.0 | ViewModel state holders |
| `androidx.compose.material:material-icons-extended` | 1.7.8 | Apache-2.0 | DJ transport & editing iconography |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Apache-2.0 | Asynchronous concurrency |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Apache-2.0 | Segue JSON serialization |
| `androidx.room:room-common` | 2.7.0 | Apache-2.0 | SQLite Room database persistence |
| `androidx.media3:media3-exoplayer` | 1.8.0 | Apache-2.0 | Local two-deck audio decoding |
| `androidx.media3:media3-datasource` | 1.8.0 | Apache-2.0 | Local SAF URI streaming datasource |
| `androidx.media3:media3-session` | 1.8.0 | Apache-2.0 | MediaSessionService background player |

## Bundled Assets & Native Binaries

| Asset / Library | Path / Package | SPDX License | Attribution |
| :--- | :--- | :--- | :--- |
| `beat-this-small0-int8.onnx` | `base/app/src/main/assets/automix/` | MIT | Francesco Foscarin, Jan Schlüter, Gerhard Widmer (CPJKU Linz) |
| `libonnxruntime.so` | `lib/arm64-v8a/`, `lib/armeabi-v7a/` | MIT | Microsoft Corporation |
| `libonnxruntime4j_jni.so` | `lib/arm64-v8a/`, `lib/armeabi-v7a/` | MIT | Microsoft Corporation |
| `libandroidx.graphics.path.so` | `lib/arm64-v8a/`, `lib/armeabi-v7a/` | Apache-2.0 | The Android Open Source Project |
| `inter.ttf` | `base/app/src/main/res/font/` | SIL OFL 1.1 | Rasmus Andersson & The Inter Project Authors |

## Test-Only Dependencies

| Group / Artifact Coordinate | Version | SPDX License | Purpose |
| :--- | :--- | :--- | :--- |
| `junit:junit` | 4.13.2 | EPL-1.0 | Unit test execution |
| `org.json:json` | 20240303 | JSON-License | Android unit test JSON assertions |
| `org.xerial:sqlite-jdbc` | 3.41.2.2 | Apache-2.0 | Unit test in-memory SQLite runner |
| `com.microsoft.onnxruntime:onnxruntime` | 1.23.2 | MIT | JVM test model inference |

## License Compatibility Review
All dependencies are distributed under permissive licenses (Apache-2.0, MIT, SIL Open Font License 1.1) or reciprocal licenses compatible with GNU General Public License v3.0. No proprietary or GPL-incompatible code is bundled.
