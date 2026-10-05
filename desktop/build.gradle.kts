import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

/**
 * The desktop tool's own version, on its own release line.
 *
 * Not the app's. `docs/RELEASES.md` sets out why: the two are different products on different
 * schedules, the tool tags `filet-desktop-mount-tool-vX.Y.Z`, and `ReleaseTags.kt` filters the
 * phone's updater on that shape so a desktop release can never be offered to a phone.
 */
val toolVersion = "0.1.0"

version = toolVersion

/**
 * 21, not the 17 the shared modules target.
 *
 * Those two are consumed by an Android build and are pinned to its floor. Nothing consumes this
 * module, so it takes the JVM it is packaged with - and `jpackage`, which is how this ships to a
 * machine that has never had a JVM on it, wants a recent one.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // The protocol and the server, the same copy the phone runs.
    implementation(project(":core-dav"))
    implementation(project(":core-vfs-jvm"))

    implementation(compose.desktop.currentOs)
    // Compose Desktop draws on Swing's event thread, so that thread is this module's `Main`
    // dispatcher. Without it `Dispatchers.Main` has no implementation and throws on first use.
    implementation(libs.kotlinx.coroutines.swing)

    // For real here, not `compileOnly`. `:core-dav` leaves `org.json` unimplemented because
    // `android.jar` supplies it on the phone; a desktop JVM supplies nothing, so the shared
    // share codec would throw `NoClassDefFoundError` the first time a share list was saved.
    implementation(libs.json)

    // mDNS. Both halves of the tool: advertising what this PC offers, and listening for phones.
    implementation(libs.jmdns)

    // The native mount calls. See `mount/` for which symbol each OS needs.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "dev.niccc2007.filet.desktop.MainKt"

        nativeDistributions {
            /**
             * What `jpackage` is allowed to build here.
             *
             * `packageDistributionForCurrentOS` picks the one that matches, and each needs a
             * tool the OS does not ship by default - WiX for MSI, `dpkg-deb` for DEB. So the
             * build scripts default to `createDistributable`, which needs nothing but the JDK
             * and produces a runnable folder; the installer is the step after that.
             *
             * **DMG is deliberately absent, and it is not an oversight.** Apple requires a
             * bundle version with a major of at least 1, so `0.1.0` is rejected outright at
             * configuration time - the only ways to add it are to lie about the version on one
             * platform or to ship a 1.0 nobody meant. Neither is worth doing for a format that
             * cannot be built or tested here anyway. It goes in when there is a Mac to run it
             * on, with a mapped bundle version and a note saying what the mapping is.
             */
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)

            packageName = "Filet Desktop"
            packageVersion = toolVersion
            description = "Offer folders from this PC to Filet on your phone, and mount what the phone offers back."
            vendor = "Niccc2007"
            // GPL-3, the same as the rest of the repository. `:core-dav` is linked, so it could
            // not be anything else even if that were wanted.
            licenseFile.set(rootProject.file("LICENSE"))

            /**
             * The modules `jlink` has to keep.
             *
             * A packaged distribution carries a cut-down runtime, and `jpackage` cannot see
             * which modules are reached reflectively. Each of these is reached that way:
             *
             *  - `java.naming` - JmDNS
             *  - `java.instrument`, `jdk.unsupported` - Compose and JNA's native loader
             *  - `java.prefs` - where the autostart check and the config path are read from
             *  - `java.sql` - Compose's resource loading pulls it in on Windows
             *
             * Leaving one out produces a distribution that starts and then fails at the moment
             * the feature is used, which is the worst shape of failure available here.
             */
            modules("java.naming", "java.instrument", "jdk.unsupported", "java.prefs", "java.sql")

            windows {
                // Spaces are fine in the Start-menu name but not in a shortcut's file name, and
                // an MSI upgrade is keyed on this UUID - change it and an upgrade installs a
                // second copy beside the first rather than replacing it.
                upgradeUuid = "8E3C4C2A-6A5E-4D13-9B0F-7A2D1C4E5F60"
                menuGroup = "Filet"
                shortcut = true
                dirChooser = true
            }
            linux {
                packageName = "filet-desktop"
                menuGroup = "Utility"
            }
            macOS {
                bundleID = "dev.niccc2007.filet.desktop"
            }
        }
    }
}
