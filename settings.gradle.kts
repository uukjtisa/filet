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
include(":core-vfs")
include(":core-index")
include(":core-native")
