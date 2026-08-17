plugins {
    // AGP 9 built-in Kotlin (see :metawear-core) — org.jetbrains.kotlin.android
    // must NOT be applied. The Compose compiler Gradle plugin (2.2.10, matching
    // AGP 9.2.1's embedded Kotlin compiler) plugs into built-in Kotlin directly.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Jetpack Compose MetaWear app: scanning, live streaming with ring-buffer
// decimation, on-device logging + download, session history with CSV export,
// LED/haptic controls, device settings, firmware updates, and a hardware-free
// demo mode.
android {
    namespace = "com.mbientlab.metawear.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mbientlab.metawear.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    // bandwidth advisor, demo-transport round trips, group-capture walks.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Room's annotation JAR so tests can implement PersistenceDao in memory.
    testImplementation(libs.androidx.room.common)
    testRuntimeOnly(libs.junit.platform.launcher)
}
