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
 * schedules, the tool tags `filet-desktop-network-tool-vX.Y.Z`, and `ReleaseTags.kt` filters the
 * phone's updater on that shape so a desktop release can never be offered to a phone.
 */
val toolVersion = "0.2.0"

version = toolVersion

/**
 * The version as a resource, so a jar run knows it too and the updater compares against the
 * number this build was given rather than one read from a launcher that may not exist.
 */
val versionResource = tasks.register("versionResource") {
    val out = layout.buildDirectory.dir("generated/version")
    val v = toolVersion
    inputs.property("version", v)
    outputs.dir(out)
    doLast { out.get().file("filet-desktop.version").asFile.apply { parentFile.mkdirs(); writeText(v) } }
}
sourceSets.main { resources.srcDir(versionResource) }

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

    // A share's QR, for the phone's scanner. zxing's core, the encoder the phone draws its own
    // share codes with - pure Java, no camera or platform code.
    implementation(libs.zxing)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "dev.niccc2007.filet.desktop.MainKt"

        /**
         * The JDK `jlink` and `jpackage` come from, as a toolchain and not a path.
         *
         * Left alone it is the JVM Gradle runs on, and from Android Studio that is its JBR - a JDK
         * 21 with no `jpackage`, so every installer task stopped at `checkRuntime`. Adoptium by
         * vendor, because "any 21" matches the JBR again; the foojay resolver in settings fetches
         * it where it is not installed. A machine that cannot resolve one at all (offline, nothing
         * installed) keeps the old default, so tests and the jar still build there - only the
         * installers need it.
         */
        runCatching {
            javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(21)
                vendor = JvmVendorSpec.ADOPTIUM
            }.get().metadata.installationPath.asFile.absolutePath
        }.getOrNull()?.let { javaHome = it }

        nativeDistributions {
            /**
             * What `jpackage` is allowed to build here.
             *
             * `packageDistributionForCurrentOS` picks the one that matches, and each needs a
             * tool the OS does not ship by default - WiX for EXE and MSI, `dpkg-deb` for DEB. So the
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
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Deb)

            packageName = "Filet Desktop Network Tool"
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
             *  - `java.net.http` - the mount probe. `HttpURLConnection` rejects PROPFIND, so
             *    the pre-mount check uses `HttpClient`; leaving this out does not degrade the
             *    probe, it makes EVERY packaged mount fail as "could not be reached", because
             *    the probe catches `NoClassDefFoundError` and reports unreachable.
             *
             * Leaving one out produces a distribution that starts and then fails at the moment
             * the feature is used, which is the worst shape of failure available here.
             */
            modules(
                "java.naming", "java.instrument", "jdk.unsupported", "java.prefs", "java.sql",
                "java.net.http",
            )

            windows {
                // Spaces are fine in the Start-menu name but not in a shortcut's file name, and
                // an MSI upgrade is keyed on this UUID - change it and an upgrade installs a
                // second copy beside the first rather than replacing it.
                upgradeUuid = "8E3C4C2A-6A5E-4D13-9B0F-7A2D1C4E5F60"
                menuGroup = "Filet"
                // The phone's folder mark at every size Explorer asks for; see icons/make_icons.py.
                iconFile.set(project.file("icons/filet.ico"))
                shortcut = true
                /**
                 * Per user, in the profile, not Program Files.
                 *
                 * Decision: an app that updates itself must be able to replace its own files, and
                 * under Program Files every update needed an administrator - a UAC prompt for each
                 * build installed here and each release from GitHub. In the profile no update asks
                 * for anything, as with the user installers of VS Code or Discord. No folder choice
                 * either: an upgrade installs to the default folder, so a chosen one would be left
                 * behind with the old copy in it.
                 */
                perUserInstall = true
                dirChooser = false
            }
            linux {
                packageName = "filet-desktop-network-tool"
                iconFile.set(project.file("src/main/resources/filet.png"))
                menuGroup = "Utility"
            }
            macOS {
                bundleID = "dev.niccc2007.filet.desktop"
            }
        }
    }
}

/**
 * Installs each installer this machine builds over the copy installed here, so a build is never
 * left waiting for someone to find it and run it.
 *
 * Runs after `packageMsi` or `packageExe` on Windows, from the MSI: it closes Filet Desktop Network Tool if it
 * is open, replaces the per-user install, and opens it again, sharing again if it was sharing.
 * See packaging/install-here.ps1. Never on CI (`CI` is set there), and `-PnoInstall` skips it.
 */
val installHere = tasks.register<Exec>("installHere") {
    group = "distribution"
    description = "Installs the MSI just built over the copy installed on this machine."
    dependsOn("packageMsi")
    val skip = providers.environmentVariable("CI").isPresent || providers.gradleProperty("noInstall").isPresent ||
        !System.getProperty("os.name").startsWith("Windows")
    onlyIf("on Windows, not CI, not -PnoInstall") { !skip }
    val msiDir = layout.buildDirectory.dir("compose/binaries/main/msi")
    val script = layout.projectDirectory.file("packaging/install-here.ps1").asFile
    val v = toolVersion
    doFirst {
        val msi = msiDir.get().asFile.listFiles { f -> f.name.endsWith("-$v.msi") }?.singleOrNull()
            ?: throw GradleException("No MSI for $v in ${msiDir.get().asFile}")
        commandLine("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.path, "-Msi", msi.path)
    }
}
tasks.matching { it.name == "packageMsi" || it.name == "packageExe" }.configureEach { finalizedBy(installHere) }

