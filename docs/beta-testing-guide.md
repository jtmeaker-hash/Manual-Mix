# SetFlow External Beta Testing Guide

Welcome to the **SetFlow v0.15.0 Beta Trial**! Thank you for helping test SetFlow in real-world mobile DJ environments.

SetFlow is an open-source, 100% offline Android DJ Set Builder designed for mobile DJ performance, transition editing, and mix preparation.

---

## Testing Principles & Privacy Guarantee

- **Zero Network Transmission**: SetFlow does not request or possess the `INTERNET` permission. The app cannot and will not send any data, audio, or telemetry over the internet.
- **Your Audio Stays on Your Device**: SetFlow never alters or copies your original audio files. All edits and transition plans are stored in an internal SQLite database.
- **Opt-In Diagnostics**: If you encounter an issue, diagnostics can only be shared by you manually. The internal diagnostic log automatically scrubs all file names, file paths, and private URIs.

---

## Installation & Setup

1. **System Requirements**: Android 10 (API 29) or later; ARM64-v8a (64-bit) or ARMv7-a (32-bit).
2. **Download APK**:
   - Modern phones / tablets (64-bit): `SetFlow-Beta-0.15.0-beta.1-ARMv8.apk`
   - Older or 32-bit devices: `SetFlow-Beta-0.15.0-beta.1-ARMv7.apk`
   - Universal: `SetFlow-Beta-0.15.0-beta.1-universal.apk`
3. **Install**: Open the downloaded APK and grant one-time permission to install apps from your browser or file manager.

---

## 8-Step Beta Verification Script

Please execute the following 8-step workflow on your device and record your observations:

### Step 1: Import Local Audio Files
- Tap **Add Tracks** or **Import**.
- Select 5 or more local audio tracks via the Android document picker (WAV, FLAC, MP3, AAC, or OGG).
- *Verify*: Track titles, artist tags, and durations appear immediately in the library without modifying your files.

### Step 2: Build a DJ Set
- Create a new set (e.g., "Beta Test Set").
- Add the imported tracks to the set.
- Reorder tracks by dragging the reorder handles.
- *Verify*: Automated tempo (BPM) detection and initial transition recommendations populate cleanly.

### Step 3: Edit Two Independent Transitions
- Tap on the transition between **Track 1 and Track 2**:
  * Change the transition style to **Bass Swap** (`BASS_SWAP`).
  * Use the stepper buttons (`-1 Bar`, `-1 Beat`, `+1 Beat`, `+1 Bar`) to fine-tune the transition entry/exit points.
  * Tap **Preview Transition** to audition the 3-band EQ and volume crossfade.
  * Tap **Save Override**.
- Tap on the transition between **Track 3 and Track 4**:
  * Change the style to **Filter Open** (`FILTER_OPEN`) or **Cut on Beat** (`CUT_ON_BEAT`).
  * Make a draft change and tap **Undo**, then **Redo** to verify in-memory draft editing.
  * Tap **Reset to Auto**; verify the confirmation dialog appears before resetting.
  * Re-apply your custom settings and tap **Save Override**.
- *Verify*: The two transitions retain completely independent settings without crosstalk.

### Step 4: Process Kill & Persistence Check
- Swipe away SetFlow from Android Recent Apps, or force-stop via ADB:
  ```bash
  adb shell am force-stop app.setflow.dev
  ```
- Re-open SetFlow.
- *Verify*: Your set, track order, and custom transition overrides remain exactly as saved.

### Step 5: Continuous Background Playback
- Open your test set and tap **Play All**.
- Lock your phone screen.
- *Verify*:
  * Playback continues smoothly across track transitions without gaps or stuttering.
  * Lock screen and notification media controls allow pausing, resuming, and skipping.
  * Receiving a phone call or notification ducks or pauses audio according to system audio focus policy.

### Step 6: Master Lossless WAV Export
- In the set detail screen, tap **Export Set**.
- Select **16-bit 44.1kHz Stereo WAV** (or 24-bit).
- Pick a destination folder in your device's Downloads or Music directory.
- Observe the real-time progress bar.
- *Verify*:
  * Export completes and reports success.
  * The exported `.wav` file plays cleanly in VLC or any standard media player with exact transition curves.

### Step 7: Project Backup Export & Re-Import
- Open **Settings** -> **Backup & Export Project**.
- Save the JSON project backup file.
- Delete the set from your library.
- Tap **Import Project** and select the JSON backup file.
- *Verify*: The set is completely restored with all transition cue points and overrides intact.

### Step 8: Missing File Recovery
- Using a file manager, temporarily rename or move one of the audio files used in your set.
- Open SetFlow and view the set.
- *Verify*:
  * An `A01 Source Missing` warning badge is displayed on the affected track.
  * The project and transition plan are NOT deleted or corrupted.
  * Tapping **Relink File** allows reselecting the file and immediately restores green health.

---

## Bug Reporting Template

If you encounter any bug, glitch, or unexpected behavior, please submit a report with the following information:

```markdown
### Bug Report
- **Device Model**: (e.g. Motorola Edge 50 Pro, Google Pixel 8, Samsung Galaxy S23)
- **Android Version**: (e.g. Android 14 / API 34, Android 16 / API 36)
- **Audio Formats**: (e.g. 16-bit 44.1kHz FLAC, 320kbps MP3)
- **Step in Test Script**: (Step 1 through 8)
- **Reproduction Steps**:
  1. ...
  2. ...
- **Expected Behavior**: ...
- **Observed Behavior**: ...
- **Visible Error Code**: (e.g. A01, A06, or None)
- **Diagnostic Snapshot**: (Optional: Copy text from Settings -> Diagnostic Buffer)
```
