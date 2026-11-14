package dev.niccc2007.filet.jobs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which job kinds announce themselves in the app, and how: the started card when a job begins,
 * the message at the bottom when it ends. The engine behind the Activity page in Settings.
 *
 * ## One list
 *
 * Every switch on that page is read from [JobKind.notice], an exhaustive `when`: a new kind of
 * job does not compile until it has a group and says whether it has a finished message. A list of
 * switches kept beside the kinds would let a new action ship with no switch at all, which is the
 * failure a registry exists to make impossible.
 *
 * ## Decisions
 *
 * - **A failure always says so.** Switching off a kind's finished message hides its success
 *   only. A failed copy that ended in silence would read as a copy that worked.
 * - **Kinds that end silently have no finished-message switch.** Indexing, folder sizes, beat maps,
 *   fetches from a share, downloads from other apps: they have never had a message, so a switch
 *   for one would be a control that does nothing. They keep their started card and its switch.
 * - **How long a card stays times the cards only.** The finished message keeps its own time.
 * - **Only what is switched OFF is stored**, by the kind's name, so a kind added later arrives
 *   switched on, and one removed later is dropped on reading rather than failing it.
 */
object Notices {

    /** How long a started card may stay, in the order offered. */
    val LIFE_CHOICES = listOf(2_000L, 3_000L, 5_000L, 8_000L)

    /** How many started cards may be on screen at once, in the order offered. */
    val STACK_CHOICES = listOf(1, 2, 3, 5)

    /** Whether a job of [kind] gets its started card. */
    fun showsCard(kind: JobKind, prefs: NoticePrefs): Boolean = kind !in prefs.startsOff

    /**
     * Whether a job of [kind] that ended gets its message. [failed] always does: see the class
     * note. A kind with no finished message has nothing to show on success.
     */
    fun showsEnd(kind: JobKind, failed: Boolean, prefs: NoticePrefs): Boolean =
        failed || (kind.notice().ends && kind !in prefs.endsOff)

    /** The kinds in [group], in the order the page lists them. */
    fun kindsIn(group: NoticeGroup): List<JobKind> = JobKind.entries.filter { it.notice().group == group }
}

/** The groups on the Activity page, in the order it lists them. */
enum class NoticeGroup { COPY_MOVE, ARCHIVES, DELETE, NETWORK, SAVING, BACKGROUND, TOOLS, OTHER }

/**
 * Where a kind's switches sit, and whether it has a finished message at all.
 *
 * @param ends false for a kind whose job has never ended with a message - see [Notices].
 */
data class Notice(val group: NoticeGroup, val ends: Boolean)

/** See [Notices]. Exhaustive on purpose: a new kind is a compile error until it is placed. */
fun JobKind.notice(): Notice = when (this) {
    JobKind.COPY, JobKind.MOVE -> Notice(NoticeGroup.COPY_MOVE, ends = true)
    JobKind.COMPRESS, JobKind.EXTRACT -> Notice(NoticeGroup.ARCHIVES, ends = true)
    JobKind.DELETE -> Notice(NoticeGroup.DELETE, ends = true)
    JobKind.FETCH, JobKind.DOWNLOAD -> Notice(NoticeGroup.NETWORK, ends = false)
    JobKind.SAVE -> Notice(NoticeGroup.SAVING, ends = true)
    JobKind.INDEX, JobKind.MEASURE, JobKind.BEAT -> Notice(NoticeGroup.BACKGROUND, ends = false)
    JobKind.SCRIPT, JobKind.APK -> Notice(NoticeGroup.TOOLS, ends = true)
    JobKind.OTHER -> Notice(NoticeGroup.OTHER, ends = false)
}

/** Where the started cards sit. [TOP_END] is where they have always been. */
enum class CardPlace { TOP_END, TOP_START, BOTTOM_END, BOTTOM_START }

/**
 * The Activity page's settings. The defaults are what the app has always done, so a phone that
 * never opens the page sees no change.
 */
data class NoticePrefs(
    val startsOff: Set<JobKind> = emptySet(),
    val endsOff: Set<JobKind> = emptySet(),
    val lifeMs: Long = StartedCards.LIFE_MS,
    val place: CardPlace = CardPlace.TOP_END,
    val stack: Int = StartedCards.MAX,
) {
    fun encode(): String = JSONObject()
        .put("startsOff", JSONArray(startsOff.map { it.name }.sorted()))
        .put("endsOff", JSONArray(endsOff.map { it.name }.sorted()))
        .put("life", lifeMs)
        .put("place", place.name)
        .put("stack", stack)
        .toString()

    companion object {
        /**
         * The stored settings, field by field: anything missing or unreadable falls back to its
         * default rather than taking the rest with it, and a value no longer offered snaps to the
         * nearest one that is.
         */
        fun decode(raw: String?): NoticePrefs {
            if (raw.isNullOrBlank()) return NoticePrefs()
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return NoticePrefs()
            fun kinds(key: String): Set<JobKind> {
                val a = o.optJSONArray(key) ?: return emptySet()
                return (0 until a.length()).mapNotNull { i ->
                    runCatching { JobKind.valueOf(a.optString(i)) }.getOrNull()
                }.toSet()
            }
            val d = NoticePrefs()
            return NoticePrefs(
                startsOff = kinds("startsOff"),
                // A kind with no finished message cannot have it switched off.
                endsOff = kinds("endsOff").filterTo(HashSet()) { it.notice().ends },
                lifeMs = nearest(o.optLong("life", d.lifeMs), Notices.LIFE_CHOICES),
                place = runCatching { CardPlace.valueOf(o.optString("place")) }.getOrDefault(d.place),
                stack = nearest(o.optInt("stack", d.stack).toLong(), Notices.STACK_CHOICES.map { it.toLong() }).toInt(),
            )
        }

        private fun nearest(v: Long, choices: List<Long>): Long = choices.minBy { kotlin.math.abs(it - v) }
    }
}
