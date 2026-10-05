plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Java 17, matching `:core-vfs`.
 *
 * Not the toolchain's own 21: an Android module consumes this one's class files, and the floor
 * for that is whatever `:core-vfs` compiles to. Targeting higher here would produce a module
 * that builds on its own and fails the moment `:app` links it, which is the worst order to find
 * that out in.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // `-core`, never `-android`. See the catalogue note: the Android artifact installs a Main
    // dispatcher that needs a Looper, and this module runs on a desktop too.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
