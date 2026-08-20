import java.util.Properties

plugins {
    // AGP 9 built-in Kotlin (see :metawear-core) — org.jetbrains.kotlin.android
    // must NOT be applied. The Compose compiler Gradle plugin (2.2.10, matching
    // AGP 9.2.1's embedded Kotlin compiler) plugs into built-in Kotlin directly.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing reads from an UNTRACKED file so no secret ever enters git.
// Create app/keystore.properties (gitignored) with:
//   storeFile=/absolute/path/to/mbientlab_android_apps.jks
//   storePassword=…
//   keyAlias=…
//   keyPassword=…
// When the file is absent (CI, fresh clones) the release build type still
// assembles — unsigned — so the AAB task exists everywhere and only a machine
// holding the upload key can produce a signed artifact.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

// Jetpack Compose MetaWear app: scanning, live streaming with ring-buffer
// decimation, on-device logging + download, session history with CSV export,
// LED/haptic controls, device settings, and firmware updates.
android {
    namespace = "com.mbientlab.metawear.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mbientlab.metawear.app"
        minSdk = 26
        targetSdk = 36
        // This package already ships on Google Play (the previous-generation
        // app reached versionCode 25 / 4.0.0). Play rejects any upload whose
        // versionCode is not higher than the live one, so the new app line
        // starts at 100 / 5.0.0 — clear headroom above the old series and an
        // unmistakable major bump for existing users.
        versionCode = 100
        versionName = "5.0.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink + obfuscate. Keep rules for Room, the Nordic BLE/DFU
            // libraries, and Compose live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Without an upload keystore on this machine, sign with the debug
            // key so the minified build can still be installed and smoke-tested
            // on a device. Play never sees this artifact — the store AAB is
            // produced only where keystore.properties exists.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            // Distinct id so a debug build can sit beside the store app.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    bundle {
        // Let Play deliver per-device splits; language splits stay off so
        // the app is fully usable offline in any locale (single-language app).
        language.enableSplit = false
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // JVM unit tests use JUnit 5, matching the library modules.
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":metawear-protocol"))
    implementation(project(":metawear-core"))
    implementation(project(":metawear-persistence"))
    implementation(project(":metawear-firmware"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)

    // JVM unit tests: RingBuffer, Channel decimation, CSV export, filenames,
    // bandwidth advisor, group-capture walks, and end-to-end walks through the
    // real MetaWearDevice against DemoBleTransport (a test-only emulator).
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Room's annotation JAR so tests can implement PersistenceDao in memory.
    testImplementation(libs.androidx.room.common)
    testRuntimeOnly(libs.junit.platform.launcher)
}
