package dev.niccc2007.filet.pick

/**
 * What Filet hands back to the app that asked, described before any of it becomes an `Intent`.
 *
 * Separated from the Android side so the shape can be tested on the JVM, because the shape is
 * where pickers go wrong and none of it throws when it does. A caller that gets the wrong shape
 * back sees "nothing was picked", which is indistinguishable from a cancel, and the bug is then
 * in somebody else's app where nobody can see it.
 *
 * @param uris one entry per picked file, in the order they were chosen.
 * @param useClipData whether the result also carries a `ClipData`. True whenever more than one
 *   file is returned.
 */
data class PickAnswer(
    val uris: List<String>,
    val useClipData: Boolean,
) {
    /**
     * The URI that goes in `setData`.
     *
     * Set even for a multiple pick, and that is the point rather than an accident: plenty of
     * callers set `EXTRA_ALLOW_MULTIPLE` and then read only `getData()`. Handing those nothing
     * is a silent failure. A caller that understands `ClipData` reads the clip and ignores this;
     * a caller that does not gets the first file instead of nothing.
     */
    val primary: String? get() = uris.firstOrNull()

    val isEmpty: Boolean get() = uris.isEmpty()

    companion object {
        /**
         * Build the answer for a set of picked files.
         *
         * @param uris already-grantable URIs, in pick order. Anything that could not be turned
         *   into one has been dropped by the caller - a URI the other app cannot open is worse
         *   than a shorter list, because it fails after the picker has closed.
         */
        fun of(uris: List<String>, request: PickRequest): PickAnswer {
            // A single-pick request that somehow collected several takes the first rather than
            // returning a clip the caller never asked for and will not read.
            val kept = if (request.allowMultiple) uris else uris.take(1)
            return PickAnswer(uris = kept, useClipData = kept.size > 1)
        }
    }
}

/**
 * Whether a selection can be handed over at all, and what to say when it cannot.
 *
 * Every sentence here is one somebody reads instead of getting a file, so each says what to do
 * next rather than only what went wrong.
 */
object PickRules {

    /**
     * @param anyDirectories whether the selection contains a folder.
     * @param count how many things are selected.
     * @return the reason the pick cannot be returned, or null when it can.
     */
    fun refusal(request: PickRequest, count: Int, anyDirectories: Boolean): String? = when {
        count == 0 -> "Choose a file first."
        // CATEGORY_OPENABLE means the caller will open a stream on what it gets, and a folder
        // answers that with an exception inside the other app. Refused here, where the sentence
        // can be read, rather than there, where it cannot.
        anyDirectories && request.openable ->
            "A folder cannot be handed to the app that asked — it wants a file it can open."
        anyDirectories ->
            "Folders cannot be picked. Open it and choose a file inside."
        !request.allowMultiple && count > 1 ->
            "That app asked for one file. Choose a single file."
        else -> null
    }
}
