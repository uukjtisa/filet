package dev.niccc2007.filet.shortcuts

/**
 * Which shortcuts the Shortcuts tab lists, given what the launcher says is on the home screen.
 *
 * ## The bug this closes
 *
 * Every shortcut was written to the list the moment the launcher was asked to place it. The
 * launcher then asks the person - Add or Cancel - and Cancel, or Back, left a row for an icon that
 * was never made, which had to be forgotten by hand.
 *
 * Now a request waits as [Pending] and becomes a row only when the launcher confirms it: its id
 * shows up among the pinned shortcuts, or the launcher's callback arrives. A request still
 * unconfirmed after [GRACE_MS] was turned down. A row whose icon has since been taken off the home
 * screen leaves the list too.
 *
 * ## Launchers that say nothing
 *
 * Some launchers place the icon and report nothing at all, so "not reported" cannot be read as
 * "not there" on them. [reports] says whether this launcher has ever reported a pin; until it has,
 * an unconfirmed request is kept and marked [ShortcutRecord.unconfirmed] rather than dropped, and
 * nothing already listed is removed. Guessing wrong there would silently lose somebody's list.
 */
internal object PinReconcile {

    /** How long the launcher gets to report a pin it has accepted. Its dialog closing comes first. */
    const val GRACE_MS = 2_000L

    data class Pending(val record: ShortcutRecord, val at: Long)

    data class Outcome(
        val records: List<ShortcutRecord>,
        /** Requests still inside their grace time; look again after it. */
        val pending: List<Pending>,
    )

    /**
     * @param pinned the ids the launcher reports as on the home screen now.
     * @param reports whether this launcher reports pins at all: it has done so before, or does now.
     * @param found the home screen's own shortcuts, rebuilt by [recordOf]: the turned-on ones the
     *   launcher reports. One with no row is listed, so every Filet icon on the home screen can be
     *   edited, whatever happened to its row.
     */
    fun decide(
        records: List<ShortcutRecord>,
        pending: Collection<Pending>,
        pinned: Set<String>,
        reports: Boolean,
        now: Long,
        graceMs: Long = GRACE_MS,
        found: List<ShortcutRecord> = emptyList(),
    ): Outcome {
        val waiting = ArrayList<Pending>()
        val arrived = ArrayList<ShortcutRecord>()
        for (p in pending) {
            when {
                p.record.id in pinned -> arrived += p.record.copy(unconfirmed = false)
                now - p.at < graceMs -> waiting += p
                reports -> Unit // turned down: Cancel, or Back
                else -> arrived += p.record.copy(unconfirmed = true)
            }
        }
        val waitingIds = waiting.mapTo(HashSet()) { it.record.id }
        val arrivedIds = arrived.mapTo(HashSet()) { it.id }
        val kept = records.mapNotNull { r ->
            when {
                r.id in arrivedIds -> null // replaced by the fresh request below
                r.id in pinned -> if (r.unconfirmed) r.copy(unconfirmed = false) else r
                // Being asked for again: leave it until that request is answered.
                r.id in waitingIds -> r
                reports -> null // its icon was taken off the home screen
                else -> r
            }
        }
        val listed = HashSet<String>().apply {
            kept.mapTo(this) { it.id }
            addAll(arrivedIds)
            addAll(waitingIds)
        }
        val adopted = if (reports) found.filter { it.id in pinned && it.id !in listed }.distinctBy { it.id } else emptyList()
        return Outcome(kept + arrived + adopted, waiting)
    }

    /**
     * A shortcut on the home screen, rebuilt from what it carries: its id says its kind and what it
     * opens, and a file's intent carries its path and opener.
     *
     * This is how an icon whose row was lost gets one back - its row forgotten by a build whose
     * Forget left the icon turned on, or lost with the list itself. Every id Filet has ever
     * pinned is one of these five forms. Null for any other, the held icon's menu entries among
     * them: those belong to the menu, and editing one would change the menu too.
     *
     * @param path a file shortcut's fallback path extra. An `id:` shortcut without one cannot be
     *   opened from the list, so it is not rebuilt.
     */
    fun recordOf(id: String, label: String, path: String?, handler: String?, now: Long): ShortcutRecord? {
        val r = when {
            id.startsWith("id:") -> path?.let { ShortcutRecord(id, ShortcutKind.FILE, label, it, handler, createdAt = now) }
            id.startsWith("path:") -> ShortcutRecord(id, ShortcutKind.FILE, label, path ?: id.removePrefix("path:"), handler, createdAt = now)
            id.startsWith(TAB_PREFIX) -> ShortcutRecord(id, ShortcutKind.TAB, label, id.removePrefix(TAB_PREFIX), createdAt = now)
            id.startsWith(SCRIPT_PREFIX) -> ShortcutRecord(id, ShortcutKind.SCRIPT, label, id.removePrefix(SCRIPT_PREFIX), createdAt = now)
            id.startsWith(ACTION_ID_PREFIX) -> ShortcutRecord(id, ShortcutKind.ACTION, label, id.removePrefix(ACTION_ID_PREFIX), createdAt = now)
            else -> null
        }
        return r?.takeIf { it.target.isNotBlank() }
    }

    /** An action shortcut's id is this and the action's name. */
    const val ACTION_ID_PREFIX = "action:"
}
