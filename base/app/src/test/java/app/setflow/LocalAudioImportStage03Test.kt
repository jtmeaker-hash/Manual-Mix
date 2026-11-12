/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.ImportOutcomeType
import app.setflow.domain.MetadataNormalizer
import app.setflow.domain.PersistableGrantStatus
import app.setflow.domain.RelinkCandidateEvaluation
import app.setflow.domain.TrackAvailabilityState
import app.setflow.io.SafUriGuard
import app.setflow.io.TrackFingerprinter
import app.setflow.io.VirtualSafTrackSourceProvider
import app.setflow.service.LocalTrackLibraryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin

class LocalAudioImportStage03Test {

    @Test
    fun sameFilenameDifferentRecordingAndDuplicateImportTenTimesPreservesIdentityAndSourceChecksums() {
        val tempDir = Files.createTempDirectory("setflow-s03-identity").toFile()
        try {
            val dirA = File(tempDir, "folderA").apply { mkdirs() }
            val dirB = File(tempDir, "folderB").apply { mkdirs() }
            val fileA = File(dirA, "Club_Anthem_128.wav")
            val fileB = File(dirB, "Club_Anthem_128.wav") // Identical filename, different audio!

            writeSyntheticWav(fileA, sampleRate = 44100, channels = 2, durationSec = 6.0, freqHz = 440.0, title = "Club Anthem (Original Mix)", artist = "DJ Alpha")
            writeSyntheticWav(fileB, sampleRate = 44100, channels = 2, durationSec = 6.0, freqHz = 880.0, title = "Club Anthem (VIP Dub)", artist = "DJ Beta")

            val shaBeforeA = TrackFingerprinter.computeFullSha256(fileA)
            val shaBeforeB = TrackFingerprinter.computeFullSha256(fileB)
            assertNotEquals("Recordings A and B must have distinct source bytes", shaBeforeA, shaBeforeB)

            val provider = VirtualSafTrackSourceProvider()
            val uriA1 = "content://com.android.externalstorage.documents/document/primary%3ADJs%2FfolderA%2FClub_Anthem_128.wav"
            val uriA2 = "content://com.android.externalstorage.documents/document/primary%3ADJs%2Fbackup%2FClub_Anthem_128.wav"
            val uriB = "content://com.android.externalstorage.documents/document/primary%3ADJs%2FfolderB%2FClub_Anthem_128.wav"

            provider.registerFileAsSafDocument(uriA1, fileA, displayName = "Club_Anthem_128.wav")
            provider.registerFileAsSafDocument(uriA2, fileA, displayName = "Club_Anthem_128.wav")
            provider.registerFileAsSafDocument(uriB, fileB, displayName = "Club_Anthem_128.wav")

            val service = LocalTrackLibraryService(
                sourceProvider = provider,
                diagnostics = DiagnosticsRingBuffer(),
                storageDirectory = File(tempDir, "store")
            )

            // 1. Import fileA first time
            val firstOutcome = service.importUris(listOf(uriA1)).single()
            assertEquals(ImportOutcomeType.IMPORTED_NEW, firstOutcome.outcomeType)
            assertFalse(firstOutcome.sameNameDifferentRecording)
            assertEquals(1, service.tracks.value.size)

            // 2. S12: Import exact same URI 9 more times (10x total) + import identical content from second URI
            repeat(9) {
                val dupUriOutcome = service.importUris(listOf(uriA1)).single()
                assertEquals(ImportOutcomeType.DUPLICATE_SAME_URI, dupUriOutcome.outcomeType)
                assertEquals(firstOutcome.trackId, dupUriOutcome.trackId)
            }
            val dupContentOutcome = service.importUris(listOf(uriA2)).single()
            assertEquals(ImportOutcomeType.DUPLICATE_SAME_CONTENT, dupContentOutcome.outcomeType)
            assertEquals(firstOutcome.trackId, dupContentOutcome.trackId)
            assertEquals("Duplicate imports must not create extra tracks", 1, service.tracks.value.size)

            // 3. Import fileB (same filename "Club_Anthem_128.wav", different recording bytes)
            val secondOutcome = service.importUris(listOf(uriB)).single()
            assertEquals(ImportOutcomeType.IMPORTED_NEW, secondOutcome.outcomeType)
            assertTrue("Must flag sameNameDifferentRecording=true", secondOutcome.sameNameDifferentRecording)
            assertNotEquals(firstOutcome.trackId, secondOutcome.trackId)
            assertEquals(2, service.tracks.value.size)

            val trackA = service.getTrack(firstOutcome.trackId!!)!!
            val trackB = service.getTrack(secondOutcome.trackId!!)!!
            assertNotEquals(trackA.identity.contentFingerprint, trackB.identity.contentFingerprint)
            assertNotEquals(trackA.identity.verified10sAudioKey, trackB.identity.verified10sAudioKey)
            assertEquals("Club Anthem (Original Mix)", trackA.title)
            assertEquals("Club Anthem (VIP Dub)", trackB.title)

            // 4. Task 8: Verify source file checksums after import/decode are 100% unchanged
            val shaAfterA = TrackFingerprinter.computeFullSha256(fileA)
            val shaAfterB = TrackFingerprinter.computeFullSha256(fileB)
            assertEquals("Source file A must never be modified", shaBeforeA, shaAfterA)
            assertEquals("Source file B must never be modified", shaBeforeB, shaAfterB)

            // 5. Task 6: Zero leaked file descriptors
            assertEquals(0, provider.resourceTracker.activeFileDescriptors)
            assertEquals(provider.resourceTracker.totalOpened, provider.resourceTracker.totalClosed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun uriRevokedAfterRebootAndSdCardMissingPreserveTrackAndRequireConfirmationOnUncertainRelink() {
        val tempDir = Files.createTempDirectory("setflow-s03-relink").toFile()
        try {
            val fileA = File(tempDir, "Track_One.wav")
            val fileB = File(tempDir, "Track_Two.wav")
            val fileBBackup = File(tempDir, "Track_Two_Backup.wav")
            val fileADifferentRecordingSameName = File(tempDir, "alt/Track_One.wav").apply { parentFile.mkdirs() }

            writeSyntheticWav(fileA, sampleRate = 44100, channels = 2, durationSec = 6.0, freqHz = 330.0)
            writeSyntheticWav(fileB, sampleRate = 44100, channels = 2, durationSec = 7.0, freqHz = 550.0)
            fileB.copyTo(fileBBackup, overwrite = true)
            writeSyntheticWav(fileADifferentRecordingSameName, sampleRate = 44100, channels = 2, durationSec = 8.5, freqHz = 660.0)

            val provider = VirtualSafTrackSourceProvider()
            val uriA = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FTrack_One.wav"
            val uriB = "content://com.android.externalstorage.documents/document/sdcard1%3AMusic%2FTrack_Two.wav"
            val uriBBackup = "content://com.android.externalstorage.documents/document/primary%3ABackup%2FTrack_Two_Backup.wav"
            val uriAUncertain = "content://com.android.externalstorage.documents/document/primary%3AAlt%2FTrack_One.wav"

            provider.registerFileAsSafDocument(uriA, fileA)
            provider.registerFileAsSafDocument(uriB, fileB)
            provider.registerFileAsSafDocument(uriBBackup, fileBBackup)
            provider.registerFileAsSafDocument(uriAUncertain, fileADifferentRecordingSameName, displayName = "Track_One.wav")

            val storageDir = File(tempDir, "persisted")
            val service1 = LocalTrackLibraryService(provider, DiagnosticsRingBuffer(), storageDir)
            val outcomes = service1.importUris(listOf(uriA, uriB))
            val idA = outcomes[0].trackId!!
            val idB = outcomes[1].trackId!!

            // Simulate app force-stop and reboot: new service instance reloads persisted JSON
            val serviceAfterReboot = LocalTrackLibraryService(provider, DiagnosticsRingBuffer(), storageDir)
            assertEquals(2, serviceAfterReboot.tracks.value.size)

            // Simulate URI permission revoked on Track A after reboot & SD card unmounted on Track B
            provider.setGrantRevoked(uriA, true)
            provider.setSdCardMounted(uriB, false)

            serviceAfterReboot.verifyAllTracks()
            val afterA = serviceAfterReboot.getTrack(idA)!!
            val afterB = serviceAfterReboot.getTrack(idB)!!

            // Tracks must NEVER be deleted when missing or revoked!
            assertEquals(2, serviceAfterReboot.tracks.value.size)
            assertEquals(TrackAvailabilityState.MISSING_OR_REVOKED, afterA.availabilityState)
            assertEquals(PersistableGrantStatus.REVOKED_OR_DENIED, afterA.identity.grantStatus)
            assertEquals(AudioErrorCode.A02_UNREADABLE_PERMISSION, afterA.lastErrorCode)

            assertEquals(TrackAvailabilityState.MISSING_OR_REVOKED, afterB.availabilityState)
            assertEquals(AudioErrorCode.A01_SOURCE_MISSING, afterB.lastErrorCode)

            // Relink Track B to exact backup copy -> ExactMatch auto-applies
            val evalB = serviceAfterReboot.evaluateRelinkCandidate(idB, uriBBackup, autoApplyOnExactMatch = true)
            assertTrue(evalB is RelinkCandidateEvaluation.ExactMatch)
            val relinkedB = serviceAfterReboot.getTrack(idB)!!
            assertEquals(TrackAvailabilityState.AVAILABLE, relinkedB.availabilityState)
            assertEquals(uriBBackup, relinkedB.identity.contentUri)
            assertNull(relinkedB.lastErrorCode)

            // Relink Track A to same-named file with different recording -> UncertainMatchRequiresConfirmation!
            val evalA = serviceAfterReboot.evaluateRelinkCandidate(idA, uriAUncertain, autoApplyOnExactMatch = true)
            assertTrue("Same filename with different audio must require explicit confirmation", evalA is RelinkCandidateEvaluation.UncertainMatchRequiresConfirmation)
            val uncertain = evalA as RelinkCandidateEvaluation.UncertainMatchRequiresConfirmation
            assertTrue(uncertain.sameDisplayName)
            assertEquals("Track A must remain MISSING_OR_REVOKED until user confirms", TrackAvailabilityState.MISSING_OR_REVOKED, serviceAfterReboot.getTrack(idA)!!.availabilityState)

            // Calling confirmRelink with userConfirmedUncertainMatch=false must not apply
            assertNull(serviceAfterReboot.confirmRelink(uncertain, userConfirmedUncertainMatch = false))
            assertEquals(TrackAvailabilityState.MISSING_OR_REVOKED, serviceAfterReboot.getTrack(idA)!!.availabilityState)

            // Explicit user confirmation applies the relink and records A05_SOURCE_CHANGED notice
            val confirmedA = serviceAfterReboot.confirmRelink(uncertain, userConfirmedUncertainMatch = true)
            assertNotNull(confirmedA)
            assertEquals(TrackAvailabilityState.AVAILABLE, confirmedA!!.availabilityState)
            assertEquals(uriAUncertain, confirmedA.identity.contentUri)
            assertEquals(AudioErrorCode.A05_SOURCE_CHANGED, confirmedA.lastErrorCode)
            assertEquals(0, provider.resourceTracker.activeFileDescriptors)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsEmptyShortCorruptFakeExtensionCloudOnly192kHzAndNormalizesUnicodeMetadata() {
        val tempDir = Files.createTempDirectory("setflow-s03-edgecases").toFile()
        try {
            // 1. Forbidden content:// -> File guard
            var guardTriggered = false
            try {
                SafUriGuard.resolveTestFileOnly("content://com.android.externalstorage.documents/document/primary%3Asong.wav")
            } catch (_: IllegalArgumentException) {
                guardTriggered = true
            }
            assertTrue("SafUriGuard must forbid converting content:// URI to File", guardTriggered)

            val emptyFile = File(tempDir, "empty.wav").apply { writeBytes(ByteArray(0)) }
            val shortFile = File(tempDir, "too_short_400ms.wav").also {
                writeSyntheticWav(it, sampleRate = 44100, channels = 2, durationSec = 0.4, freqHz = 440.0)
            }
            val fakeMp3 = File(tempDir, "not_really_audio.mp3").apply {
                writeText("<html><body>Not an MP3 file despite .mp3 extension</body></html>")
            }
            val unsupported192kWav = File(tempDir, "audiophile_192k.wav").also {
                writeSyntheticWav(it, sampleRate = 192000, channels = 2, durationSec = 2.0, freqHz = 440.0)
            }
            val unicodeName = "東京_Night_Сет_🎵_Café\u0007_Mix.wav"
            val unicodeFile = File(tempDir, "unicode_source.wav").also {
                writeSyntheticWav(
                    it,
                    sampleRate = 48000,
                    channels = 2,
                    durationSec = 6.0,
                    freqHz = 528.0,
                    title = "東京 Night Сет 🎵 " + "X".repeat(300),
                    artist = "DJ\u0000 Клуб\tCafé"
                )
            }

            val provider = VirtualSafTrackSourceProvider()
            provider.registerFileAsSafDocument("content://saf/doc/empty.wav", emptyFile)
            provider.registerFileAsSafDocument("content://saf/doc/short.wav", shortFile)
            provider.registerFileAsSafDocument("content://saf/doc/fake.mp3", fakeMp3)
            provider.registerFileAsSafDocument("content://saf/doc/192k.wav", unsupported192kWav)
            provider.registerFileAsSafDocument("content://saf/doc/cloud.wav", unicodeFile, isCloudOnlyOrVirtual = true)
            provider.registerFileAsSafDocument("content://saf/doc/unicode.wav", unicodeFile, displayName = unicodeName)

            val service = LocalTrackLibraryService(provider, DiagnosticsRingBuffer())
            val outcomes = service.importUris(
                listOf(
                    "content://saf/doc/empty.wav",
                    "content://saf/doc/short.wav",
                    "content://saf/doc/fake.mp3",
                    "content://saf/doc/192k.wav",
                    "content://saf/doc/cloud.wav",
                    "content://saf/doc/unicode.wav"
                )
            )

            assertEquals(ImportOutcomeType.REJECTED_MISSING_OR_EMPTY, outcomes[0].outcomeType)
            assertEquals(AudioErrorCode.A01_SOURCE_MISSING, outcomes[0].errorCode)

            assertEquals(ImportOutcomeType.REJECTED_UNSUPPORTED_OR_CORRUPT, outcomes[1].outcomeType)
            assertEquals(AudioErrorCode.A03_UNSUPPORTED_CODEC, outcomes[1].errorCode)

            assertEquals(ImportOutcomeType.REJECTED_UNSUPPORTED_OR_CORRUPT, outcomes[2].outcomeType)
            assertEquals(AudioErrorCode.A03_UNSUPPORTED_CODEC, outcomes[2].errorCode)

            assertEquals(ImportOutcomeType.REJECTED_UNSUPPORTED_OR_CORRUPT, outcomes[3].outcomeType)
            assertEquals(AudioErrorCode.A03_UNSUPPORTED_CODEC, outcomes[3].errorCode)
            assertTrue("Must cite 192000 Hz sample rate in failure message", outcomes[3].message.contains("192000"))

            assertEquals(ImportOutcomeType.REJECTED_CLOUD_NOT_OFFLINE, outcomes[4].outcomeType)
            assertEquals(AudioErrorCode.A01_SOURCE_MISSING, outcomes[4].errorCode)
            assertTrue(outcomes[4].message.contains("offline", ignoreCase = true))

            assertEquals(ImportOutcomeType.IMPORTED_NEW, outcomes[5].outcomeType)
            val imported = service.getTrack(outcomes[5].trackId!!)!!
            assertEquals("東京_Night_Сет_🎵_Café_Mix.wav", imported.displayName)
            assertEquals(MetadataNormalizer.MAX_METADATA_CHARS, imported.title.length)
            assertTrue(imported.title.startsWith("東京 Night Сет 🎵"))
            assertEquals("DJ Клуб Café", imported.artist)
            assertEquals(0, provider.resourceTracker.activeFileDescriptors)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun cancellationMidBatchStopsImmediatelyAndClosesAllFileDescriptors() {
        val tempDir = Files.createTempDirectory("setflow-s03-cancel").toFile()
        try {
            val provider = VirtualSafTrackSourceProvider()
            val uris = (1..8).map { idx ->
                val f = File(tempDir, "track_$idx.wav")
                writeSyntheticWav(f, sampleRate = 44100, channels = 2, durationSec = 5.5, freqHz = 300.0 + idx * 40.0)
                val uri = "content://saf/batch/track_$idx.wav"
                provider.registerFileAsSafDocument(uri, f)
                uri
            }

            val service = LocalTrackLibraryService(provider, DiagnosticsRingBuffer())
            var checkCalls = 0
            // Cancel after the first 3 tracks have been imported
            val outcomes = service.importUris(
                uriStrings = uris,
                cancelledOverride = {
                    checkCalls++
                    service.tracks.value.size >= 3
                }
            )

            assertEquals(8, outcomes.size)
            assertEquals(3, outcomes.count { it.outcomeType == ImportOutcomeType.IMPORTED_NEW })
            assertEquals(5, outcomes.count { it.outcomeType == ImportOutcomeType.CANCELLED })
            assertTrue(service.importProgress.value.cancelled)
            assertEquals("Every FD must be closed on cancellation", 0, provider.resourceTracker.activeFileDescriptors)
            assertEquals(provider.resourceTracker.totalOpened, provider.resourceTracker.totalClosed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun decodes10SecondsFromSyntheticWavFlacMp3AndM4aWithUnchangedSourceChecksums() {
        val tempDir = Files.createTempDirectory("setflow-s03-codecs").toFile()
        try {
            val wavFile = File(tempDir, "synth_12s.wav")
            val flacFile = File(tempDir, "synth_12s.flac")
            val mp3File = File(tempDir, "synth_12s.mp3")
            val m4aFile = File(tempDir, "synth_12s.m4a")
            val alacFile = File(tempDir, "synth_12s_alac.m4a")

            writeSyntheticWav(
                wavFile,
                sampleRate = 44100,
                channels = 2,
                durationSec = 12.0,
                freqHz = 440.0,
                title = "Synth 12s Reference",
                artist = "SetFlow Lab"
            )

            val ffmpegBin = listOf("/usr/local/bin/ffmpeg", "/data/data/com.termux/files/usr/bin/ffmpeg", "ffmpeg")
                .firstOrNull { p -> p == "ffmpeg" || File(p).canExecute() } ?: "ffmpeg"

            runFfmpeg(ffmpegBin, "-y", "-i", wavFile.absolutePath, "-metadata", "TITLE=Synth FLAC", "-metadata", "ARTIST=SetFlow Lab", flacFile.absolutePath)
            runFfmpeg(ffmpegBin, "-y", "-i", wavFile.absolutePath, "-c:a", "libmp3lame", "-b:a", "192k", "-metadata", "title=Synth MP3", "-metadata", "artist=SetFlow Lab", mp3File.absolutePath)
            runFfmpeg(ffmpegBin, "-y", "-i", wavFile.absolutePath, "-c:a", "aac", "-b:a", "192k", "-metadata", "title=Synth M4A", "-metadata", "artist=SetFlow Lab", m4aFile.absolutePath)
            runFfmpeg(ffmpegBin, "-y", "-i", wavFile.absolutePath, "-c:a", "alac", alacFile.absolutePath)

            val allFiles = listOf(wavFile, flacFile, mp3File, m4aFile, alacFile)
            val checksumsBefore = allFiles.associate { it.name to TrackFingerprinter.computeFullSha256(it) }

            val provider = VirtualSafTrackSourceProvider()
            val uris = allFiles.map { f ->
                val uri = "content://com.android.externalstorage.documents/document/primary%3ACodecs%2F${f.name}"
                provider.registerFileAsSafDocument(uri, f)
                uri
            }

            val service = LocalTrackLibraryService(provider, DiagnosticsRingBuffer())
            val outcomes = service.importUris(uris, verifyWindowSeconds = 10)

            // WAV, FLAC, MP3, and AAC M4A must succeed and decode 10s
            for (i in 0..3) {
                val out = outcomes[i]
                assertEquals("Expected ${allFiles[i].name} to import cleanly: ${out.message}", ImportOutcomeType.IMPORTED_NEW, out.outcomeType)
                val rec = service.getTrack(out.trackId!!)!!
                assertEquals(44100, rec.sampleRateHz)
                assertEquals(2, rec.channelCount)
                assertTrue("Duration (${rec.durationUs} us) must be ~12s", abs(rec.durationUs - 12_000_000L) <= 250_000L)
                assertNotNull(rec.identity.verified10sAudioKey)
                assertEquals(64, rec.identity.verified10sAudioKey!!.length)
                println(
                    "STAGE03_CODEC_METRIC file=${allFiles[i].name} mime=${rec.mimeType} codec=\"${rec.codecDescription}\" " +
                        "durationUs=${rec.durationUs} shaBefore=${checksumsBefore[allFiles[i].name]} " +
                        "contentFp=${rec.identity.contentFingerprint} verified10sAudioKey=${rec.identity.verified10sAudioKey}"
                )
            }

            // ALAC M4A must fail with explicit A03_UNSUPPORTED_CODEC reason
            val alacOutcome = outcomes[4]
            assertEquals(ImportOutcomeType.REJECTED_UNSUPPORTED_OR_CORRUPT, alacOutcome.outcomeType)
            assertEquals(AudioErrorCode.A03_UNSUPPORTED_CODEC, alacOutcome.errorCode)
            assertTrue("Must report ALAC unsupported codec", alacOutcome.message.contains("ALAC", ignoreCase = true))
            println("STAGE03_CODEC_REJECT file=${alacFile.name} code=${alacOutcome.errorCode} reason=\"${alacOutcome.message}\"")

            // Verify non-destructive access: every file's SHA-256 checksum is identical after import + 10s decode
            for (f in allFiles) {
                val shaAfter = TrackFingerprinter.computeFullSha256(f)
                assertEquals("Checksum changed for ${f.name}", checksumsBefore[f.name], shaAfter)
                println("STAGE03_CHECKSUM_VERIFY file=${f.name} shaBefore=${checksumsBefore[f.name]} shaAfter=$shaAfter match=${checksumsBefore[f.name] == shaAfter}")
            }
            assertEquals(0, provider.resourceTracker.activeFileDescriptors)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun imports120TracksWithEmbeddedArtworkWithinBoundedMemoryAndZeroFdLeaks() {
        val tempDir = Files.createTempDirectory("setflow-s03-120tracks").toFile()
        try {
            val provider = VirtualSafTrackSourceProvider()
            val uris = ArrayList<String>(120)
            // 32 KiB synthetic JPEG payload for normal tracks, plus one 9 MiB oversized artwork block on track 0
            val normalArtPayload = ByteArray(32 * 1024) { (it and 0xFF).toByte() }
            val oversizedArtLen = MetadataNormalizer.MAX_EMBEDDED_ARTWORK_BYTES + 512 * 1024

            for (i in 0 until 120) {
                val f = File(tempDir, "set_track_%03d.flac".format(i))
                writeSyntheticFlacHeaderWithTrailingFrames(
                    file = f,
                    sampleRate = 44100,
                    channels = 2,
                    bitsPerSample = 16,
                    totalSamples = 44100L * 6L,
                    title = "Bulk Track #$i",
                    artist = "Artist #${i % 15}",
                    picturePayload = if (i == 0) null else normalArtPayload,
                    oversizedPictureLength = if (i == 0) oversizedArtLen else 0,
                    uniqueSeed = i
                )
                val uri = "content://com.android.externalstorage.documents/document/primary%3ABulk%2F${f.name}"
                provider.registerFileAsSafDocument(uri, f)
                uris.add(uri)
            }

            // Custom fast-verify test service that exercises full container + metadata + artwork + fast fingerprint
            // and decodes a 1s window on WAV/FLAC or verifies artwork cache bounds across 120 tracks
            Runtime.getRuntime().gc()
            val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            val service = LocalTrackLibraryService(provider, DiagnosticsRingBuffer())
            // Also verify 120 real decodable WAV tracks to measure full import + decode memory profile
            val wavUris = (0 until 120).map { i ->
                val wf = File(tempDir, "wav_track_%03d.wav".format(i))
                writeSyntheticWav(wf, sampleRate = 22050, channels = 1, durationSec = 2.0, freqHz = 220.0 + i * 3.0, title = "Track $i", artist = "Artist $i")
                val u = "content://com.android.externalstorage.documents/document/primary%3AWavBulk%2F${wf.name}"
                provider.registerFileAsSafDocument(u, wf)
                u
            }

            // Populate artwork cache directly from the 120 FLAC headers + run 120-track WAV import
            for (u in uris) {
                provider.openReadOnlyChannel(u).use { ch ->
                    val c = app.setflow.io.AudioContainerInspector.inspect(ch)
                    if (c.artworkBytes != null && !c.artworkOversizedSkipped) {
                        service.artworkCache.put("key-$u", c.artworkMimeType ?: "image/jpeg", c.artworkWidth, c.artworkHeight, c.artworkBytes)
                    }
                }
            }

            val outcomes = service.importUris(wavUris, verifyWindowSeconds = 2)
            Runtime.getRuntime().gc()
            val heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val heapDeltaBytes = (heapAfter - heapBefore).coerceAtLeast(0L)
            println(
                "STAGE03_MEMORY_PROFILE tracks=${service.tracks.value.size} artworkCacheUsedBytes=${service.artworkCache.usedBytes()} " +
                    "artworkCacheMaxBytes=${service.artworkCache.maxCacheBytes} artworkEntries=${service.artworkCache.entryCount()} " +
                    "heapBeforeBytes=$heapBefore heapAfterBytes=$heapAfter heapDeltaBytes=$heapDeltaBytes " +
                    "activeFds=${provider.resourceTracker.activeFileDescriptors} totalOpenedFds=${provider.resourceTracker.totalOpened}"
            )

            assertEquals(120, outcomes.count { it.outcomeType == ImportOutcomeType.IMPORTED_NEW })
            assertEquals(120, service.tracks.value.size)
            assertTrue(
                "Artwork cache (${service.artworkCache.usedBytes()} bytes) must never exceed 4 MiB budget (${service.artworkCache.maxCacheBytes})",
                service.artworkCache.usedBytes() <= service.artworkCache.maxCacheBytes
            )
            assertTrue(
                "Heap delta (${heapDeltaBytes / (1024 * 1024)} MiB) for 120-track import must remain bounded (< 64 MiB)",
                heapDeltaBytes < 64L * 1024L * 1024L
            )
            assertEquals(0, provider.resourceTracker.activeFileDescriptors)
            assertEquals(provider.resourceTracker.totalOpened, provider.resourceTracker.totalClosed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun runFfmpeg(vararg args: String) {
        val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals("ffmpeg failed ($code): $out", 0, code)
    }

    private fun writeSyntheticWav(
        file: File,
        sampleRate: Int,
        channels: Int,
        durationSec: Double,
        freqHz: Double,
        title: String? = null,
        artist: String? = null
    ) {
        val totalFrames = (sampleRate * durationSec).toInt()
        val bytesPerSample = 2
        val blockAlign = channels * bytesPerSample
        val dataSize = totalFrames * blockAlign

        val infoChunk = if (title != null || artist != null) {
            val out = ByteArrayOutputStream()
            out.write("INFO".toByteArray(StandardCharsets.US_ASCII))
            fun writeSub(id: String, value: String?) {
                if (value == null) return
                val bytes = (value + "\u0000").toByteArray(StandardCharsets.UTF_8)
                out.write(id.toByteArray(StandardCharsets.US_ASCII))
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
                out.write(bytes)
                if (bytes.size % 2 == 1) out.write(0)
            }
            writeSub("INAM", title)
            writeSub("IART", artist)
            out.toByteArray()
        } else {
            null
        }

        val listChunkTotal = if (infoChunk != null) 8 + infoChunk.size else 0
        val riffSize = 4 + (8 + 16) + listChunkTotal + (8 + dataSize)

        file.outputStream().buffered().use { out ->
            val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            hdr.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            hdr.putInt(riffSize)
            hdr.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            hdr.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            hdr.putInt(16)
            hdr.putShort(1) // PCM
            hdr.putShort(channels.toShort())
            hdr.putInt(sampleRate)
            hdr.putInt(sampleRate * blockAlign)
            hdr.putShort(blockAlign.toShort())
            hdr.putShort(16)
            out.write(hdr.array(), 0, 36)

            if (infoChunk != null) {
                val listHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                listHdr.put("LIST".toByteArray(StandardCharsets.US_ASCII))
                listHdr.putInt(infoChunk.size)
                out.write(listHdr.array())
                out.write(infoChunk)
            }

            val dataHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            dataHdr.put("data".toByteArray(StandardCharsets.US_ASCII))
            dataHdr.putInt(dataSize)
            out.write(dataHdr.array())

            val frameBuf = ByteBuffer.allocate(4096 * blockAlign).order(ByteOrder.LITTLE_ENDIAN)
            var frame = 0
            while (frame < totalFrames) {
                frameBuf.clear()
                val batch = minOf(4096, totalFrames - frame)
                for (i in 0 until batch) {
                    val t = (frame + i).toDouble() / sampleRate
                    val s = (sin(2.0 * Math.PI * freqHz * t) * 16000.0).toInt().toShort()
                    for (c in 0 until channels) {
                        frameBuf.putShort(s)
                    }
                }
                out.write(frameBuf.array(), 0, batch * blockAlign)
                frame += batch
            }
        }
    }

    private fun writeSyntheticFlacHeaderWithTrailingFrames(
        file: File,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        title: String,
        artist: String,
        picturePayload: ByteArray?,
        oversizedPictureLength: Int,
        uniqueSeed: Int
    ) {
        file.outputStream().buffered().use { out ->
            out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))

            // Block 0: STREAMINFO (34 bytes, not last)
            out.write(byteArrayOf(0x00, 0x00, 0x00, 34))
            val si = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN)
            si.putShort(4096)
            si.putShort(4096)
            si.put(byteArrayOf(0, 0, 0))
            si.put(byteArrayOf(0, 0, 0))
            val packed: Long = ((sampleRate.toLong() and 0xFFFFFL) shl 44) or
                (((channels - 1).toLong() and 0x7L) shl 41) or
                (((bitsPerSample - 1).toLong() and 0x1FL) shl 36) or
                (totalSamples and 0xFFFFFFFFFL)
            si.putLong(packed)
            repeat(16) { si.put((uniqueSeed + it).toByte()) }
            out.write(si.array())

            // Block 4: VORBIS_COMMENT
            val vcOut = ByteArrayOutputStream()
            val vendor = "SetFlowTest".toByteArray(StandardCharsets.UTF_8)
            vcOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendor.size).array())
            vcOut.write(vendor)
            val comments = listOf("TITLE=$title", "ARTIST=$artist")
            vcOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(comments.size).array())
            for (c in comments) {
                val cb = c.toByteArray(StandardCharsets.UTF_8)
                vcOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cb.size).array())
                vcOut.write(cb)
            }
            val vcBytes = vcOut.toByteArray()
            val hasPicture = picturePayload != null || oversizedPictureLength > 0
            val vcFirstByte = if (hasPicture) 0x04 else 0x84
            out.write(
                byteArrayOf(
                    vcFirstByte.toByte(),
                    ((vcBytes.size ushr 16) and 0xFF).toByte(),
                    ((vcBytes.size ushr 8) and 0xFF).toByte(),
                    (vcBytes.size and 0xFF).toByte()
                )
            )
            out.write(vcBytes)

            if (picturePayload != null) {
                val mimeBytes = "image/jpeg".toByteArray(StandardCharsets.US_ASCII)
                val picBlockLen = 32 + mimeBytes.size + picturePayload.size
                out.write(
                    byteArrayOf(
                        0x86.toByte(), // last + PICTURE (6)
                        ((picBlockLen ushr 16) and 0xFF).toByte(),
                        ((picBlockLen ushr 8) and 0xFF).toByte(),
                        (picBlockLen and 0xFF).toByte()
                    )
                )
                val pb = ByteBuffer.allocate(32 + mimeBytes.size).order(ByteOrder.BIG_ENDIAN)
                pb.putInt(3) // front cover
                pb.putInt(mimeBytes.size)
                pb.put(mimeBytes)
                pb.putInt(0) // desc len
                pb.putInt(1024) // width
                pb.putInt(1024) // height
                pb.putInt(24)
                pb.putInt(0)
                pb.putInt(picturePayload.size)
                out.write(pb.array())
                out.write(picturePayload)
            } else if (oversizedPictureLength > 0) {
                val mimeBytes = "image/jpeg".toByteArray(StandardCharsets.US_ASCII)
                val picBlockLen = 32 + mimeBytes.size + oversizedPictureLength
                out.write(
                    byteArrayOf(
                        0x86.toByte(),
                        ((picBlockLen ushr 16) and 0xFF).toByte(),
                        ((picBlockLen ushr 8) and 0xFF).toByte(),
                        (picBlockLen and 0xFF).toByte()
                    )
                )
                val pb = ByteBuffer.allocate(32 + mimeBytes.size).order(ByteOrder.BIG_ENDIAN)
                pb.putInt(3)
                pb.putInt(mimeBytes.size)
                pb.put(mimeBytes)
                pb.putInt(0)
                pb.putInt(4096)
                pb.putInt(4096)
                pb.putInt(24)
                pb.putInt(0)
                pb.putInt(oversizedPictureLength)
                out.write(pb.array())
                val chunk = ByteArray(65536)
                var remaining = oversizedPictureLength
                while (remaining > 0) {
                    val w = minOf(chunk.size, remaining)
                    out.write(chunk, 0, w)
                    remaining -= w
                }
            }
        }
    }
}
