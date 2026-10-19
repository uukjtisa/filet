package dev.niccc2007.filet.notice

import dev.niccc2007.filet.log.FiletLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything that appears without being asked for, in one queue.
 *
 * Decision: one queue, one item on screen at a time. The welcome and the update offer both want
 * the first launch after an install - a fresh install of an old APK has both - and two
 * subsystems each deciding independently to show a dialogue put two on screen at once, the
 * second stacked on the first. A new kind of unsolicited dialogue is added to [NoticeKind] and
 * nowhere else; the ordering then decides itself.
 *
 * Something the user asked for - pressing Check for updates - is not a notice and never waits
 * here. It is an answer, and an answer held behind a queue reads as a dead button.
 */
enum class NoticeKind(val rank: Int) {
    /** First, because it explains the app the other notices are about. */
    WELCOME(0),
    UPDATE(1),
}

object NoticeQueue {
    /**
     * What should be on screen. Pure.
     *
     * The one showing stays until it is done, even when something that ranks higher arrives:
     * swapping a dialogue out from under a thumb that is about to tap it is worse than making the
     * newcomer wait one dismissal.
     */
    fun head(pending: Set<NoticeKind>, showing: NoticeKind?): NoticeKind? =
        showing?.takeIf { it in pending } ?: pending.minByOrNull { it.rank }
}

/** The process-wide queue. Per process on purpose: what was dismissed is remembered by its owner. */
object Notices {
    private val pending = MutableStateFlow<Set<NoticeKind>>(emptySet())
    private val _head = MutableStateFlow<NoticeKind?>(null)

    /** The notice to draw now, or null. */
    val head: StateFlow<NoticeKind?> = _head.asStateFlow()

    fun offer(kind: NoticeKind) {
        pending.update { it + kind }
        settle()
        FiletLog.i("notice", "queued $kind; showing ${_head.value}")
    }

    fun done(kind: NoticeKind) {
        pending.update { it - kind }
        settle()
    }

    private fun settle() {
        _head.value = NoticeQueue.head(pending.value, _head.value)
    }
}
