package dev.niccc2007.filet.vfs.provider

/**
 * Which files are one archive cut into pieces.
 *
 * Nic asked for *"a multi part archive.. like others part1 part 2 etc"*, on both sides: hand
 * Filet one part of a set somebody sent and have it open as a whole, and produce a set when
 * making one.
 *
 * This file is only the naming, and the naming is where it goes wrong, because the four
 * schemes in circulation do not agree with each other and two of them are ambiguous with
 * ordinary files:
 *
 * | Scheme | Looks like | Last part |
 * |---|---|---|
 * | [PartStyle.RAR_PART] | `movie.part1.rar`, `movie.part2.rar` | flagged inside the archive |
 * | [PartStyle.RAR_OLD] | `movie.rar`, `movie.r00`, `movie.r01` | not knowable from names |
 * | [PartStyle.ZIP_VOLUMES] | `backup.z01`, `backup.z02`, `backup.zip` | the `.zip`, always |
 * | [PartStyle.NUMBERED] | `backup.7z.001`, `backup.7z.002` | not knowable from names |
 *
 * ## The two failure modes
 *
 * **Missing a part**, so an incomplete set reads as a corrupt archive and somebody re-downloads
 * four gigabytes. **Swallowing an unrelated file**, so `photos.001` from some other tool gets
 * treated as part of an archive and the read fails in a way that blames the wrong file. The
 * guard against the second is that a numeric suffix only counts when what is left underneath
 * it is itself a recognised archive name - `backup.7z.001` is a part, `notes.001` is a file.
 *
 * ## What cannot be known from names alone
 *
 * For two of the four schemes the last part is only identifiable by reading it. So this
 * reports *gaps* - parts provably missing because a later one is present - and says plainly
 * whether the end of the set is knowable. Claiming a set is complete when the answer is "the
 * names cannot tell you" is the version of this that wastes somebody's evening.
 */

enum class PartStyle {
    /** `name.partN.rar`. The modern RAR set, and the only one with the index in the middle. */
    RAR_PART,

    /** `name.rar` then `name.r00`, `name.r01`. The 1990s layout, still common in the wild. */
    RAR_OLD,

    /** `name.z01` ... then `name.zip` LAST, carrying the central directory. */
    ZIP_VOLUMES,

    /** `name.ext.001`. A plain byte split; concatenating the parts rebuilds the file exactly. */
    NUMBERED,
}

/**
 * One file's membership of a set.
 *
 * @param index 1-based position. `name.rar` in a [PartStyle.RAR_OLD] set is part 1 and
 *   `name.r00` is part 2, which is the off-by-one this type exists to keep in one place.
 * @param padding how many digits the number is written with, so a generated set keeps a
 *   consistent width and sorts correctly in every file listing.
 */
data class PartRef(
    val setName: String,
    val style: PartStyle,
    val index: Int,
    val padding: Int,
)

/**
 * What is on disk for one set.
 *
 * @param gaps parts provably absent: a lower number is missing while a higher one is present.
 * @param endKnown whether the LAST part can be identified from the names. True only for
 *   [PartStyle.ZIP_VOLUMES], where the `.zip` is always last.
 * @param complete true only when there are no gaps, part 1 is present, AND the end is knowable.
 */
data class PartSetState(
    val setName: String,
    val style: PartStyle,
    val present: List<String>,
    val gaps: List<String>,
    val endKnown: Boolean,
    val complete: Boolean,
) {
    /** The file to hand the reader. Always part 1 - the others have no header on them. */
    val firstPart: String? get() = present.firstOrNull()
}

object PartSets {

    /**
     * The index given to a part that is last by NAME rather than by number - the plain
     * `.zip` closing a zip volume set.
     *
     * It was `Int.MAX_VALUE` and that was a bug with a crash in it: the gap scan runs
     * `1..highest`, so a set containing the final `.zip` tried to materialise a range of
     * two billion entries and died with an OutOfMemoryError. A sentinel that is obviously
     * not an index cannot be used as one by accident.
     */
    const val TAIL = -1

