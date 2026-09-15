package dev.niccc2007.filet.script

/**
 * The script list, as a value.
 *
 * Nic: *"the script tab.. fix it.. I keep editing the parameters of the script and it keeps it
 * stale unless I refresh"*.
 *
 * Two separate causes, both of which are list arithmetic rather than UI:
 *
 * 1. **Saving appended.** `filterNot { it.id == fileId } + script` put an edited script at the
 *    end of the list whatever its name was, so editing anything moved it to the bottom and a
 *    refresh moved it back. Reads exactly like a stale list.
 * 2. **Approving emitted nothing.** Approval lived only in `SharedPreferences`, which no
 *    `StateFlow` observes, and re-publishing the list did not help either: `StateFlow` drops a
 *    value that `equals` the one it already holds, so a rebuilt-but-identical list is silently
 *    swallowed. The row kept its old label until something unrelated changed the list.
 *
 * Both live here because both are decisions that can be wrong silently, and neither needs a
 * `Context` to be checked.
 */
object ScriptList {

    /** The one ordering. By name, case-insensitively, so it matches what a reader expects. */
    fun inOrder(scripts: List<Script>): List<Script> = scripts.sortedBy { it.name.lowercase() }

    /**
     * Put [script] into [scripts], replacing any entry with the same id, and keep the order.
     *
     * The ordering is applied to the RESULT rather than assumed of the input, so a save can
     * never leave the list unsorted regardless of how it got that way.
     */
    fun replacing(scripts: List<Script>, script: Script): List<Script> =
        inOrder(scripts.filterNot { it.id == script.id } + script)

    /**
     * Flip one script's approval.
     *
     * Returns a list that is genuinely unequal to the input when something changed, which is
     * the property the UI depends on and the one that was missing.
     */
    fun withApproval(scripts: List<Script>, id: String, approved: Boolean): List<Script> =
        scripts.map { if (it.id == id) it.copy(approved = approved) else it }
}
