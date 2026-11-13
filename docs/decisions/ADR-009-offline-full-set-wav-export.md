# ADR-009: Offline Full-Set Audio Export and Streaming WAV Renderer Parity

## Context
In Stage 11, SetFlow delivers offline rendering of complete prepared DJ sets into a continuous master audio file (`STAGES/11_OFFLINE_EXPORT.txt`).
Exporting a multi-hour DJ set presents critical architectural challenges:
1. **Bounded Memory Constraints (Task 1 & Task 9)**:
   - A 3-hour 44.1kHz 16-bit stereo set comprises ~952,560,000 bytes (~908 MiB of raw PCM; >1.8 GiB in float samples).
   - Android JVM heaps typically enforce 192–512 MiB limits. Decoding or buffering an entire set into memory causes an immediate `OutOfMemoryError`.
   - The engine must stream audio in small, bounded frame blocks (e.g., 4096 frames / ~92 ms) from source decoders through DSP into the output container.
2. **Snapshot Revision Isolation (Task 2)**:
   - If a user edits the set (reordering tracks, altering transition parameters, or modifying cue points) while an export job is rendering, the job must render strictly from the captured immutable snapshot. It must never interleave or mix revisions midway.
3. **WAV Container Standards & 4GiB RIFF Boundary (Task 3, Bug Trap 40)**:
   - Standard RIFF/WAV uses 32-bit unsigned chunk lengths, capping the maximum file size at $2^{32} - 1$ bytes (~4 GiB, equivalent to ~6.7 hours of 44.1kHz 16-bit stereo PCM).
   - The engine must compute planned duration in frames and bytes before rendering. Any set that would exceed the 4GiB limit must be capped with an explicit `A12_TIMELINE_OVERFLOW` error rather than generating a truncated or corrupted RIFF header.
4. **DSP & Transition Parity with Live Playback (Task 4 & Task 5)**:
   - The offline renderer must reuse the identical `SegueDeck` and `LaneCurve` automation curves from upstream `:automix` used in live playback (`DualDeckPcmEngine`), rather than an independent export approximation.
   - During solo track segments, audio passes through at nominal gain. During transition overlaps, both outgoing and incoming decks run concurrently through their respective `SegueDeck` DSP instances and are summed with soft-knee headroom protection.
5. **Cancellability, Error Recovery, and Disk Safety (Task 6 & Task 7)**:
   - Long renders must be cancellable at any percentage (1%, 50%, 99%).
   - On cancellation or I/O failure (insufficient disk space, DocumentProvider crash), the engine must immediately close handles, attempt cleanup of the partial target file, log diagnostic codes (`A10_EXPORT_IO_FAILURE`, `A11_OPERATION_CANCELLED`), and leave the project database completely uncorrupted.
6. **Read-Back Validation (Task 8)**:
   - Before reporting 100% completion or marking the export job `COMPLETED`, the engine must independently read back and validate the generated WAV file: checking the RIFF header, format chunk, channel count, sample rate, data size, and verifying absence of NaN/Inf non-finite samples.

## Decision

1. **Streaming WAV Writer (`WavWriter`)**:
   - Implements a streaming 16-bit 44.1kHz stereo PCM WAV writer.
   - Writes a 44-byte canonical RIFF/WAVE header with placeholder chunk sizes, streams PCM samples block-by-block, and patches the final header byte counts on completion.
   - Enforces 4GiB file size capping: rejects jobs where `plannedDurationFrames * 4 + 44 > 0xFFFFFFFFL` with `AudioErrorCode.A12_TIMELINE_OVERFLOW`.
   - Clamps and converts float samples $[-1.0, 1.0]$ to 16-bit signed PCM with clipping protection.

2. **Offline Set Render Engine (`OfflineSetRenderEngine`)**:
   - Takes a `ProjectDetailSnapshot` and locks all occurrence ordering, track entities, and resolved transition plans.
   - Resolves the timeline via `SetTimelineResolver.resolve()`, producing exact frame-accurate start, overlap, and end coordinates.
   - Operates a chunked render loop:
     - Iterates through the timeline in 4096-frame blocks.
     - Tracks current playback cursor and determines whether the active block is solo track playback or a dual-deck transition overlap.
     - Employs `SegueDeck` on both decks during overlaps to execute filter sweeps, frequency cuts, and level automation identical to live playback.
     - Progress is computed monotonically: $\text{progress} = \frac{\text{renderedFrames}}{\text{totalFrames}} \times 0.99$.
     - Reaching 1.0 is strictly gated on successful WAV finalization and read-back validation.

3. **Database Integration (`ExportJobEntity`)**:
   - Persists state in `SetFlowProjectDatabase` using `ExportJobEntity`:
     - States: `QUEUED` $\to$ `RUNNING` $\to$ `COMPLETED` / `FAILED` / `CANCELLED`.
     - Records planned frame count, actual completion time, failure code if aborted, and SHA-256 fingerprint of the resulting audio file.

4. **Independent Read-Back Validation (`WavReaderValidator`)**:
   - Opens the written output file independently via `RandomAccessFile` / `InputStream`.
   - Validates RIFF magic, WAVE format, fmt subchunk (PCM format 1, 2 channels, 44100 Hz, 16 bits per sample, 176400 bytes/sec), data subchunk length, and audio frame count.

## Consequences

- **Guaranteed Bounded Memory**: Multi-hour sets render with constant $O(1)$ RAM usage, ensuring zero risk of `OutOfMemoryError` on constrained Android devices.
- **Accurate Duration & No Packet Gaps**: Frame-accurate global timeline calculation eliminates splicing seams, encoder padding errors, or compressed packet boundary clicks.
- **True DSP Parity**: Live playback and offline export execute the identical pure-Kotlin `SegueDeck` and `LaneCurve` automation curves.
- **Fail-Safe Operation**: Cancelled or failed jobs cleanly clean up temporary files without database corruption or false success indications.