    private val RAR_PART = Regex("""^(.*)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)
    private val RAR_OLD = Regex("""^(.*)\.r(\d{2})$""", RegexOption.IGNORE_CASE)
    private val ZIP_VOL = Regex("""^(.*)\.z(\d{2})$""", RegexOption.IGNORE_CASE)
    private val NUMBERED = Regex("""^(.*)\.(\d{3,})$""")

    /**
     * Which set [name] belongs to, or null when it is a file in its own right.
     *
     * Order matters: `movie.part1.rar` also matches "an ordinary rar", so the part patterns are
     * tried first. A plain `movie.rar` is NOT reported as a set here - it is only part 1 of a
     * [PartStyle.RAR_OLD] set if an `.r00` sits next to it, which is a question about the
     * folder rather than about the name, and is answered by [stateOf].
     */
    fun refFor(name: String): PartRef? {
        RAR_PART.find(name)?.let { m ->
            val digits = m.groupValues[2]
            return PartRef(m.groupValues[1], PartStyle.RAR_PART, digits.toInt(), digits.length)
        }
        RAR_OLD.find(name)?.let { m ->
            // .r00 is the SECOND part; the first is the bare .rar.
            return PartRef(m.groupValues[1], PartStyle.RAR_OLD, m.groupValues[2].toInt() + 2, 2)
        }
        ZIP_VOL.find(name)?.let { m ->
            return PartRef(m.groupValues[1], PartStyle.ZIP_VOLUMES, m.groupValues[2].toInt(), 2)
        }
        NUMBERED.find(name)?.let { m ->
            val base = m.groupValues[1]
            // The guard against swallowing an unrelated `.001`: what is underneath has to be
            // an archive name on its own. `backup.7z.001` yes, `holiday-photos.001` no.
            if (Archives.of(base) == null) return null
            val digits = m.groupValues[2]
            return PartRef(base, PartStyle.NUMBERED, digits.toInt(), digits.length)
        }
        return null
    }

    /** The name of part [index] of a set, in the same scheme and width. */
    fun nameFor(setName: String, style: PartStyle, index: Int, padding: Int): String = when (style) {
        PartStyle.RAR_PART -> "$setName.part${index.toString().padStart(padding, '0')}.rar"
        PartStyle.RAR_OLD ->
            if (index <= 1) "$setName.rar"
            else "$setName.r${(index - 2).toString().padStart(2, '0')}"
        PartStyle.ZIP_VOLUMES -> "$setName.z${index.toString().padStart(2, '0')}"
        PartStyle.NUMBERED -> "$setName.${index.toString().padStart(padding, '0')}"
    }

    /**
     * What exists for the set [member] belongs to, given everything in the folder.
     *
     * @param member any one part; the caller does not have to find part 1 first, which is the
     *   whole point - tapping part 3 should not be an error.
     * @param siblings every name in the same folder.
     */
    fun stateOf(member: String, siblings: Collection<String>): PartSetState? {
        val direct = refFor(member)
        // A bare `name.rar` or `name.zip` is only a set when its siblings say so.
        val ref = direct ?: impliedFirstPart(member, siblings) ?: return null

        val found = sortedMapOf<Int, String>()
        var tail: String? = null
        for (s in siblings) {
            val r = refFor(s) ?: impliedFirstPart(s, siblings) ?: continue
            if (r.style != ref.style) continue
            if (!r.setName.equals(ref.setName, ignoreCase = true)) continue
            // The zip set's final `.zip` carries no number, so it is held aside rather than
            // given an index. Giving it one - and Int.MAX_VALUE in particular - made the gap
            // scan below try to build a two-billion-entry range.
            if (r.index == TAIL) tail = s else found[r.index] = s
        }
        if (found.isEmpty() && tail == null) return null
        // A lone `.zip` or `.rar` with nothing numbered beside it is an ordinary archive.
        if (found.isEmpty()) return null
        if (found.size == 1 && tail == null && direct == null) return null

        val highest = found.keys.max()
        val gaps = (1..highest).filter { it !in found.keys }
            .map { nameFor(ref.setName, ref.style, it, ref.padding) }

        val present = found.values.toList() + listOfNotNull(tail)
        // Only a zip set can be known to be whole from its names: its last part is always the
        // plain `.zip`. RAR and numbered sets flag the end INSIDE the archive, so a set with no
        // gaps may still be missing everything after the highest part present.
        val endKnown = ref.style == PartStyle.ZIP_VOLUMES && tail != null

        return PartSetState(
            setName = ref.setName,
            style = ref.style,
            present = present,
            gaps = gaps,
            endKnown = endKnown,
            complete = gaps.isEmpty() && 1 in found.keys && endKnown,
        )
    }

    /**
     * `name.rar` as part 1 of an old-style set, or `name.zip` as the tail of a zip set.
     *
     * Only when a sibling proves it. Treating every `.rar` as a one-part set would report a
     * multi-part archive for every archive on the device.
     */
    private fun impliedFirstPart(name: String, siblings: Collection<String>): PartRef? {
        val lower = name.lowercase()
        if (lower.endsWith(".rar") && RAR_PART.find(name) == null) {
            val base = name.dropLast(4)
            val hasOld = siblings.any { RAR_OLD.find(it)?.groupValues?.get(1).equals(base, ignoreCase = true) }
            if (hasOld) return PartRef(base, PartStyle.RAR_OLD, 1, 2)
        }
        if (lower.endsWith(".zip")) {
            val base = name.dropLast(4)
            val vols = siblings.mapNotNull { ZIP_VOL.find(it)?.groupValues?.get(1) }
            if (vols.any { it.equals(base, ignoreCase = true) }) {
                return PartRef(base, PartStyle.ZIP_VOLUMES, TAIL, 2)
            }
        }
        return null
    }

    /**
     * The names a split of [totalBytes] into [partBytes] will produce.
     *
     * The width is chosen from the part COUNT rather than fixed at three, so a 1200-part set
     * does not roll `.999` into `.1000` and stop sorting. Three digits is the floor because
     * every tool expects at least that.
     */
    fun plannedNames(setName: String, style: PartStyle, totalBytes: Long, partBytes: Long): List<String> {
        require(partBytes > 0) { "a part has to have a size" }
        val count = maxOf(1, ((totalBytes + partBytes - 1) / partBytes).toInt())
        val padding = maxOf(if (style == PartStyle.NUMBERED) 3 else 2, count.toString().length)
        return when (style) {
            // The volumes are numbered and the FINAL part is the plain .zip, so the numbered
            // ones stop one short of the count.
            PartStyle.ZIP_VOLUMES ->
                (1 until count).map { nameFor(setName, style, it, padding) } + "$setName.zip"
            else -> (1..count).map { nameFor(setName, style, it, padding) }
        }
    }
}
