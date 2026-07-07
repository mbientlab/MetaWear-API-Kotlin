plugins {
    // AGP 9 "built-in Kotlin": the library plugin registers the `kotlin`
    // extension and runs the Kotlin compiler itself — applying
    // org.jetbrains.kotlin.android on top is an error ("extension already
    // registered"), so this module deliberately applies AGP alone.
    alias(libs.plugins.android.library)
}

// Android transport layer: the Nordic-backed BleTransport implementation plus
// the AndroidMetaWear entry point. All protocol/parsing logic stays in the pure
// JVM :metawear-protocol module; this module only touches the radio.
android {
    namespace = "com.mbientlab.metawear.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api: consumers of :metawear-core program against MetaWearDevice /
    // MetaWearScanner / BleTransport, all of which live in the protocol module.
    api(project(":metawear-protocol"))

    implementation(libs.nordic.ble.client)
    implementation(libs.nordic.ble.scanner)

    // Hardware smoke tests (self-skip when no board is in range).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
