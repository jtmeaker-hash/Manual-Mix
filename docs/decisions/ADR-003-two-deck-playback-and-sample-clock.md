# ADR-003: Dual-Deck Playback State Machine, Sample-Clock Scheduling, and Audio Ownership

- **Status:** Accepted
- **Date:** 2026-11-12
- **Stage:** 05 (`STAGES/05_TWO_DECK_PLAYBACK.txt`)

## Context

Stage 05 requires a deterministic two-deck playback and crossfade engine that:
1. Preserves upstream Myspoti's proven `SegueProcessor`, `SegueSink`, `MixPreparation`, `CrossfadeRamp`, and `SegueDeck` components (`com.music.spotui.automix.*`, `com.music.spotui.di.CrossfadeRamp`, `app.myspoti.automix.segue.*`).
2. Enforces the state machine `IDLE -> PREPARING -> ARMED -> RUNNING -> HANDOFF -> COMPLETE` (plus `ERROR`, `SAFE_FALLBACK`, and `STOPPED`) with a monotonically increasing `generation: Long` token so stale callbacks after `Next`, `Seek`, `Stop`, `Pause`, or project reorder (`S05`, `S06`) are deterministically ignored.
3. Decodes Source A and Source B independently (never splicing compressed packets or loading the full queue onto both decks simultaneously, preventing the upstream duplicate-audio hazard `F01`/`F02`), prebuffers Deck B with `volume = 0f` before the cue point, and executes equal-power (`cos(pi/2 * t)`, `sin(pi/2 * t)`) or `SegueDeck` crossfades with soft-knee peak limiting (`<= 0 dBFS`) and NaN/Inf protection (`A07_DSP_NONFINITE`).
4. Binds every armed transition to an immutable `ArmedTransitionBinding` (`projectId`, `projectRevision`, `edgeId`, `edgePlanRevision`, `outgoingOccurrenceId`, `incomingOccurrenceId`, `audioKeyA`, `audioKeyB`, `exitAnchorUs`, `entryAnchorUs`, `spanUs`, `ratePpmB`, `planHashSha256`). Any mismatch disarms automation and triggers `SAFE_FALLBACK`.
5. Enforces single-owner audio graph arbitration (`AudioGraphOwner.SET_PLAYBACK` vs `AudioGraphOwner.TRANSITION_PREVIEW`) and handles audio focus (`A08_AUDIO_FOCUS_LOST`) and output route changes (`A09_OUTPUT_ROUTE_CHANGED`).

## Decision

1. **Dual-Deck Engine Architecture (`app.setflow.playback`):**
   - `SyntheticAudioFixtureGenerator`: Deterministic seeded PCM/WAV generator (`SEED = 0x5E7F1040L`) producing synthetic test fixtures (`T01` 155 BPM 4/4 click + sub, `T03` 160 BPM hard kick + click, `T04` 170 BPM near-0 dBFS kick, corrupt/truncated streams) with exact known beat frame indices.
   - `DualDeckPcmEngine`: Sample-accurate dual-deck decoder and mixer driving two independent deck decoders (`DeckSlot.DECK_A` and `DeckSlot.DECK_B`) from `TrackSourceProvider` / `AudioContainerAndDecoder`. Deck B is prebuffered independently with `gain = 0.0f` prior to `exitAnchorFrame`. During `[exitAnchorFrame, exitAnchorFrame + fadeFrames)`, both decks are mixed sample-accurately using `CrossfadeRamp.outgoingGain(t)` / `CrossfadeRamp.incomingGain(t)` (or `SegueDeck` when a `SeguePlan` is attached) with non-finite sample scrubbing (`A07`) and soft-knee headroom protection (`F06`).
   - `AndroidMedia3DualDeckBridge`: Android OS Media3 bridge wrapping two `ExoPlayer` instances configured with upstream `SegueProcessor` + `SegueSink` (`setEnableAudioTrackPlaybackParams(true)`), `AudioAttributes` (`USAGE_MEDIA`, `AUDIO_CONTENT_TYPE_MUSIC`), and `setHandleAudioBecomingNoisy(true)` on the primary deck only (`volume = 0f` on the prebuffering incoming deck until cue).
   - `TwoDeckPlaybackController`: Service-owned state machine (`PlaybackSessionController`) managing `generation` tokens, `ArmedTransitionBinding` verification against `SetFlowProjectDatabase`, idempotent `playSet` / `playTwoTrackTransition` / `pause` / `resume` / `stopAll` / `skipToNext` / `skipAtFraction` / `seekToUs`, audio focus callbacks, route-change callbacks, and single-owner arbitration between `SET_PLAYBACK` and `TRANSITION_PREVIEW`.

2. **Hardware Verification on Physical `arm64-v8a` Device:**
   - All PCM overlap, click-alignment (`155 BPM -> 160 BPM`), 4s/8s equal-power crossfade, skip (`10%`, `50%`, `90%`), late-B fallback, decoder-failure fallback, route-change, and 20-minute equivalent A/B soak tests run directly on the host `motorola edge 50 pro` (`arm64-v8a`, Android 16 / API 36, 8-core Cortex-A715/A510) with measured frame-level metrics recorded in test logs.
