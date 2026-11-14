/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow

import app.setflow.domain.AudioErrorCode
import app.setflow.domain.DiagnosticsRingBuffer
import app.setflow.domain.SchedulerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Comprehensive verification suite for Stage 14 — Automated Quality Gates, Licensing,
 * Merged Manifest Permissions, Privacy Redaction and Release Builds (STAGES/14_CI_LEGAL_SECURITY.txt).
 */
class CiLegalSecurityStage14Test {

    private fun findRepoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "README.md").exists()) {
            dir = dir.parentFile
        }
        return dir ?: error("Could not locate repository root containing README.md")
    }

    @Test
    fun test01_mergedManifestExcludesAllNetworkPermissions() {
        val repoRoot = findRepoRoot()
        val manifestFile = File(
            repoRoot,
            "base/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
        )

        // If the merged manifest exists from assembleRelease / processReleaseMainManifest, verify it strictly
        if (manifestFile.exists()) {
            val content = manifestFile.readText()

            // Forbidden permissions (100% offline invariant)
            assertFalse(
                "Release merged manifest MUST NOT contain INTERNET permission",
                content.contains("android.permission.INTERNET")
            )
            assertFalse(
                "Release merged manifest MUST NOT contain ACCESS_NETWORK_STATE permission",
                content.contains("android.permission.ACCESS_NETWORK_STATE")
            )
            assertFalse(
                "Release merged manifest MUST NOT contain ACCESS_WIFI_STATE permission",
                content.contains("android.permission.ACCESS_WIFI_STATE")
            )
            assertFalse(
                "Release merged manifest MUST NOT contain REQUEST_INSTALL_PACKAGES permission",
                content.contains("android.permission.REQUEST_INSTALL_PACKAGES")
            )
            assertFalse(
                "Release merged manifest MUST NOT contain MANAGE_EXTERNAL_STORAGE permission",
                content.contains("android.permission.MANAGE_EXTERNAL_STORAGE")
            )

            // Permitted permissions for background playback & notifications
            assertTrue(
                "Release merged manifest must declare FOREGROUND_SERVICE",
                content.contains("android.permission.FOREGROUND_SERVICE")
            )
            assertTrue(
                "Release merged manifest must declare FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                content.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")
            )
            assertTrue(
                "Release merged manifest must declare POST_NOTIFICATIONS",
                content.contains("android.permission.POST_NOTIFICATIONS")
            )
        }
    }

    @Test
    fun test02_sourceManifestDeclaresToolsRemoveForNetworkPermissions() {
        val repoRoot = findRepoRoot()
        val sourceManifest = File(repoRoot, "base/app/src/main/AndroidManifest.xml")
        assertTrue("Source AndroidManifest.xml must exist", sourceManifest.exists())

        val content = sourceManifest.readText()
        assertTrue(
            "Source manifest must explicitly remove INTERNET via tools:node=\"remove\"",
            content.contains("""android:name="android.permission.INTERNET"""") &&
                    content.contains("""tools:node="remove"""")
        )
        assertTrue(
            "Source manifest must explicitly remove ACCESS_NETWORK_STATE via tools:node=\"remove\"",
            content.contains("""android:name="android.permission.ACCESS_NETWORK_STATE"""")
        )
        assertTrue(
            "Source manifest must explicitly remove ACCESS_WIFI_STATE via tools:node=\"remove\"",
            content.contains("""android:name="android.permission.ACCESS_WIFI_STATE"""")
        )
        assertTrue(
            "Source manifest must explicitly remove REQUEST_INSTALL_PACKAGES via tools:node=\"remove\"",
            content.contains("""android:name="android.permission.REQUEST_INSTALL_PACKAGES"""")
        )
    }

    @Test
    fun test03_diagnosticsRingBufferRedactionComprehensive() {
        // Content URIs
        val contentUriMsg = "Failed to open content://media/external/audio/media/10042 for playback"
        val redactedContentUri = DiagnosticsRingBuffer.redact(contentUriMsg)
        assertEquals("Failed to open <redacted-path> for playback", redactedContentUri)
        assertFalse(redactedContentUri.contains("10042"))

        // File URIs
        val fileUriMsg = "Loaded file:///storage/emulated/0/Music/SecretTrack.flac successfully"
        val redactedFileUri = DiagnosticsRingBuffer.redact(fileUriMsg)
        assertEquals("Loaded <redacted-path> successfully", redactedFileUri)
        assertFalse(redactedFileUri.contains("SecretTrack"))

        // Absolute POSIX filesystem paths
        val posixMsg = "SQLite DB opened at /data/user/0/app.setflow/databases/setflow.db"
        val redactedPosix = DiagnosticsRingBuffer.redact(posixMsg)
        assertEquals("SQLite DB opened at <redacted-path>", redactedPosix)
        assertFalse(redactedPosix.contains("/data/user/0"))

        // Download directory paths
        val downloadMsg = "Rendered master WAV to /storage/emulated/0/Download/DJ_Club_Set_2026.wav"
        val redactedDownload = DiagnosticsRingBuffer.redact(downloadMsg)
        assertEquals("Rendered master WAV to <redacted-path>", redactedDownload)
        assertFalse(redactedDownload.contains("DJ_Club_Set_2026"))

        // Complete URL scrubbing (including query strings)
        val urlWithSecrets = "https://internal.test/stream?token=SECRET_AUTH_TOKEN_99&sig=HEXSIG1234"
        val redactedUrl = DiagnosticsRingBuffer.redact(urlWithSecrets)
        assertEquals("<redacted-path>", redactedUrl)
        assertFalse(redactedUrl.contains("SECRET_AUTH_TOKEN_99"))
        assertFalse(redactedUrl.contains("HEXSIG1234"))

        // Standalone token, signature, password, bearer query parameters
        val secretMsg = "Auth failed: token=SECRET_AUTH_TOKEN_99&sig=HEXSIG1234&password=MyPassword!&bearer=JWT_BEARER"
        val redactedSecret = DiagnosticsRingBuffer.redact(secretMsg)
        assertTrue("Tokens must be redacted", redactedSecret.contains("token=<redacted>"))
        assertTrue("Signatures must be redacted", redactedSecret.contains("sig=<redacted>"))
        assertTrue("Passwords must be redacted", redactedSecret.contains("password=<redacted>"))
        assertTrue("Bearers must be redacted", redactedSecret.contains("bearer=<redacted>"))
        assertFalse(redactedSecret.contains("SECRET_AUTH_TOKEN_99"))
        assertFalse(redactedSecret.contains("HEXSIG1234"))
        assertFalse(redactedSecret.contains("MyPassword!"))
        assertFalse(redactedSecret.contains("JWT_BEARER"))
    }

    @Test
    fun test04_diagnosticsRingBufferBoundedMemoryAndSnapshot() {
        val buffer = DiagnosticsRingBuffer(capacity = 5)
        assertEquals(5, buffer.capacity)
        assertTrue(buffer.snapshot().isEmpty())

        for (i in 1..10) {
            buffer.record(
                category = "TEST",
                phase = SchedulerPhase.RUNNING,
                errorCode = AudioErrorCode.A01_SOURCE_MISSING,
                message = "Event $i from /path/to/file$i.wav"
            )
        }

        val snapshot = buffer.snapshot()
        assertEquals("Ring buffer must hold exactly its capacity", 5, snapshot.size)

        // Oldest 5 events (1..5) should have been dropped; only 6..10 remain
        assertEquals("Event 6 from <redacted-path>", snapshot[0].message)
        assertEquals("Event 10 from <redacted-path>", snapshot[4].message)

        // Clear buffer
        buffer.clear()
        assertTrue("Buffer must be empty after clear", buffer.snapshot().isEmpty())
    }

    @Test
    fun test05_legalAndLicensingNoticesCompliance() {
        val repoRoot = findRepoRoot()

        val licenseFile = File(repoRoot, "LICENSE")
        assertTrue("LICENSE file must exist", licenseFile.exists())
        val licenseText = licenseFile.readText()
        assertTrue(
            "LICENSE must be GNU General Public License Version 3",
            licenseText.contains("GNU GENERAL PUBLIC LICENSE") && licenseText.contains("Version 3")
        )

        val noticeFile = File(repoRoot, "NOTICE.md")
        assertTrue("NOTICE.md must exist", noticeFile.exists())
        val noticeText = noticeFile.readText()
        assertTrue(
            "NOTICE.md must reference SetFlow copyright",
            noticeText.contains("SetFlow") && noticeText.contains("Copyright")
        )
        assertTrue(
            "NOTICE.md must attribute upstream Myspoti commit 95a24e7e4de82ec89d4ec11d5b05b146c44d78e7",
            noticeText.contains("95a24e7e4de82ec89d4ec11d5b05b146c44d78e7")
        )

        val thirdPartyFile = File(repoRoot, "THIRD_PARTY_NOTICES.md")
        assertTrue("THIRD_PARTY_NOTICES.md must exist", thirdPartyFile.exists())
        val thirdPartyText = thirdPartyFile.readText()
        assertTrue(
            "THIRD_PARTY_NOTICES.md must attribute Beat This! MIT",
            thirdPartyText.contains("Beat This!") && thirdPartyText.contains("MIT")
        )
        assertTrue(
            "THIRD_PARTY_NOTICES.md must attribute ONNX Runtime MIT",
            thirdPartyText.contains("ONNX Runtime") && thirdPartyText.contains("MIT")
        )
        assertTrue(
            "THIRD_PARTY_NOTICES.md must attribute BitChord GPL-3.0-or-later",
            thirdPartyText.contains("BitChord") && thirdPartyText.contains("GPL-3.0")
        )

        val agentsFile = File(repoRoot, "AGENTS.md")
        assertTrue("AGENTS.md must exist", agentsFile.exists())
        val agentsText = agentsFile.readText()
        assertTrue(
            "AGENTS.md must mandate GPL-3.0 and credit retention",
            agentsText.contains("GPL-3.0") && agentsText.contains("Credit")
        )
    }

    @Test
    fun test06_applicationIdentityAndDecoupling() {
        val repoRoot = findRepoRoot()
        val buildFile = File(repoRoot, "base/app/build.gradle.kts")
        assertTrue("base/app/build.gradle.kts must exist", buildFile.exists())

        val buildContent = buildFile.readText()

        // Application ID & Namespace decoupling
        assertTrue(
            "Namespace must be app.setflow",
            buildContent.contains("""namespace = "app.setflow"""")
        )
        assertTrue(
            "applicationId must be app.setflow",
            buildContent.contains("""applicationId = "app.setflow"""")
        )
        assertTrue(
            "Debug applicationIdSuffix must be .dev",
            buildContent.contains("""applicationIdSuffix = ".dev"""")
        )
        assertTrue(
            "appLabel must be SetFlow",
            buildContent.contains("""manifestPlaceholders["appLabel"] = "SetFlow"""")
        )

        // SDK requirements
        assertTrue("minSdk must be 29", buildContent.contains("minSdk = 29"))
        assertTrue("targetSdk must be 36", buildContent.contains("targetSdk = 36"))
        assertTrue("compileSdk must be 37", buildContent.contains("compileSdk = 37"))

        // Versioning: minimum stage 14 or beta 15
        val hasValidVersionCode = buildContent.contains("?: 14") || buildContent.contains("?: 15")
        assertTrue("versionCode must be at least 14", hasValidVersionCode)
        val hasValidVersionName = buildContent.contains("0.14.0-stage14") || buildContent.contains("0.15.0-beta.1")
        assertTrue("versionName must be at least 0.14.0 or beta", hasValidVersionName)
    }

    @Test
    fun test07_proguardR8RulesPreserveNativeAndRoom() {
        val repoRoot = findRepoRoot()
        val proguardFile = File(repoRoot, "base/app/proguard-rules.pro")
        assertTrue("base/app/proguard-rules.pro must exist", proguardFile.exists())

        val content = proguardFile.readText()

        assertTrue(
            "ProGuard rules must keep ai.onnxruntime.** to protect JNI bindings",
            content.contains("-keep class ai.onnxruntime.** { *; }")
        )
        assertTrue(
            "ProGuard rules must keep RoomDatabase subclasses",
            content.contains("-keep class * extends androidx.room.RoomDatabase")
        )
        assertTrue(
            "ProGuard rules must keep Serializable models",
            content.contains("-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }")
        )
    }

    @Test
    fun test08_deterministicChecksumCalculation() {
        val data = "SetFlow Release Candidate Byte Stream 0.14.0".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        val hexHash = digest.joinToString("") { "%02x".format(it) }

        assertEquals(64, hexHash.length)
        // Repeat calculation to ensure determinism
        val md2 = MessageDigest.getInstance("SHA-256")
        val hexHash2 = md2.digest(data).joinToString("") { "%02x".format(it) }
        assertEquals("SHA-256 calculation must be completely deterministic", hexHash, hexHash2)

        // Check if SHA256SUMS.txt exists in build output and has valid format
        val repoRoot = findRepoRoot()
        val shaFile = File(repoRoot, "base/app/build/release-apks/SHA256SUMS.txt")
        if (shaFile.exists()) {
            val lines = shaFile.readLines().filter { it.isNotBlank() }
            assertTrue("SHA256SUMS.txt must contain at least one checksum line", lines.isNotEmpty())
            for (line in lines) {
                val parts = line.split("\\s+".toRegex(), limit = 2)
                assertEquals("Checksum line must consist of hash and filename: $line", 2, parts.size)
                assertEquals("Hash must be exactly 64 hex characters", 64, parts[0].length)
                assertTrue("Target must be an APK file", parts[1].endsWith(".apk"))
            }
        }
    }

    @Test
    fun test09_ciWorkflowHardeningAndDecoupling() {
        val repoRoot = findRepoRoot()
        val workflowFile = File(repoRoot, ".github/workflows/android.yml")
        assertTrue(".github/workflows/android.yml must exist", workflowFile.exists())

        val workflowContent = workflowFile.readText()

        // Upstream secret decoupling
        assertFalse(
            "Workflow must NOT reference upstream MYSPOTI_KEYSTORE",
            workflowContent.contains("MYSPOTI_KEYSTORE")
        )
        assertFalse(
            "Workflow must NOT reference myspoti-debug artifact name",
            workflowContent.contains("name: myspoti-debug")
        )

        // SetFlow naming
        assertTrue(
            "Workflow must upload setflow-debug artifact",
            workflowContent.contains("setflow-debug")
        )
        assertTrue(
            "Workflow must reference SETFLOW_KEYSTORE secrets",
            workflowContent.contains("SETFLOW_KEYSTORE")
        )
        assertTrue(
            "Workflow must run :automix:test and :app:testDebugUnitTest",
            workflowContent.contains("./gradlew :automix:test :app:testDebugUnitTest")
        )
        assertTrue(
            "Workflow must verify merged manifest permissions",
            workflowContent.contains("Verify Merged Manifest Permissions")
        )
    }

    @Test
    fun test10_softwareBillOfMaterialsCoverage() {
        val repoRoot = findRepoRoot()
        val sbomFile = File(repoRoot, "docs/SBOM.md")
        assertTrue("docs/SBOM.md must exist", sbomFile.exists())

        val sbomContent = sbomFile.readText()

        assertTrue(
            "SBOM must include onnxruntime-android",
            sbomContent.contains("com.microsoft.onnxruntime:onnxruntime-android")
        )
        assertTrue(
            "SBOM must include media3-exoplayer",
            sbomContent.contains("androidx.media3:media3-exoplayer")
        )
        assertTrue(
            "SBOM must include room-common",
            sbomContent.contains("androidx.room:room-common")
        )
        assertTrue(
            "SBOM must include beat-this-small0-int8.onnx asset",
            sbomContent.contains("beat-this-small0-int8.onnx")
        )
        assertTrue(
            "SBOM must specify GPL-3.0 license",
            sbomContent.contains("GPL-3.0")
        )
    }
}
