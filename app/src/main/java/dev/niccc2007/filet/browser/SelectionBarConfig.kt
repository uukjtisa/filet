package dev.niccc2007.filet.browser

/**
 * Which selection actions sit on the bar, and which are behind More.
 *
 * ## Why the choice between a bar and a menu is gone
 *
 * There used to be a setting picking one of two renderings of the same list. It was a messy
 * question to ask - both answers were incomplete on their own. The bar could not hold fifteen
 * actions, so it scrolled sideways and the ones past the edge were invisible; the menu held
 * all fifteen but put every one of them two taps away, including the four anybody actually
 * uses. Neither was better, which is why the setting existed and why it was the wrong setting.
 *
 * One bar now, holding the actions worth one tap, with More opening the rest. The setting that
 * remains is the useful one: WHICH actions are worth one tap, which differs per person in a way
 * that bar-versus-menu never did.
 *
 * ## The invariant
 *
 * [split] partitions. Every live action lands on the bar or behind More, exactly once, never
 * both and never neither - an action that falls out of the partition is unreachable, and an
 * action in both places is two buttons that do the same thing. Both are silent failures, so the
 * partition is a pure function with a test rather than two filters written next to each other.
 */
object SelectionBarConfig {

    /**
     * Everything that may be put on the bar, with the words Settings uses for it.
     *
     * A fixed table rather than something derived from [selectionActions], because that
     * function needs a live selection and real callbacks to produce a list at all. A test
     * asserts this table and that function agree, which is the drift that would otherwise
     * appear as an action nobody can find.
     *
     * The extract actions are absent on purpose: they exist only when the selection is one
     * readable archive, so a permanent bar slot for them would be empty almost always.
     */
    val CHOOSABLE: List<Pair<String, String>> = listOf(
        "copy" to "Copy",
        "move" to "Move",
        "rename" to "Rename",
        "delete" to "Delete",
        "send" to "Send",
        "compress" to "Compress",
        "openwith" to "Open with",
        "details" to "Details",
        "bookmark" to "Bookmark",
        "nearby" to "Nearby",
        "shortcut" to "Shortcut",
        "install" to "Install",
    )

    val IDS: List<String> = CHOOSABLE.map { it.first }

    fun labelFor(id: String): String? = CHOOSABLE.firstOrNull { it.first == id }?.second

    /** What a selection is usually for. Anyone who never opens Settings sees this. */
    val DEFAULT = listOf("copy", "move", "rename", "delete")

    /**
     * Past this many there is no room for More, which is the one button that must never be
     * pushed off the edge - everything not on the bar is behind it.
     */
    const val MAX_ON_BAR = 5

    /**
     * Read a stored bar, dropping anything that no longer exists.
     *
     * Duplicates collapse rather than reject: two copies of one button is a bar that looks
     * broken, and a stored value can pick them up from a bad edit.
     */
    fun normalise(stored: String?): List<String> {
        val parsed = stored.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it in IDS }
            .distinct()
            .take(MAX_ON_BAR)
        // An empty bar is a mistake rather than a configuration - most likely every stored id
        // having been renamed. Falling back beats a strip with nothing in it.
        return parsed.ifEmpty { DEFAULT }
    }

    fun encode(ids: List<String>): String = ids.joinToString(",")

    /**
     * Add or remove one entry.
     *
     * Removing the last is refused: a bar with nothing on it is one More button and a count,
     * which reads as a failure to load. Adding past [MAX_ON_BAR] is refused too, rather than
     * silently dropping the oldest - a switch that turns itself back off is worse than one that
     * says no.
     */
    fun toggled(ids: List<String>, id: String): List<String> = when {
        id !in IDS -> ids
        id !in ids -> if (ids.size >= MAX_ON_BAR) ids else ids + id
        ids.size <= 1 -> ids
        else -> ids - id
    }

    fun full(ids: List<String>): Boolean = ids.size >= MAX_ON_BAR

    /**
     * Partition [all] into the ones on the bar and the ones behind More.
     *
     * @param onBar ids in the order they should appear. An id with no live action is skipped
     *   rather than drawn as a gap - `install` is only there for an APK, and a hole in the bar
     *   for every other selection would be worse than a shorter bar.
     *
     * Bar order follows [onBar], because that order was chosen. The rest keep the order
     * [selectionActions] produced, because that order carries the grouping.
     */
    fun split(
        all: List<SelectionAction>,
        onBar: List<String>,
    ): Pair<List<SelectionAction>, List<SelectionAction>> {
        val bar = onBar.mapNotNull { id -> all.firstOrNull { it.id == id } }
        val taken = bar.map { it.id }.toSet()
        return bar to all.filterNot { it.id in taken }
    }
}
