# SetFlow Known Issues & Limitations

## Version 0.15.0 (Beta Trial Freeze)

### Supported Configurations
- **Operating System**: Android 10 (API 29) through Android 15 (API 36).
- **Architectures**: ARM64-v8a (primary target), ARMv7-a (legacy 32-bit), Universal.
- **Form Factors**: Mobile phones and tablets (portrait and landscape orientations).

### Audio Format Support & Boundaries
1. **Supported Local Decoders**:
   - WAV: 16-bit and 24-bit PCM (lossless rendering source & target).
   - FLAC: 16-bit and 24-bit stereo.
   - MP3: Constant and variable bitrates (up to 320 kbps).
   - AAC: M4A containers via standard Android MediaCodec decoders.
   - OGG: Vorbis audio.
2. **Unsupported Formats**:
   - DRM-protected streams (FairPlay, Widevine, PlayReady).
   - Multichannel audio (>2 channels; downmixed to stereo).
   - Proprietary DJ database files (Pioneer Rekordbox `.pdb`, Serato database) - planned for future import stages.

### Non-Features by Design (V1 Scope)
- **Zero Cloud / Streaming Sync**: By design under the Zero-Network policy, SetFlow does not communicate with Spotify, SoundCloud, Tidal, or remote servers.
- **Zero In-App Purchasing / Ads**: 100% free software under GNU GPLv3.
- **Zero Network Remote Control**: All mixing and planning is executed locally on-device.

### Known Edge Cases & Mitigations
1. **Variable-Bitrate (VBR) MP3 Seam Drift**:
   - *Behavior*: Some corrupt VBR MP3 headers report incorrect durations by a few milliseconds.
   - *Mitigation*: The analysis engine decodes audio frames to extract exact sample counts rather than relying on container header estimates.
2. **Extreme Tempo Stretching (>12%)**:
   - *Behavior*: Time-stretching tracks beyond +/-12% BPM can introduce audible phase artifacts.
   - *Mitigation*: The `MixPlanner` and transition validator flag transitions exceeding +/-8% with a warning badge (`TEMPO_DIFF_HIGH`), suggesting harmonic key shifts or cut-on-beat transitions instead.
3. **Low-Memory Devices during 20-Track Export**:
   - *Behavior*: Devices with <3GB RAM may trigger memory pressure if large uncompressed PCM chunks are buffered in memory.
   - *Mitigation*: The export engine streams audio in bounded 4096-sample chunks directly into the WAV output stream, keeping resident heap usage below 48MB throughout full-length sets.
