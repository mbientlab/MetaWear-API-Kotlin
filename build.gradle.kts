plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Declared here (apply false) so AGP resolves on one classpath;
    // :metawear-core applies it. AGP 9 compiles Kotlin itself (built-in
    // Kotlin), so no separate org.jetbrains.kotlin.android plugin exists here.
    alias(libs.plugins.android.library) apply false
    // :app — application plugin ships in the same AGP artifact; declaring it
    // here keeps both markers on one classpath (a versioned request from a
    // submodule alone fails: "plugin is already on the classpath").
    alias(libs.plugins.android.application) apply false
    // :app — Compose compiler plugin, version matching AGP's embedded Kotlin.
    alias(libs.plugins.kotlin.compose) apply false
}
