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

/**
 * Where "Report a problem" posts, from `filet.reportEndpoint` in local.properties, or blank.
 *
 * A build setting rather than a constant in the source, so the address can be set once the
 * backend is deployed without editing code, and a local test server can be pointed at for a
 * debug build. It is a URL, not a credential: the GitHub token lives only on the server.
 */
val reportEndpoint: String = rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { FileInputStream(f).use { load(it) } }.getProperty("filet.reportEndpoint")
}?.trim().orEmpty()

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
                // The certificate's subject is PUBLIC - it travels in every signed APK and
                // anyone can read it with `apksigner verify --print-certs`. Verified on the
                // 0.1.13 release build: `CN=Niccc2007, OU=Filet, O=uukjtisa, C=PH`. The
                // display name and the handle, and no legal name anywhere in it, which is
                // what it should be and is recorded here because a certificate DN is the kind
                // of public surface that gets audited late and cannot be changed afterwards.
                //
                // Any new app's keystore is generated the same way, with the handle, for the
                // same reason the key above cannot be replaced: a DN is fixed at generation
                // and a new one is a new identity, so getting it wrong is permanent.
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
        versionCode = 21
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "REPORT_ENDPOINT", "\"" + reportEndpoint.replace("\"", "") + "\"")
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
    // Hosting this phone over WebDAV. In its own module because the desktop tool serves the
    // same protocol from the same code - a second implementation of PROPFIND is a second set
    // of the corners this one already has tests for.
    implementation(project(":core-dav"))
    implementation(project(":core-index"))
    implementation(libs.androidx.work)
    // LGPL-2.1: linked as an unmodified binary dependency, never vendored.
    implementation(libs.sora.editor)
    // Pure Java, ~300 KB, no NDK and no ABI splits - which is why Lua and not Python.
    implementation(libs.luaj)
    // Markdown. BSD-2-Clause, pure Java, and the reference implementation of the spec -
    // which matters because the alternative was a hand-rolled subset that would get tables,
    // nested lists and fenced code inside a list item wrong in different ways for years.
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.alerts)
    implementation(libs.commonmark.footnotes)
    implementation(libs.commonmark.tasklist)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.frontmatter)
    // Pictures in documents: local ones through the VFS, web ones through HttpURLConnection -
    // see docs/DocPictures.kt. SVG for the badges every README starts with.
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.network)
    // SVG drawings in the picture viewer - see handlers/SvgPicture.kt.
    implementation(libs.androidsvg)
    // Syntax colours for code blocks.
    implementation(libs.highlights)
    // M6: rebuild and re-sign on device. ARSCLib reads and writes AXML with no aapt2.
    implementation(libs.smali.assembler)
    implementation(libs.apksig)
    implementation(libs.arsclib)
    // QR for the share URL. Core only: no camera, no Android glue.
    implementation(libs.zxing)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
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
