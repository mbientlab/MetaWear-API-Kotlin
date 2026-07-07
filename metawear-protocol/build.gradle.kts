plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Pure-JVM module — no Android dependencies. Keeping the protocol/parsing layer
// here means its tests run on a plain JVM in milliseconds (the equivalent of the
// Swift package's no-hardware `swift test` suite) and the door stays open to a
// future Kotlin Multiplatform commonMain target.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // Instant for Timestamped / LoggedSample. KMP-friendly time type.
    api(libs.kotlinx.datetime)
    // Flow / suspend in the BleTransport seam (pure JVM, KMP-friendly).
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
