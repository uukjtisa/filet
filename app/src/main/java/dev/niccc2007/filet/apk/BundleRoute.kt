package dev.niccc2007.filet.apk

/**
 * What tapping an app package should do, and where Install can be reached from.
 *
 * Bug identified: installing a split bundle was built and was unreachable. `openNode` routed
 * on `node.extension == "apk"` alone, so `.xapk`, `.apkm` and `.apks` fell through to the
 * archive branch - they are zips, so `Archives.canList` claims them - and mounted as folders.
 * `installApk` knew how to install them and the only door into it was the APK inspector, which
 * a bundle never reached. Every piece worked and nothing connected them, which is rule R1 in
 * the same shape as the tab strip that could not be dragged.
 *
 * Two separate questions, and conflating them is what caused the hole:
 *
 *  - **What does a tap open?** For a bundle, the archive browser. Looking inside one is a
 *    reasonable thing to want, and a dedicated bundle inspector is a later piece of work.
 *  - **Can this be installed?** For a bundle, yes - so Install is offered wherever actions on
 *    a file are offered, rather than only on the one screen a bundle cannot get to.
 */
object BundleRoute {

    /** The split bundle formats: a base APK plus its per-ABI, per-density and per-language parts. */
    val BUNDLE_EXTENSIONS = setOf("xapk", "apkm", "apks")

    /** Where a tap goes. */
    enum class Tap {
        /** The APK inspector, which reads a manifest and offers Install. */
        INSPECT,

        /** The archive browser, for looking inside. */
        MOUNT,

        /** Not an app package; somebody else's problem. */
        OTHER,
    }

    private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** A single APK. */
    fun isApk(name: String): Boolean = ext(name) == "apk"

    /** One of the split bundle formats. */
    fun isBundle(name: String): Boolean = ext(name) in BUNDLE_EXTENSIONS

    /**
     * Whether Install should be offered for this file.
     *
     * The question the old routing never asked. An extension check rather than a magic-byte
     * one on purpose: the platform installer reads the real contents and rejects anything that
     * is not a package, so a wrong guess here costs a refusal rather than a bad install - and
     * refusing to OFFER install on a correctly named bundle is the failure that shipped.
     */
    fun installable(name: String): Boolean = isApk(name) || isBundle(name)

    /**
     * Where a tap on [name] should go.
     *
     * A bundle mounts rather than inspecting, because the inspector reads a single APK's
     * manifest and a bundle has several. That inspector is a later piece of work; until it
     * exists, mounting is honest and Install is reachable from the menu instead.
     */
    fun tap(name: String, isDir: Boolean): Tap = when {
        isDir -> Tap.OTHER
        isApk(name) -> Tap.INSPECT
        isBundle(name) -> Tap.MOUNT
        else -> Tap.OTHER
    }
}
