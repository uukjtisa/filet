plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.niccc2007.filet"
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
        }
        debug {
            // A different package, so a development build cannot see the release Trawl and
            // vice versa. Bridge discovery enumerates both names (PLAN.md §5.4).
            applicationIdSuffix = ".debug"
        }
    }

    /**
     * Distribution flavours (PLAN.md §4).
     *
     * The updater is a build concern, not a runtime setting: F-Droid handles updates itself
     * and objects to self-updaters, so REQUEST_INSTALL_PACKAGES must not even appear in that
     * manifest.
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
