package dev.niccc2007.filet.nearby

import dev.niccc2007.filet.vfs.VPath

/**
 * Whether a browser holding a token may walk into [path].
 *
 * Pulled out of `SharedSet` because it is a decision that can be wrong, and it was: the
 * quarantine folder was refused by name even when Nic had deliberately shared it, so the
 * listing offered **Received** and opening it answered `{"error":"gone"}`. From a browser that
 * reads as "shared folders are not explorable", which is how he reported it.
 *
 * Offered in one place and refused in another is rule R1 wearing a different coat, and it is
 * only visible from the far end of a network. So the rule lives here, as a function over four
 * values, with a test - rather than inside a class that needs a `Context` and a live `Vfs` to
 * instantiate.
 *
 * ## The rule, in order
 *
 * 1. **An explicit share wins.** If the owner put this path in the shared set, or it sits
 *    inside something they put there, it is reachable. That is a deliberate act by the person
 *    holding the phone and nothing below may override it.
 * 2. **The quarantine subtree is refused.** Uploads land there. Someone who can upload must
 *    not thereby be able to read back what everybody else uploaded, so the folder is not
 *    reachable *by default* - and neither is anything under it, which the old check missed.
 * 3. **The real shared folder is reachable**, along with everything beneath it. That is what
 *    the folder is for.
 * 4. Anything else is refused.
 *
 * The order is the whole design. Putting the quarantine refusal first - which is what the code
 * did - makes it an override of the owner's decision instead of a default.
 */
object ShareReach {

    /**
     * @param path where the browser is trying to go.
     * @param folder the real shared folder.
     * @param quarantine where accepted uploads land.
     * @param sharedPaths every path explicitly put in the shared set.
     */
    fun isReachable(
        path: VPath,
        folder: VPath,
        quarantine: VPath,
        sharedPaths: Collection<VPath>,
    ): Boolean {
        // 1. An explicit share, or something inside one. Checked FIRST and deliberately so.
        if (sharedPaths.any { it == path || it.contains(path) }) return true

        // 2. The quarantine subtree, unless step 1 already allowed it. `contains` rather than
        //    equality: the old check only refused the folder itself, so had the quarantine ever
        //    been placed inside the shared folder, every upload in it would have been readable
        //    through step 3 below.
        if (path == quarantine || quarantine.contains(path)) return false

        // 3. The shared folder and its subtree.
        return path == folder || folder.contains(path)
    }
}
