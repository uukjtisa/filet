// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Declared here with a version and applied in the modules without one.
    //
    // The Android plugin already puts the Kotlin Gradle plugin on the build classpath without a
    // version Gradle can see, so a module asking for `org.jetbrains.kotlin.jvm` version 2.2.10
    // on its own is refused: "already on the classpath with an unknown version, so
    // compatibility cannot be checked". Resolving it once at the root is the fix, and it also
    // means there is one place the version is written.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose) apply false
}