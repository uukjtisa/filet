plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.niccc2007.filet.vfs"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The RAR readers. Native, because every JVM RAR decoder carries the UnRAR licence's
    // field-of-use restriction and GPL-3 cannot take one; libarchive's are BSD-2-Clause.
    api(project(":core-native"))
    implementation(libs.kotlinx.coroutines.android)
    // The APK-as-a-filesystem provider (PLAN.md L0): a dex entry is a walkable tree of
    // smali, which means the disassembler belongs to the provider, not to a screen.
    api(libs.smali.dexlib2)
    api(libs.smali.baksmali)
    // M8. Root goes through libsu's managed shell, never Runtime.exec("su") per command.
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)
    implementation(libs.smbj)
    implementation(libs.sshj)
    implementation(libs.commons.net)
    // smbj and sshj log through slf4j; without a binding they print a warning on every call.
    implementation(libs.slf4j.nop)
    // Round 7: tar, 7z and the gz/bz2/xz wrappers. `api` rather than `implementation` because
    // the app layer builds archives with the same writers the provider reads with, and two
    // copies of that decision is how the reader and the writer drift apart.
    api(libs.commons.compress)
    api(libs.xz)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
