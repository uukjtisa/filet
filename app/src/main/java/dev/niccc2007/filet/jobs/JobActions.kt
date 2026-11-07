package dev.niccc2007.filet.jobs

import dev.niccc2007.filet.ui.Labels
import kotlin.math.abs

/** One thing an Activity row offers. The label is what the button says. */
enum class JobAction(val label: String) {
    /** The folder the work landed in, opened; for a file, its folder with it selected. */
    GO_TO("Go to folder"),

    /** The file the work made, in whatever opens it; a folder is opened. */
    OPEN("Open"),

    /** The result in the folder that holds it, selected. */
    SHOW_IN_FOLDER(Labels.OPEN_LOCATION),
    PAUSE("Pause"),
    RESUME("Resume"),
    STOP("Stop"),
    RETRY("Retry"),
    RUN_AGAIN("Run again"),
}

/**
 * What an Activity row offers, declared once for both places that offer it: the opened row and
 * the hold menu. So a kind of job cannot ship with a way to its result in one and not the other,
 * and a new kind does not compile until [resultOf] says what its result is.
 *
 * A tap only ever opens the row; nothing here happens until one of these is pressed. Copies and
 * moves used to go to their folder on the tap itself, which hid their details behind a jump.
 */
object JobActions {

    /** What a kind of job leaves behind, for deciding how to show it. */
    enum class Result {
        /** Always a folder: going to it is the only thing worth offering. */
        FOLDER,

        /** Always a file: open it, or see it where it is. */
        FILE,

        /** A file or a folder depending on the work: both ways are offered. */
        EITHER,

        /** Nothing to go to. */
        NONE,
    }

    /** Exhaustive, so a new [JobKind] has to decide this before it compiles. */
    fun resultOf(kind: JobKind): Result = when (kind) {
        // Into a folder: copied or moved into, extracted into, measured, beat-mapped.
        JobKind.COPY, JobKind.MOVE, JobKind.EXTRACT, JobKind.MEASURE, JobKind.BEAT -> Result.FOLDER
        // The folder the items went from.
        JobKind.DELETE -> Result.FOLDER
        // An archive written, a file saved, a download.
        JobKind.COMPRESS, JobKind.SAVE, JobKind.FETCH, JobKind.DOWNLOAD -> Result.FILE
        // A signed APK or a decompiled folder; and whatever another app published.
        JobKind.APK, JobKind.OTHER -> Result.EITHER
        // No result recorded yet. A way to what an index found or a script printed needs a place
        // to open first; until then these rows show their figures and nothing to go to.
        JobKind.INDEX, JobKind.SCRIPT -> Result.NONE
    }

    /** Everything [job] offers now, in the order the buttons stand. */
    fun of(job: Job): List<JobAction> = buildList {
        if (job.running) {
            if (job.pausable) add(if (job.paused) JobAction.RESUME else JobAction.PAUSE)
            if (job.cancellable) add(JobAction.STOP)
            return@buildList
        }
        if (job.opened.isNotEmpty()) {
            when (resultOf(job.kind)) {
                Result.FOLDER -> add(JobAction.GO_TO)
                Result.FILE, Result.EITHER -> {
                    add(JobAction.OPEN)
                    add(JobAction.SHOW_IN_FOLDER)
                }
                Result.NONE -> Unit
            }
        }
        if (job.canRunAgain) {
            val retry = job.state == JobState.FAILED || job.state == JobState.CANCELLED
            add(if (retry) JobAction.RETRY else JobAction.RUN_AGAIN)
        }
    }
}

/**
 * Where a swiped Activity row settles when the finger lifts: back, or off to one side.
 *
 * Away past 40% of the row or on a fling; anything less springs back, so a sideways wobble while
 * scrolling never clears a row. The side is the one the row is heading for, the fling counted.
 */
object SwipeAway {
    const val AWAY_FRACTION = 0.4f
    const val FLING_PX_PER_S = 2_000f

    /** The offset to settle at: 0 for back, or minus or plus [widthPx] for away. */
    fun settle(offsetPx: Float, widthPx: Float, velocity: Float): Float {
        val away = abs(offsetPx) > widthPx * AWAY_FRACTION || abs(velocity) > FLING_PX_PER_S
        if (!away) return 0f
        return if (offsetPx + velocity / 10f < 0f) -widthPx else widthPx
    }
}
