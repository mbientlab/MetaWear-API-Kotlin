plugins {
    // AGP 9 "built-in Kotlin" (see :metawear-core): the library plugin runs the
    // Kotlin compiler itself, so org.jetbrains.kotlin.android must NOT be applied.
    alias(libs.plugins.android.library)
    // Room annotation processing. KSP >= 2.3.0 (standalone versioning) supports
    // AGP's built-in Kotlin directly — no Kotlin Gradle plugin required.
    alias(libs.plugins.ksp)
}

// Room-backed session persistence. Layering:
//   SessionRecord/SampleRecord  -> Room @Entity data classes
//   PersistenceStore            -> business logic over suspend DAO calls
//   PersistenceDatabase         -> the Room database (create one per app)
android {
    namespace = "com.mbientlab.metawear.persistence"
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
        // JVM unit tests use JUnit 5, matching :metawear-protocol.
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Consumers program against LoggedSample / DeviceInformation / DataTable /
    // the sample value types, all of which live in the protocol module.
    api(project(":metawear-protocol"))

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // JVM unit tests: store logic + Persistable codecs against a fake DAO.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Instrumented tests: real Room database round-trips (run on a device).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
