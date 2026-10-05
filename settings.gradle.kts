pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Bundled SQLite (requery) and libsu publish here only. Pinned to exact versions
        // below, because JitPack builds on demand and an unpinned range is not reproducible.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Filet"
include(":app")
// The part of the VFS that is plain JVM - the router, the path type, the provider interface and
// the local-disk provider. Split out of :core-vfs so the desktop tool can hold the same objects
// the phone does rather than a second implementation of them. :core-vfs still re-exports it, so
// nothing above L0 can tell the two apart.
include(":core-vfs-jvm")
// WebDAV: the protocol and the server. Also plain JVM, for the same reason - both ends of a
// mount now speak through one copy of it, so they cannot drift.
include(":core-dav")
include(":core-vfs")
include(":core-index")
include(":core-native")
// The PC app. Compose Desktop, its own release line (`filet-desktop-mount-tool-vX.Y.Z`), and it
// is not built by the Android tasks - `:desktop:run` and `:desktop:packageDistributionForCurrentOS`
// are its own.
include(":desktop")
