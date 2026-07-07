pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    // Lets Gradle auto-provision a matching JDK for the configured toolchain,
    // so the build works on a machine without JDK 17 already installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "metawear-android"

// Step 1: pure-Kotlin protocol layer (zero Android imports, JVM unit tests).
// The remaining Android modules (:metawear-persistence, :metawear-firmware,
// :app) are added in later steps of the build plan.
include(":metawear-protocol")

// Step 2: Android transport layer — Nordic-backed BleTransport + hardware smoke tests.
include(":metawear-core")

// Step 3: Room-backed session persistence — port of the Swift MetaWearPersistence
// SwiftData package (session/sample records, PersistenceStore, CSV export).
include(":metawear-persistence")

// Step 4: Nordic-DFU firmware updates — port of the Swift MetaWearFirmware
// package (catalog client, bootloader interlock, DFU orchestration).
include(":metawear-firmware")
