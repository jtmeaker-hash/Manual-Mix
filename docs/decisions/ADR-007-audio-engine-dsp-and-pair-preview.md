# ADR-007: Audible EQ/FX Transitions, Headroom Limiter, and Transition Pair Preview

## Context
In Stage 09, SetFlow delivers audible, measurable, and safe EQ/FX transition execution and pair preview (`STAGES/09_DSP_PREVIEW.txt`).
Previous stages established the project database, beatgrid analysis, two-deck playback scheduling, and the transition editor UI with draft separation.

Key requirements and audio challenges:
1. **Audible and Measurable DSP Transitions (Task 1 & Task 2)**:
   - Every enabled transition preset (`BASS_SWAP`, `THREE_BAND`, `CUT_ON_BEAT`, `FILTER_OPEN`, `RISE_OUT`, `EVEN_FADE`, `SWEEP_OUT`) must change sound deterministically via pure Kotlin DSP (`SegueDeck`) and Media3 hardware pipeline (`SegueProcessor` + `SegueSink`).
   - Automation curves control 3-band EQ (`SHELF_BOTTOM`, `BAND_CORE`, `SHELF_TOP`), filter sweep (`SWEEP_HZ`, `SWEEP_PEAK`), filter rise (`RISE_HZ`), and level fades (`LEVEL`).
   - Presets not yet verified with zero-leak ring/tail cleanup (`ECHO_TAIL`, `ECHO_OUT`, `WASH_OUT`) must remain explicitly disabled in the UI.
2. **Headroom Management & Correlated Peaks (Task 6, F06)**:
   - Equal-power curves $\cos(\frac{\pi}{2}t)$ and $\sin(\frac{\pi}{2}t)$ maintain constant perceived energy for uncorrelated signals, but when two heavily mastered hardstyle tracks (150–170 BPM) with in-phase kick transients sum at a transition, the peak level can reach up to $+3\,\text{dBFS}$, resulting in harsh digital clipping.
   - The engine must implement a measured soft-knee limiter (`SOFT_KNEE_THRESHOLD = 0.92f`, ceiling $\pm 0.995\text{f}$) without audible pumping or crushing dynamic range.
3. **Single Audio Graph Owner & Preview Arbitration (Task 3, S04)**:
   - Only one controller/session may output audio at any moment.
   - If set playback (`AudioGraphOwner.SET_PLAYBACK`) is playing when the user opens the transition editor and taps [Preview Unsaved], set playback is suspended and its state/position checkpointed (`SuspendedSetSession`).
   - Exclusive ownership transfers to `AudioGraphOwner.TRANSITION_PREVIEW`. Exiting preview or leaving the screen halts preview immediately and restores the suspended set session.
4. **Draft Plan Isolation vs Committed Playback (Task 3 & Task 5, F05)**:
   - Preview must render the immutable unsaved draft `SeguePlan` with the draft parameters (`TransitionOverrideParams`).
   - Set playback in the background or upon resumption must continue using the committed database revision and resolved plan hash until the user explicitly saves.
5. **No File Boundary Clicks, Decoder Warm-up & Click-Free Seeking (Task 4 & Task 5)**:
   - Preview windows starting 5–15 seconds before the cue point must avoid mid-track decoder seek transients and packet boundaries.
   - Outgoing Deck A starts 2–15 seconds before the exit anchor; incoming Deck B is prebuffered with `volume = 0.0f` before cue and warmed up.
6. **Zero-Leak Effect Cleanup & Re-entry Safety (Task 7, F08)**:
   - Reusing an effect from track A $\to$ track B and track B $\to$ track C must reset all internal filter states (`ic1, ic2, s1, s2, delay line`).
   - Stop, Skip, Back, or Cancel must immediately disarm any active `SegueDeck` or `SegueProcessor` session.
7. **Neutral EQ True Bypass (Task 6)**:
   - When EQ curves are at $0\,\text{dB}$ and filter cutoff is open, the DSP engine must bypass filtering and output bit-for-bit dry audio without phase coloration or roundoff noise.

## Decision

