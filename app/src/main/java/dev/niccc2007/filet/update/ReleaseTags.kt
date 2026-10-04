package dev.niccc2007.filet.update

/**
 * Which release line a tag belongs to.
 *
 * ## Why one repository has two release histories
 *
 * The app and the desktop companion tool live in the same repository and ship on different
 * schedules: a fix to the phone app has nothing to do with the desktop mount tool, and making
 * one wait for the other would either hold up a fix or ship a version of the tool that changed
 * nothing. So the tags are shaped differently and a GitHub release list shows them as two
 * interleaved lines rather than one confused one:
 *
 *  - the app      `v0.1.11`
 *  - the tool     `filet-desktop-mount-tool-v0.1.0`
 *
 * ## The bug this exists to have already prevented
 *
 * The updater used to take the highest version across **every** release in the repository, and
 * [Version.parse] searches rather than matches - so `filet-desktop-mount-tool-v0.2.0` parses as
 * 0.2.0, beats the app's 0.1.11, and wins. Everybody on the phone would then be offered an
 * update whose release carries a JAR and no APK, and told "that release has no APK attached".
 *
 * That is a bug that cannot be found by testing the app, because it only appears on the day a
 * second release line starts. It is fixed here, before there is anything to tag.
 */
object ReleaseTags {

    /** The prefix the desktop tool's tags carry. */
    const val DESKTOP_PREFIX = "filet-desktop-mount-tool-"

    /**
     * The app's own tags: a bare dotted version, optionally with a leading `v` and a
     * pre-release suffix. Anchored at both ends on purpose - a prefix in front of the number is
     * precisely what distinguishes another product's tag from this one's.
     */
    private val APP = Regex("""^v?\d+\.\d+(\.\d+)?([-+].+)?$""")

    private val DESKTOP = Regex("""^${Regex.escape(DESKTOP_PREFIX)}v?\d+\.\d+(\.\d+)?([-+].+)?$""")

    enum class Line {
        /** The Android app. What the in-app updater follows. */
        APP,

        /** The Kotlin/JVM desktop companion, which runs wherever a JVM does. */
        DESKTOP,

        /** Neither shape. Reported rather than ignored: a typo'd tag is invisible otherwise. */
        UNKNOWN,
    }

    fun lineOf(tag: String?): Line {
        val t = tag.orEmpty().trim()
        return when {
            DESKTOP.matches(t) -> Line.DESKTOP
            APP.matches(t) -> Line.APP
            else -> Line.UNKNOWN
        }
    }

    /** Whether this tag is one the phone's updater should ever consider. */
    fun isApp(tag: String?): Boolean = lineOf(tag) == Line.APP

    fun isDesktop(tag: String?): Boolean = lineOf(tag) == Line.DESKTOP

    /** The tag for a given desktop version, so the shape is written in exactly one place. */
    fun desktopTag(version: String): String =
        DESKTOP_PREFIX + if (version.startsWith("v")) version else "v$version"
}
