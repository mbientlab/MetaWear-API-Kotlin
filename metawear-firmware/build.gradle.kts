plugins {
    // AGP 9 "built-in Kotlin" (see :metawear-core): the library plugin runs the
    // Kotlin compiler itself, so org.jetbrains.kotlin.android must NOT be applied.
    alias(libs.plugins.android.library)
}

// Nordic-DFU firmware updates: port of the Swift MetaWearFirmware package.
//
//   MWFirmwareServer / MWFirmwareCatalog / MWFirmwareBuild → FirmwareServer / …
//   MWFirmwareError                                        → FirmwareException
//   DFUSession (iOS NordicDFU delegate wrapper)            → DfuSession (Android
//                                                            DFU broadcast wrapper)
//   MetaWearDevice+DFU extension                           → FirmwareUpdate.kt
//
// JSON: the firmware catalog (info2.json) is parsed by a hand-rolled minimal
// recursive-descent parser in FirmwareCatalog.kt — the same approach as
// :metawear-protocol's BoardState codec. android.org.json is NOT on the JVM
// unit-test classpath (android.jar test stubs throw), so hand-rolling keeps
// the module dependency-light AND lets all catalog tests run as plain JVM
// unit tests with no org.json test double.
android {
    namespace = "com.mbientlab.metawear.firmware"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // JVM unit tests use JUnit 5, matching the other modules.
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Consumers program against MetaWearDevice / DeviceInformation / Debug,
    // all of which live in the protocol module.
    api(project(":metawear-protocol"))

    // Nordic Android DFU — DfuServiceInitiator / DfuBaseService / listeners.
    implementation(libs.nordic.dfu)

    // JVM unit tests: version comparison, catalog parsing/selection, server
    // logic against a mock fetcher, bootloader interlock, progress values.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