1. **Preset Mapping & Lane Target Architecture (`SegueCompiler` + `SegueDeck`)**:
   - UI preset selections map strictly to `SegueShape` enum values:
     - `BASS_SWAP`: Bass crossover on downbeat knot via low shelf cut/boost (`SHELF_BOTTOM`), smooth equal-power level crossfade.
     - `THREE_BAND`: Low shelf (`SHELF_BOTTOM`), mid bell (`BAND_CORE`), and high shelf (`SHELF_TOP`) sequential handover.
     - `CUT_ON_BEAT`: Downbeat hard cut with pop-safe 5ms anti-click ramp.
     - `FILTER_OPEN`: Incoming track low-pass sweeps open (`SWEEP_HZ`).
     - `RISE_OUT`: Outgoing track high-pass sweeps up (`RISE_HZ`).
     - `EVEN_FADE`: Equal-power level crossfade with smooth gain curves.
     - `SWEEP_OUT`: Resonant low-pass filter sweep with Q peak automation (`SWEEP_HZ`, `SWEEP_PEAK`).
   - Presets with delay/reverb sends (`ECHO_TAIL`, `ECHO_OUT`, `WASH_OUT`) are marked `isSupported = false` until tail cleanup and bounded ring dissipation are verified in subsequent stages.

2. **Soft-Knee Headroom Limiter**:
   - `DualDeckPcmEngine.applySoftKneeHeadroomLimiter(sample)` operates with a linear knee threshold of $0.92\,\text{f}$ ($\approx -0.72\,\text{dBFS}$) and headroom margin of $0.075\,\text{f}$.
   - Samples exceeding $0.92\,\text{f}$ are compressed smoothly using a hyperbolic tangent function:
     $$\text{compressed} = 0.92 + 0.075 \cdot \tanh\left(\frac{|x| - 0.92}{0.075}\right)$$
     with a hard clamp at $\pm 0.995\,\text{f}$ ($-0.043\,\text{dBFS}$).
   - This eliminates digital clipping on summing in-phase kicks while leaving uncompressed audio ($|x| \le 0.92$) completely bit-transparent.

3. **Audio Owner Arbitration & Preview Lifecycle (`TransitionPreviewAudioBridge`)**:
   - A dedicated `TransitionPreviewAudioBridge` manages preview audio sessions.
   - When preview begins:
     - Queries `TwoDeckPlaybackController`. If `activeOwner == SET_PLAYBACK`, captures `SuspendedSetSession` and suspends playback.
     - Acquires `AudioGraphOwner.TRANSITION_PREVIEW`.
     - Synthesizes or decodes the draft transition window using the draft `SeguePlan`.
     - Feeds audio to the hardware sink via Media3 bridge or PCM buffer playback.
   - When preview ends (Stop button, Back, Cancel, Save, or Activity exit):
     - Disarms DSP stages immediately (`disarm()`).
     - Releases preview audio resources.
     - Restores `SuspendedSetSession` in paused state, ready for user resume.

4. **Neutral EQ True Bypass**:
   - In `SegueDeck.run()`, if `gain == 1.0`, all EQ band gains (`bassDb`, `coreDb`, `topDb`) are $0.0\,\text{dB}$, filter sweep and rise are inactive (`mix == 0.0`, `riseNow == 0.0`), and no sends are used, the engine outputs `x[k] = dry.toFloat()`.
   - Verified by unit tests to yield zero difference against the original input.

5. **State Isolation and Zero-Leak Cleanup**:
   - Every transition session creates fresh `SegueDeck` instances or calls `disarm()` on `SegueProcessor`.
   - Biquad internal filter memory (`ic1, ic2`), state-variable filter integrators (`s1, s2`), and delay buffers are reset on disarm/release so subsequent transitions cannot inherit ringing or residual energy.

## Consequences
- Guaranteed audible and measurable EQ and filter transitions across all enabled presets.
- 0% risk of clipped audio over 0 dBFS during hardstyle kicks summing at transition midpoints.
- Full draft separation: playing sets never use uncommitted parameters, and preview never modifies persisted set data.
- Strict single audio ownership eliminates simultaneous playback collisions and audio focus fights.
- Transparent neutral bypass ensures pure playback when no EQ or FX are active.
