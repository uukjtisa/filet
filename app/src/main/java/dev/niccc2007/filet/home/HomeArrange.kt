package dev.niccc2007.filet.home

/**
 * What Home's edit state decides about the drives and the categories, kept pure so each rule is a
 * test rather than a drag on a phone.
 *
 * A drive is known here by its id: the stored form of its path (`local:///storage/emulated/0`),
 * the same string `home.hiddenCards` has always used, or one of the ids below for a tile that is
 * not a volume - the Termux offer, and one app's folders drawn as one card.
 */
internal object HomeArrange {

    /** How the drives are laid out: one wrapping row, or a row per [DriveKind]. */
    enum class Layout(val key: String, val label: String) {
        FLOW("flow", "One row"),
        GROUPED("grouped", "Grouped by kind"),
        ;

        companion object {
            fun of(raw: String?): Layout = entries.firstOrNull { it.key == raw } ?: FLOW
        }
    }

    /** Several folders granted from one app: a card each, or one card for the app. */
    enum class PerApp(val key: String, val label: String) {
        EACH("each", "A card each"),
        APP("app", "One per app"),
        ;

        companion object {
            fun of(raw: String?): PerApp = entries.firstOrNull { it.key == raw } ?: EACH
        }
    }

    /**
     * The groups of [Layout.GROUPED], in the order they are drawn.
     *
     * Decision: what is part of the phone first, then what was plugged into it, then what other
     * apps lend it, then what is reached over the network - nearest to farthest, which is also
     * the order of how reliably each is there.
     */
    enum class DriveKind(val title: String) {
        PHONE("On this phone"),
        PLUGGED("Plugged in"),
        APPS("From apps"),
        NETWORK("Network"),
    }

    /**
     * The groups in the order they are drawn: the ones somebody arranged, then the rest in their
     * natural order. Unknown names - from an older or newer Filet - are passed over, so a stored
     * order can never hide a group.
     */
    fun groupOrder(stored: List<String>): List<DriveKind> {
        val arranged = stored.mapNotNull { name -> DriveKind.entries.firstOrNull { it.name == name } }.distinct()
        return arranged + DriveKind.entries.filter { it !in arranged }
    }

    /**
     * The group order after [kind] moves [by] places (-1 up, 1 down) among the groups [shown] -
     * the ones with a drive in them now. A group not drawn keeps its place relative to the others,
     * so moving On this phone above Network still holds when a stick is plugged in later. A move
     * off either end changes nothing.
     */
    fun movedGroup(order: List<DriveKind>, shown: List<DriveKind>, kind: DriveKind, by: Int): List<DriveKind> {
        val visible = order.filter { it in shown }
        val from = visible.indexOf(kind)
        val to = from + by
        if (from < 0 || to !in visible.indices) return order
        val swap = visible[to]
        return order.map { if (it == kind) swap else if (it == swap) kind else it }
    }

    /** The Termux offer's id, in `hiddenCards` and in the drive order alike. */
    const val TERMUX_OFFER = "offer:termux"

    /** The id of one app's folders drawn as one card. */
    fun appCardId(authority: String): String = "app:$authority"

    /**
     * Which group a drive falls in, from its stored id and whether it is reached over a network.
     *
     * `remote` comes from the provider, not from the scheme: `root:` and `saf:` are not the local
     * scheme and are on this phone all the same. A local volume outside `/storage/emulated` is
     * a card or a stick - `/storage/1A2B-3C4D` is how removable storage mounts on every phone this
     * has met.
     */
    fun kindOf(id: String, remote: Boolean): DriveKind {
        if (remote) return DriveKind.NETWORK
        if (id == TERMUX_OFFER || id.startsWith("app:")) return DriveKind.APPS
        val scheme = id.substringBefore("://", "")
        val path = id.substringAfter("://", "")
        return when {
            scheme == "saf" -> DriveKind.APPS
            scheme == "local" && isPlugged(path) -> DriveKind.PLUGGED
            else -> DriveKind.PHONE
        }
    }

    private fun isPlugged(path: String): Boolean {
        if (!path.startsWith("/storage/")) return false
        val volume = path.removePrefix("/storage/").substringBefore('/')
        return volume.isNotEmpty() && volume != "emulated" && volume != "self"
    }

    /**
     * The authority of the app a `saf:` drive comes from, or null for anything else.
     *
     * The tree's URI is the path's first segment, percent-encoded:
     * `saf:///content%3A%2F%2Fcom.termux.documents%2Ftree%2F...`.
     */
    fun authorityOf(id: String): String? {
        if (!id.startsWith("saf://")) return null
        val first = id.substringAfter("saf://").trimStart('/').substringBefore('/')
        val tree = runCatching { java.net.URLDecoder.decode(first, "UTF-8") }.getOrNull() ?: return null
        if (!tree.startsWith("content://")) return null
        return tree.removePrefix("content://").substringBefore('/').ifEmpty { null }
    }

