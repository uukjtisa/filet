import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The release signing key, if this machine has it.
 *
 * Present only on the machine that publishes: `keystore.properties` and every key format are
 * in `.gitignore`, and a clone without them still builds - it just produces an unsigned
 * release APK, which is what CI and anyone building from source should get.
 *
 * The key itself is not a detail that can be changed later. Android identifies an app by its
 * signature, so an update signed by a different key is refused as a different app and the only
 * way past it is uninstall-and-lose-your-data. From v0.1.0 onwards this file is as
 * irreplaceable as the source.
 */
val keystorePropertiesFile: File = rootProject.file("keystore.properties")

android {
    namespace = "dev.niccc2007.filet"

    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
        signingConfigs {
            create("githubPublish") {
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
                storeFile = file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"].toString()
                // v2 is what minSdk 26 actually needs. v3 is asked for anyway because it is
                // the only scheme that carries a proof-of-rotation record - without it in the
                // FIRST published APK, this key can never be replaced, only lost. v1 is off
                // (nothing below API 24 can install this) and v4 is an adb-incremental
                // sidecar file that a GitHub release has nowhere to put.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.niccc2007.filet"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("githubPublish")
            }
        }
        debug {
            // A different package, so a development build cannot see the release install and
            // vice versa. Bridge discovery enumerates both names (PLAN.md 5.4).
            //
            // Deliberately left on the default debug key rather than the publishing one. That
            // key can push an update to every installed copy of Filet, and a debug APK is the
            // build most likely to be left on a machine or passed around; the default key also
            // keeps a debug build installable over the previous debug build.
            applicationIdSuffix = ".debug"
        }
    }

    /**
     * Distribution flavours (PLAN.md §4).
     *
     * The updater is a build concern, not a runtime setting: F-Droid updates its own apps and
     * rejects one that updates itself, so `fdroid` compiles the whole thing out - the check,
     * the notification and the button.
     *
     * `REQUEST_INSTALL_PACKAGES` stays on BOTH flavours, and an earlier version of this comment
     * claiming otherwise was wrong. The APK inspector's Install button needs it, that is an
     * ordinary file-manager feature rather than self-updating, and F-Droid lists the permission
     * as something to disclose rather than something to refuse. Stripping it would have killed
     * a working button on that flavour to satisfy a rule that does not say that.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
            buildConfigField("String", "FLAVOR_LABEL", "\"github\"")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
            buildConfigField("String", "FLAVOR_LABEL", "\"f-droid\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    // The RAR readers (libarchive, BSD-2-Clause). Pulled in through :core-vfs as well,
    // named here so the .so is packaged into the APK.
    implementation(project(":core-native"))
    implementation(project(":core-vfs"))
    implementation(project(":core-index"))
    implementation(libs.androidx.work)
    // LGPL-2.1: linked as an unmodified binary dependency, never vendored.
    implementation(libs.sora.editor)
    // Pure Java, ~300 KB, no NDK and no ABI splits - which is why Lua and not Python.
    implementation(libs.luaj)
    // M6: rebuild and re-sign on device. ARSCLib reads and writes AXML with no aapt2.
    implementation(libs.smali.assembler)
    implementation(libs.apksig)
    implementation(libs.arsclib)
    // QR for the share URL. Core only: no camera, no Android glue.
    implementation(libs.zxing)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jsonTest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
