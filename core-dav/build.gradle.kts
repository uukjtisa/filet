plugins {
    alias(libs.plugins.kotlin.jvm)
}

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
    // The server talks to the VFS abstraction and to nothing else about storage.
    api(project(":core-vfs-jvm"))

    /**
     * `org.json` compile-only, deliberately.
     *
     * `android.jar` carries `org.json` on the platform, so an Android consumer must NOT also
     * receive the Maven artifact - two copies of the same class is a build failure on the good
     * days and a surprise at runtime on the others. A desktop JVM has no `org.json` at all and
     * declares the real dependency itself.
     *
     * So this module compiles against the API and names no implementation, which is exactly
     * what `compileOnly` means. The tests below get the real one, because a codec with no
     * implementation to run against is not tested.
     */
    compileOnly(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
