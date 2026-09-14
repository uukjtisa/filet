plugins {
    alias(libs.plugins.android.library)
}

/**
 * The only native code in Filet, and it exists for one reason.
 *
 * Every RAR decoder published for the JVM descends from RARLAB's UnRAR source, whose licence
 * forbids using it to build a RAR-compatible archiver. That is a field-of-use restriction and
 * GPL-3 section 7 does not permit one to be added, so shipping it here would be an actual
 * licence violation. libarchive's RAR readers are independent of that source and BSD-2-Clause,
 * which GPL-3 can take - but they are C, so Filet grows an NDK module.
 *
 * See core-native/README.md for what is vendored and what was deliberately left out.
 */
android {
    namespace = "dev.niccc2007.filet.nativerar"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                // C only. Nothing here needs the C++ runtime, and not linking libc++ keeps
                // roughly 700 KB per ABI out of the APK.
                arguments += listOf("-DANDROID_STL=none")
                cFlags += listOf("-Os")
            }
        }
        ndk {
            // The four Android still ships. armeabi-v7a is the one that costs the least to
            // keep and the most to drop: it is every phone from before about 2017.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
