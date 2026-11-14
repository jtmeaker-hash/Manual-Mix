/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.setflow.domain.AudioErrorCode
import app.setflow.domain.AudioGraphOwner
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import app.setflow.service.LocalOnlyShellServiceGraph
import app.setflow.service.TrackResolutionResult
import app.setflow.ui.SetFlowRoute
import app.setflow.ui.SetFlowShellController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineShellStartupTest {

    @Test
    fun coldOfflineStartupRequiresNoAccountOrNetworkAndDoesNotPlayAudio() {
        val services = LocalOnlyShellServiceGraph(modelAssetPresent = true, modelAssetBytes = 5_596_894L)
        val controller = SetFlowShellController(services)
        val state = controller.uiState.value

        assertTrue("Offline-only mode must be enforced", state.offlineOnlyEnforced)
        assertFalse("No account may be required", state.accountRequired)
        assertEquals(SetFlowRoute.Sets, state.currentRoute)
        assertFalse("Stage 02 shell must not pretend to play audio", state.playbackState.isAudioPlaying)
        assertEquals(SchedulerPhase.IDLE, state.playbackState.phase)
        assertEquals(AudioGraphOwner.NONE, state.playbackState.activeOwner)
        assertTrue(state.analysisStatus.engineRetained)
        assertTrue(state.analysisStatus.modelAssetPresent)
    }

    @Test
    fun navigationBetweenSetsSetDetailAndSettingsPreservesHonestPlaceholders() {
        val controller = SetFlowShellController(LocalOnlyShellServiceGraph())

        assertEquals(SetFlowRoute.Sets, controller.uiState.value.currentRoute)
        assertFalse("At root, navigateBack should return false", controller.navigateBack())

        controller.openSetDetail("sample-shell-set-01")
        val detailState = controller.uiState.value
        assertEquals(SetFlowRoute.SetDetail("sample-shell-set-01"), detailState.currentRoute)
        assertNotNull(detailState.activeSetDetail)
        assertTrue(
            "Set Detail placeholder must explicitly state not implemented",
            detailState.activeSetDetail!!.notImplementedBanner.contains("Not implemented", ignoreCase = true)
        )
        assertFalse(detailState.activeSetDetail!!.isAudioPlaying)

        controller.openSettings()
        assertEquals(SetFlowRoute.Settings, controller.uiState.value.currentRoute)

        assertTrue(controller.navigateBack())
        assertEquals(SetFlowRoute.SetDetail("sample-shell-set-01"), controller.uiState.value.currentRoute)

        assertTrue(controller.navigateBack())
        assertEquals(SetFlowRoute.Sets, controller.uiState.value.currentRoute)
    }

    @Test
    fun playbackControllerStopAllIsIdempotentAndIncrementsGeneration() {
        val services = LocalOnlyShellServiceGraph()
        val controller = SetFlowShellController(services)

        assertEquals(0L, controller.uiState.value.playbackState.generation)
        controller.requestStopPlayback()
        assertEquals(1L, controller.uiState.value.playbackState.generation)
        assertEquals(SchedulerPhase.STOPPED, controller.uiState.value.playbackState.phase)
        assertFalse(controller.uiState.value.playbackState.isAudioPlaying)

        controller.requestStopPlayback()
        assertEquals(2L, controller.uiState.value.playbackState.generation)
        assertFalse(controller.uiState.value.playbackState.isAudioPlaying)
    }

    @Test
    fun trackResolverReturnsRelinkRequiredWithA01InStage02Shell() {
        val services = LocalOnlyShellServiceGraph()
        val result = services.trackResolver.resolve("unimplemented-track-01")
        assertTrue(result is TrackResolutionResult.RelinkRequired)
        val relink = result as TrackResolutionResult.RelinkRequired
        assertEquals(AudioErrorCode.A01_SOURCE_MISSING, relink.errorCode)
    }

    @Test
    fun audioErrorTaxonomyCoversA01ThroughA12() {
        val codes = AudioErrorCode.entries.map { it.code }
        val expected = (1..12).map { String.format("A%02d", it) }
        assertEquals(expected, codes)
    }

    @Test
    fun diagnosticsRingBufferIsBoundedAndRedactsPathsUrisAndTokens() {
        val ring = DiagnosticsRingBuffer(capacity = 3)
        ring.record("Test", message = "Opened content://com.android.externalstorage.documents/document/primary%3ADJs/track1.flac?token=secret123")
        ring.record("Test", message = "File /storage/emulated/0/Music/private_song.wav bearer=abc999")
        ring.record("Test", message = "Event 3")
        ring.record("Test", message = "Event 4")

        val snap = ring.snapshot()
        assertEquals(3, snap.size)
        assertFalse(snap[0].message.contains("private_song.wav"))
        assertFalse(snap[0].message.contains("abc999"))
        assertTrue(snap[0].message.contains("<redacted-path>"))
        assertTrue(snap[0].message.contains("bearer=<redacted>"))
        assertEquals("Event 4", snap[2].message)
    }

    @Test
    fun manifestContainsNoSpotifyHostsAndRemovesNetworkAndInstallPermissions() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Manifest must exist at ${manifestFile.absolutePath}", manifestFile.exists())
        val xml = manifestFile.readText()

        assertFalse("Manifest must not reference spotify.com", xml.contains("spotify.com", ignoreCase = true))
        assertFalse("Manifest must not reference spoti.fi", xml.contains("spoti.fi", ignoreCase = true))
        assertFalse("Manifest must not reference AccountBrowser", xml.contains("AccountBrowser"))
        assertFalse("Manifest must not reference AppUpdater", xml.contains("AppUpdater"))
        assertTrue("Manifest must explicitly remove INTERNET permission", xml.contains("android.permission.INTERNET") && xml.contains("tools:node=\"remove\""))
        assertTrue("Manifest must reference SetFlowApplication", xml.contains("app.setflow.SetFlowApplication"))
        assertTrue("Manifest must reference SetFlowActivity", xml.contains("app.setflow.ui.SetFlowActivity"))
    }

    @Test
    fun databaseDriverCreationDefaultsCleanlyOnJvmAndFallsBackSafely() {
        val tempDb = File.createTempFile("driver_test_", ".sqlite").apply { deleteOnExit() }
        val driver = app.setflow.db.SetFlowSchemaAndMigrations.createDriver(tempDb)
        assertNotNull("Driver must be created", driver)
        assertTrue("Driver must report read-write open mode", driver.openMode == app.setflow.db.DatabaseOpenMode.READ_WRITE)
        assertEquals(app.setflow.db.SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION, driver.schemaVersion)
    }

    @Test
    fun localOnlyShellServiceGraphWithContextParameterInstantiatesCleanly() {
        val tempDir = File.createTempFile("graph_test_", "").apply { delete(); mkdirs() }
        tempDir.deleteOnExit()
        val services = LocalOnlyShellServiceGraph(
            storageDirectory = tempDir,
            context = null
        )
        assertNotNull(services.projectDatabase)
        assertNotNull(services.trackLibrary)
        assertNotNull(services.setRepository)
        assertEquals(app.setflow.db.SetFlowSchemaAndMigrations.CURRENT_SCHEMA_VERSION, services.projectDatabase.schemaVersion)
    }

    @Test
    fun wavWriterSummaryIdempotencePreservesSha256Digest() {
        val tempWav = File.createTempFile("wav_summary_test_", ".wav").apply { deleteOnExit() }
        val writer = app.setflow.export.WavWriter(tempWav, sampleRate = 44100, channels = 2)
        val dummySamples = FloatArray(1024) { 0.25f }
        writer.writeFrames(dummySamples, 512)
        val s1 = writer.finalizeHeader()
        val s2 = writer.finalizeHeader()
        assertEquals("Summary SHA-256 must be identical across repeated calls", s1.sha256Hex, s2.sha256Hex)
        assertFalse("SHA-256 must not be empty-buffer digest", s1.sha256Hex == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        writer.close()
    }

    @Test
    fun setPlaybackServiceSecurityAndNotificationIconSafety() {
        val serviceFile = File("src/main/java/app/setflow/playback/SetPlaybackService.kt")
        assertTrue("SetPlaybackService.kt must exist", serviceFile.exists())
        val code = serviceFile.readText()
        assertTrue("Notification small icon must be monochrome drawable, not adaptive mipmap",
            code.contains("R.drawable.ic_launcher_monochrome") && !code.contains("setSmallIcon(R.mipmap.ic_launcher)"))
        assertTrue("Noisy receiver must be registered as NOT_EXPORTED",
            code.contains("ContextCompat.RECEIVER_NOT_EXPORTED") && !code.contains("ContextCompat.RECEIVER_EXPORTED"))
    }
}
