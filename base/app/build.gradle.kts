/* SPDX-License-Identifier: GPL-3.0-only */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val RELEASE_ABIS = listOf("arm64-v8a", "armeabi-v7a")

android {
    namespace = "app.setflow"
    compileSdk = 37

    val version = (findProperty("appVersionName") as String?) ?: "0.13.0-stage13"

    defaultConfig {
        applicationId = "app.setflow"
        manifestPlaceholders["appLabel"] = "SetFlow"
        minSdk = 29
        targetSdk = 36
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 13
        versionName = version
        (findProperty("abis") as String?)?.let { ndk { abiFilters += it.split(",") } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "SetFlow Dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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
