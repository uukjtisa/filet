package dev.niccc2007.filet.desktop.update

/**
 * This copy's version and whether it can update itself.
 *
 * The version comes from a resource the build writes from `toolVersion`, so a jar run and an
 * installed copy report the same number; the launcher's own `jpackage.app-version` is the
 * fallback. Only an installed copy can update: a jar run has no installer to replace, and the
 * updater offers the release page instead.
 */
object Version {
    val current: String by lazy {
        runCatching { Version::class.java.getResource("/filet-desktop.version")?.readText()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }
            ?: "0"
    }

    /** The installed launcher, or null when this is a build run from a jar. */
    val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { java.io.File(it).isFile }
}