    /**
     * The drives in the order they are drawn: the ones somebody arranged in that order, then the
     * rest in their natural order.
     *
     * A drive never arranged - one plugged in for the first time, a share added since - joins
     * after the arranged ones rather than pushing in among them. An id stored for a drive that is
     * not here now keeps its place in the stored order, so the drive comes back where it was when
     * it is plugged in again.
     */
    fun ordered(natural: List<String>, stored: List<String>): List<String> {
        val here = natural.toSet()
        val arranged = stored.distinct().filter { it in here }
        return arranged + natural.filter { it !in arranged }
    }

    /**
     * One tile in the drive row: a single drive, or one app's folders as one card.
     *
     * [ids] are the drives it stands for. The stored order only ever holds drive ids, never a
     * tile's own, so switching between a card each and one per app loses nothing: the app's card
     * sits where its first folder sits, and moving it moves all of its folders together.
     */
    sealed interface Tile {
        val id: String
        val ids: List<String>
    }

    data class Drive(override val id: String) : Tile {
        override val ids: List<String> get() = listOf(id)
    }

    /** One app's folders drawn as one card. */
    data class AppCard(override val id: String, val authority: String, override val ids: List<String>) : Tile

    /**
     * The tiles for [ids], already in order, under [perApp]: each drive as itself, or - one card
     * per app - the folders of an app that has more than one here folded into one [AppCard], at
     * the place of the first of them.
     *
     * An app with a single folder stays a plain card either way: a card saying "1 folder" in
     * place of the folder's own name says less than the card it replaced.
     */
    fun tiles(ids: List<String>, perApp: PerApp): List<Tile> {
        if (perApp == PerApp.EACH) return ids.map(::Drive)
        val shared = ids.mapNotNull { id -> authorityOf(id)?.let { it to id } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        val done = HashSet<String>()
        val out = ArrayList<Tile>()
        for (id in ids) {
            val auth = authorityOf(id)
            val members = auth?.let { shared[it] }
            if (auth == null || members == null) out += Drive(id)
            else if (done.add(auth)) out += AppCard(appCardId(auth), auth, members)
        }
        return out
    }

    /**
     * The stored order after the tile [id] is dropped where the tile [over] is, among the tiles
     * [shown] as drawn.
     *
     * Ids in [stored] that are not drawn now - a drive unplugged at the moment - keep their order
     * after the rest, so unplugging one never forgets where it went relative to the others that
     * are away. Dropped onto a tile further along, [id] lands after it; onto one before it, in
     * front of it: the tile passed over is the one that steps aside, which is how the drag reads
     * under the finger.
     */
    fun moved(stored: List<String>, shown: List<Tile>, id: String, over: String): List<String> =
        storedAs(stored, shown, passed(shown.map { it.id }, id, over))

    /**
     * One step of a drag, over tile ids: [id] takes [over]'s place and the tiles between step
     * toward where it was. Unknown ids or a step onto itself change nothing.
     */
    fun passed(order: List<String>, id: String, over: String): List<String> {
        val from = order.indexOf(id)
        val to = order.indexOf(over)
        if (from < 0 || to < 0 || from == to) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The stored order for the tiles [shown] drawn in [order] (tile ids): each tile's drives in
     * that order, then whatever [stored] held that is not drawn now, in its own order.
     */
    fun storedAs(stored: List<String>, shown: List<Tile>, order: List<String>): List<String> {
        val byId = shown.associateBy { it.id }
        val next = order.mapNotNull { byId[it] }.flatMap { it.ids }
        val drawn = next.toSet()
        return next + stored.filter { it !in drawn }
    }

    /**
     * Whether a tile of [idKind] may be dropped onto one of [overKind]. In one row, anywhere;
     * grouped, only within its own group, because a drive dragged into another group would land
     * somewhere it is never drawn.
     */
    fun canMove(layout: Layout, idKind: DriveKind, overKind: DriveKind): Boolean =
        layout == Layout.FLOW || idKind == overKind

    /**
     * The categories shown after [id] is switched, from [hidden]. The last one showing refuses
     * to go: a Categories card with nothing in it is a heading over a hole, and the card itself
     * has its own eye for taking it off Home.
     */
    fun toggledCategory(all: List<String>, hidden: Set<String>, id: String): Set<String> {
        if (id !in all) return hidden
        if (id in hidden) return hidden - id
        val showing = all.count { it !in hidden }
        return if (showing <= 1) hidden else hidden + id
    }
}
