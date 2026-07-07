plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Declared here (apply false) so AGP resolves on one classpath;
    // :metawear-core applies it. AGP 9 compiles Kotlin itself (built-in
    // Kotlin), so no separate org.jetbrains.kotlin.android plugin exists here.
    alias(libs.plugins.android.library) apply false
}
