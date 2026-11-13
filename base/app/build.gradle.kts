/* SPDX-License-Identifier: GPL-3.0-only */
import java.io.File
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val RELEASE_ABIS = listOf("arm64-v8a", "armeabi-v7a")
val ABI_NAMES = mapOf("arm64-v8a" to "ARMv8", "armeabi-v7a" to "ARMv7")

android {
    namespace = "app.setflow"
    compileSdk = 37

    val version = (findProperty("appVersionName") as String?) ?: "0.14.0-stage14"

    defaultConfig {
        applicationId = "app.setflow"
        manifestPlaceholders["appLabel"] = "SetFlow"
        minSdk = 29
        targetSdk = 36
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 14
        versionName = version
        (findProperty("abis") as String?)?.let { ndk { abiFilters += it.split(",") } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("SETFLOW_KEYSTORE") ?: (findProperty("setflowKeystore") as String?)
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SETFLOW_KEYSTORE_PASSWORD") ?: (findProperty("setflowKeystorePassword") as String?) ?: ""
                keyAlias = System.getenv("SETFLOW_KEY_ALIAS") ?: (findProperty("setflowKeyAlias") as String?) ?: ""
                keyPassword = System.getenv("SETFLOW_KEY_PASSWORD") ?: (findProperty("setflowKeyPassword") as String?) ?: ""
            } else {
                val debugConfig = signingConfigs.getByName("debug")
                storeFile = debugConfig.storeFile
                storePassword = debugConfig.storePassword
                keyAlias = debugConfig.keyAlias
                keyPassword = debugConfig.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "SetFlow Dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = findProperty("abis") == null
            reset()
            include(*RELEASE_ABIS.toTypedArray())
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs { excludes += listOf("lib/x86/**", "lib/x86_64/**") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Pure Kotlin beat analysis, SeguePlan, SegueValidator, and SegueDeck DSP
    implementation(project(":automix"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")

    // AndroidX core & lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Jetpack Compose & Navigation
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation(libs.compose.ui.tooling)

    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(libs.serialization.json)

    // Room annotations & SQLite persistence (Stage 04, ADR-002)
    implementation("androidx.room:room-common:2.7.0")

    // Local Media3 playback & decoding (no DASH/network streaming libraries)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.23.2")
}

/**
 * `./gradlew :app:collectApks` after assembleRelease:
 * Copies the split and universal APKs under clear SetFlow names in `build/release-apks/`:
 * SetFlow-<version>-ARMv8.apk (64-bit phones), SetFlow-<version>-ARMv7.apk (32-bit phones),
 * and SetFlow-<version>-universal.apk (both). Betas are SetFlow-Beta-...
 * Generates SHA256SUMS.txt containing cryptographic checksums for release verification.
 */
tasks.register("collectApks") {
    mustRunAfter("assembleRelease")
    val version = (findProperty("appVersionName") as String?) ?: "0.14.0-stage14"
    val beta = findProperty("appVersionName") == null || version.contains("-beta")
    val app = when {
        findProperty("overDev") != null -> "SetFlow-dev"
        beta -> "SetFlow-Beta"
        else -> "SetFlow"
    }
    val abis = RELEASE_ABIS.toList()
    val abiNames = ABI_NAMES.toMap()
    val from = layout.buildDirectory.dir("outputs/apk/release")
    val into = layout.buildDirectory.dir("release-apks")
    doLast {
        val src = from.get().asFile
        val dst = into.get().asFile.apply { deleteRecursively(); mkdirs() }
        fun copy(name: String, to: String): java.io.File {
            val f = src.resolve(name)
            if (!f.exists()) throw GradleException("$name missing: run assembleRelease without -Pabis first")
            val target = dst.resolve(to)
            f.copyTo(target, overwrite = true)
            return target
        }
        val collectedFiles = mutableListOf<java.io.File>()
        abis.forEach { abi ->
            val abiName = abiNames[abi] ?: abi
            collectedFiles += copy("app-$abi-release.apk", "$app-$version-$abiName.apk")
        }
        collectedFiles += copy("app-universal-release.apk", "$app-$version-universal.apk")

        // Generate SHA256 checksums
        val shaFile = dst.resolve("SHA256SUMS.txt")
        val md = MessageDigest.getInstance("SHA-256")
        val lines = collectedFiles.map { apk ->
            val bytes = apk.readBytes()
            val hash = md.digest(bytes).joinToString("") { b -> "%02x".format(b) }
            "$hash  ${apk.name}"
        }
        shaFile.writeText(lines.joinToString("\n") + "\n")

        dst.listFiles()?.sorted()?.forEach {
            println("${it.name}  ${it.length() / 1024} KB")
        }
    }
}
