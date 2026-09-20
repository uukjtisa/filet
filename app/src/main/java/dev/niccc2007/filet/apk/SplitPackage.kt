package dev.niccc2007.filet.apk

/**
 * Choosing which pieces of a split app bundle to install.
 *
 * An `.xapk`, `.apkm` or `.apks` is a zip holding one **base** APK plus a set of **split**
 * APKs: one per CPU architecture, one per screen density, one per language, and sometimes
 * feature modules. Android installs them through a `PackageInstaller` session with every
 * chosen piece written into it. The system installer that opens for a plain `.apk` cannot take
 * a bundle at all, which is why these three formats opened in Filet, looked supported, and
 * could not be installed.
 *
 * ## Why this is a tested function and not a filter inline
 *
 * Getting the choice wrong does not fail at install time. It **succeeds**, and the app then
 * crashes on launch with a missing native library, or renders at the wrong density, or comes
 * up in the wrong language. The failure arrives long after the action that caused it, which is
 * exactly the kind of decision that belongs somewhere it can be checked.
 *
 * Three rules do the work:
 *
 *  - **ABI is the one that must be exact.** A device's supported ABIs are ordered by
 *    preference, so the first match wins. Installing `armeabi-v7a` on an `arm64-v8a` device
 *    works; installing `x86_64` on either does not, and nothing says so until launch.
 *  - **Density is nearest-wins, never dropped.** A missing density split leaves an app with no
 *    drawables rather than blurry ones.
 *  - **Anything unrecognised is included.** Feature modules, and any naming convention this
 *    does not know about, are installed rather than silently discarded. A bundle that installs
 *    with a piece missing is worse than one that carries a few kilobytes it did not need.
 */
object SplitPackage {

    /** Entries that are part of the package itself rather than payload. */
    private val METADATA = setOf("manifest.json", "info.json", "icon.png", "meta.sai_v1.json", "meta.sai_v2.json")

    /** Densities Android names, smallest first, so "nearest" has an order to walk. */
    private val DENSITIES = listOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

    /**
     * @param entries every file name inside the bundle, as stored (paths allowed).
     * @param abis the device's supported ABIs, **in preference order** (`Build.SUPPORTED_ABIS`).
     * @param density the device's density bucket, e.g. `"xxhdpi"`.
     * @param languages the device's language tags, e.g. `["en", "tl"]`.
     * @return the entries to write into the install session, base first.
     */
    fun pick(
        entries: List<String>,
        abis: List<String>,
        density: String,
        languages: List<String>,
    ): List<String> {
        val apks = entries.filter { it.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return emptyList()

        val base = apks.filter { isBase(it) }
        val rest = apks - base.toSet()

        val abiSplits = rest.filter { abiOf(it) != null }
        val densitySplits = rest.filter { densityOf(it) != null }
        val langSplits = rest.filter { languageOf(it) != null }
        val other = rest - abiSplits.toSet() - densitySplits.toSet() - langSplits.toSet()

        val chosenAbi = abis.firstNotNullOfOrNull { want ->
            abiSplits.firstOrNull { abiOf(it).equals(want, ignoreCase = true) }
        }
        val chosenDensity = nearestDensity(densitySplits, density)
        val chosenLangs = langSplits.filter { split ->
            languages.any { it.equals(languageOf(split), ignoreCase = true) }
        }.ifEmpty {
            // No match is not a reason to ship an app with no strings. Prefer English, and
            // failing that take whatever the first one is.
            langSplits.filter { languageOf(it).equals("en", ignoreCase = true) }
                .ifEmpty { langSplits.take(1) }
        }

        return base + listOfNotNull(chosenAbi, chosenDensity) + chosenLangs + other
    }

    /**
     * The base APK inside a bundle, or null if there is not one.
     *
     * The base is the only piece that carries the manifest the inspector shows - the label,
     * the version, the permissions, the signature. The splits beside it hold resources for one
     * architecture, density or language and answer none of those questions.
     *
     * The first, when several qualify: [pick] already orders bases first and a bundle with two
     * of them is malformed, so taking the first is a choice rather than an accident.
     */
    fun baseOf(entries: List<String>): String? =
        entries.filter { it.endsWith(".apk", ignoreCase = true) }.firstOrNull { isBase(it) }

    /** Whether a bundle needs a session install rather than the system installer. */
    fun isSplitBundle(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("xapk", "apkm", "apks")

    /** Files in the bundle that are not APKs and not bundle metadata - OBBs, mostly. */
    fun extras(entries: List<String>): List<String> = entries.filter {
        !it.endsWith(".apk", ignoreCase = true) && it.substringAfterLast('/') !in METADATA
    }

    private fun isBase(name: String): Boolean {
        val leaf = name.substringAfterLast('/').lowercase()
        // "base.apk" is the convention, but a bundle whose only APK carries the package name is
        // just as much the base - APKMirror and SAI both produce that shape.
        return leaf == "base.apk" || (!leaf.startsWith("split_") && !leaf.startsWith("config."))
    }

    private fun tag(name: String): String? {
        val leaf = name.substringAfterLast('/').removeSuffix(".apk").removeSuffix(".APK")
        return when {
            leaf.startsWith("split_config.") -> leaf.removePrefix("split_config.")
            leaf.startsWith("config.") -> leaf.removePrefix("config.")
            // "<package>.config.arm64_v8a" is the third shape in the wild.
            leaf.contains(".config.") -> leaf.substringAfter(".config.")
            else -> null
        }
    }

    /** Split names use underscores where an ABI uses hyphens: `arm64_v8a` is `arm64-v8a`. */
    private fun abiOf(name: String): String? {
        val t = tag(name) ?: return null
        val abi = t.replace('_', '-')
        return abi.takeIf { it in setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86-64", "x86_64", "mips") }
            ?: t.takeIf { it == "x86_64" }?.let { "x86_64" }
    }

    private fun densityOf(name: String): String? = tag(name)?.takeIf { it in DENSITIES }

    private fun languageOf(name: String): String? =
        tag(name)?.takeIf { it.length in 2..3 && it.all { c -> c.isLetter() } && it !in DENSITIES }

    /**
     * The closest density available, never null when any density split exists.
     *
     * Walking outward from the device's own bucket rather than taking the largest: the largest
     * is several megabytes of drawables the screen cannot show, and the smallest looks soft.
     */
    private fun nearestDensity(splits: List<String>, want: String): String? {
        if (splits.isEmpty()) return null
        val byName = splits.associateBy { densityOf(it)!! }
        byName[want]?.let { return it }
        val at = DENSITIES.indexOf(want)
        if (at < 0) return splits.first()
        for (step in 1 until DENSITIES.size) {
            byName[DENSITIES.getOrNull(at + step)]?.let { return it }
            byName[DENSITIES.getOrNull(at - step)]?.let { return it }
        }
        return splits.first()
    }
}
