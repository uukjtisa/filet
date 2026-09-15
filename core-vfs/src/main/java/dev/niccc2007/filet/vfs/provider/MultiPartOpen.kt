package dev.niccc2007.filet.vfs.provider

import java.io.File
import java.io.IOException

/**
 * Opening one part of a multi-part archive as the whole thing.
 *
. This
 * is the reading half, and the user-facing rule is simple - **tapping any part opens the
 * archive**. Tapping part 3 and getting "corrupt" is the behaviour this removes.
 *
 * The three schemes need three different answers, and pretending otherwise is the bug:
 *
 * | Set | What actually opens it |
 * |---|---|
 * | RAR (`.partN.rar`, or `.rar` + `.r00`) | hand libarchive the FIRST volume; it follows the rest itself |
 * | Zip volumes (`.z01` … `.zip`) | hand the reader the final `.zip`, which holds the central directory |
 * | Numbered (`.7z.001`, `.tar.gz.002`) | a plain byte split - the parts have to be JOINED first |
 *
 * Only the third costs anything, and it costs a copy. There is no way around that with a
 * reader that seeks: the parts are meaningless individually and the original file is their
 * concatenation. So it is done once into a temp file, and above [JOIN_CAP] it is refused with
 * a sentence rather than attempted, because silently copying four gigabytes on a phone to open
 * a listing is not a kindness.
 */
object MultiPartOpen {

    /** The largest numbered set that is joined automatically. */
    const val JOIN_CAP: Long = 2L * 1024 * 1024 * 1024

    /**
     * What to do about [file].
     *
     * @param join when non-null, these parts in order must be concatenated to make the archive.
     * @param openWith the file to hand the reader when no join is needed.
     * @param missing parts that are provably absent, named so somebody knows what to fetch.
     */
    data class Resolution(
        val setName: String?,
        val style: PartStyle?,
        val openWith: File,
        val join: List<File>? = null,
        val missing: List<String> = emptyList(),
        val refusal: String? = null,
    ) {
        val isSet: Boolean get() = style != null
    }

    /**
     * Work out how to open [file], given whatever else sits beside it.
     *
     * A file that is not part of a set resolves to itself, so every caller can go through here
     * without asking first.
     */
    fun resolve(file: File): Resolution {
        val siblings = file.parentFile?.list()?.toList() ?: emptyList()
        val state = PartSets.stateOf(file.name, siblings)
            ?: return Resolution(null, null, file)

        val dir = file.parentFile ?: return Resolution(null, null, file)
        val parts = state.present.map { File(dir, it) }
        val missing = state.gaps

        if (missing.isNotEmpty()) {
            return Resolution(
                setName = state.setName,
                style = state.style,
                openWith = file,
                missing = missing,
                refusal = refusalFor(state.setName, state.present.size, missing),
            )
        }

        return when (state.style) {
            // libarchive opens a RAR set from its first volume and finds the rest by name.
            // Handing it part 3 is what produced "corrupt archive" before.
            PartStyle.RAR_PART, PartStyle.RAR_OLD ->
                Resolution(state.setName, state.style, parts.first())

            // The final `.zip` carries the central directory; the numbered volumes carry only
            // data. Opening a `.z01` directly finds no directory at all.
            PartStyle.ZIP_VOLUMES ->
                Resolution(state.setName, state.style, parts.last())

            PartStyle.NUMBERED -> {
                val total = parts.sumOf { it.length() }
                if (total > JOIN_CAP) {
                    Resolution(
                        setName = state.setName,
                        style = state.style,
                        openWith = file,
                        refusal = "This set is ${total / (1024 * 1024)} MB across ${parts.size} parts. " +
                            "Filet joins a numbered set to open it, and will not copy that much " +
                            "automatically - join the parts yourself first.",
                    )
                } else {
                    Resolution(state.setName, state.style, file, join = parts)
                }
            }
        }
    }

    private fun refusalFor(setName: String, have: Int, missing: List<String>): String {
        val shown = missing.take(3).joinToString(", ")
        val more = if (missing.size > 3) " and ${missing.size - 3} more" else ""
        return "$setName is incomplete: $have part(s) here, missing $shown$more."
    }

    /**
     * Concatenate [parts] into [target].
     *
     * Written to a temp file and only then moved into place, so an interrupted join never
     * leaves a short file that looks like a complete archive and fails somewhere in the middle
     * of a listing.
     */
    @Throws(IOException::class)
    fun join(parts: List<File>, target: File) {
        require(parts.isNotEmpty()) { "nothing to join" }
        val temp = File(target.parentFile, target.name + ".joining")
        try {
            temp.outputStream().buffered().use { out ->
                for (p in parts) p.inputStream().buffered().use { it.copyTo(out, 1 shl 16) }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                // Different filesystems, or a provider that will not rename. Copy and drop the
                // temp rather than failing the open outright.
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }
}
