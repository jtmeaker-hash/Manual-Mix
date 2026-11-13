# ADR-008: Whole-Set Playback Architecture, Authoritative Global Timeline, and Background MediaSession Service

## Context
In Stage 10, SetFlow delivers continuous, ordered whole-set playback with saved transitions, background execution via Android MediaSessionService, global timeline navigation, audio focus arbitration, and resilient resume capabilities (`STAGES/10_SET_PLAYBACK_SERVICE.txt`).

Key engineering requirements and challenges:
1. **Authoritative Set Timeline Resolver (Task 1)**:
   - A set is not a disconnected playlist; transitions overlap neighboring tracks in time.
   - Total mix duration is not simply the sum of track durations, but:
     $$T_{\text{mix}} = \sum_{i=1}^N \text{duration}(T_i) - \sum_{j=1}^{N-1} \text{span}(E_j)$$
     taking into account cue anchor points ($\text{exitAnchorUs}$, $\text{entryAnchorUs}$) and rate stretching.
   - Must detect and reject illegal configurations such as triple overlaps ($E_{k+1}$ cue occurs before $E_k$ transition completes) or cue points beyond track duration.
2. **Set Player Queue & State Machine (Task 2)**:
   - Manages automatic advancement through all edges in a set:
     $$\text{IDLE} \to \text{PLAYING\_TRACK} \to \text{ARMING\_TRANSITION} \to \text{OVERLAP\_RUNNING} \to \text{HANDOFF} \to \text{NEXT}$$
   - Transition bindings are locked to exact immutable plan revisions. Edits made during set playback apply on the next safe boundary, never mutating an armed in-flight transition mid-blend (F05).
3. **Android MediaSessionService & Foreground Execution (Task 3)**:
   - Audio engine instances (`TwoDeckPlaybackController`, `AndroidMedia3DualDeckBridge`) must reside in an Android `MediaSessionService` (`SetPlaybackService`), not in Compose Activities.
   - Manifest configures `android:foregroundServiceType="mediaPlayback"` with `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS` permissions.
   - Exposes standard Media3 `MediaSession` supporting lock screen controls, notification actions (Play, Pause, Skip Next, Skip Previous), and hardware Bluetooth/headset media keys.
4. **Audio Focus & Route Changes (Task 4, ACC-18, ACC-19)**:
   - Handles `AUDIOFOCUS_GAIN`, `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT`, and `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` (-12 dB ducking).
   - Listens for `AudioManager.ACTION_AUDIO_BECOMING_NOISY` on headphone unplug / Bluetooth disconnect, cleanly pausing playback without audio glitches.
5. **Global Timeline Seeking (Task 6)**:
   - Allows scrubbing across the entire multi-track set.
   - Maps global microsecond timestamp $t_{\text{global}}$ to the active occurrence index and track-local position.
   - If seeking lands within an active blend, cleanly disarms secondary deck and restarts from a safe point, avoiding stale automation firing.
6. **Bounded Concurrency & Memory (Task 7, F11)**:
   - Retains decoders and prebuffered audio only for the active deck and immediately adjacent incoming deck.
   - Unloads completed tracks immediately upon handoff completion.
7. **Safe Resume Metadata & Process Recovery (Task 5, F13)**:
   - Persists playback checkpoint (`projectId`, `projectRevision`, `occurrenceId`, `positionUs`) upon pause or state changes.
   - Process recreation restores the checkpoint cleanly in paused state, validating project revision before resuming.

## Decision

1. **Set Timeline Model (`SetTimelineResolver`)**:
   - Computes an immutable `SetTimeline` from `ProjectDetailSnapshot`.
   - Each occurrence is mapped to a `TimelineOccurrenceSlot(occurrenceId, trackId, startGlobalUs, endGlobalUs, exitAnchorGlobalUs)`.
   - Validates that every transition edge satisfies:
     $$\text{exitAnchorUs} + \text{spanUs} \le \text{durationUs}(A)$$
     $$\text{entryAnchorUs} + \text{spanUs} \le \text{durationUs}(B)$$
     and verifies no triple overlap exists ($E_{k}.\text{endGlobalUs} \le E_{k+1}.\text{startGlobalUs}$).
   - Provides $O(\log N)$ binary search to resolve $(t_{\text{global}}) \to (\text{occurrenceId}, \text{localPosUs})$.

2. **Continuous Set Playback Controller (`SetPlaybackQueueController`)**:
   - Manages queue progression across occurrences:
     - Arms incoming Deck B 5–15 seconds before the scheduled cue point.
     - Advances through `TwoDeckPlaybackController` on overlap step.
     - Upon handoff completion, advances active index $k \to k + 1$, disarming completed Deck A and preparing for edge $k+1$.
     - When last track finishes, transitions to `COMPLETE` and resets to `IDLE`.
   - Idempotent Skip: advances to next occurrence immediately, promoting incoming Deck B with pop-safe ramp.

3. **Android Background Service Architecture (`SetPlaybackService`)**:
   - Subclasses `androidx.media3.session.MediaSessionService`.
   - Hosts `TwoDeckPlaybackController` and `MediaSession` using primary Deck player.
   - Posts persistent notification with `MediaStyle`, track title, artist, album art thumbnail, and playback actions.
   - Supports Android 10 through Android 15 with explicit `mediaPlayback` foreground type.

4. **Audio Focus & Noisy Route Listener**:
   - Registers `AudioFocusRequestCompat` with `AUDIOFOCUS_GAIN`.
   - On transient loss with duck: applies `masterDuckGain = 0.25f`.
   - On permanent loss or becoming noisy: pauses playback, increments generation, and disarms secondary deck.

5. **Safe Checkpoint Persistence**:
   - Stores current playback checkpoint in SQLite or atomic shared metadata.
   - Restores state only if project exists and edit revision matches; otherwise resets to track start.

## Consequences
- Full set plays automatically from start to finish with all user-saved transitions without manual intervention.
- Screen can turn off or user can switch apps while playback continues flawlessly in background.
- Zero audio leaks, zero memory growth over 30+ minute listening sessions.
- Strict single audio ownership maintained across whole set and transition preview.
