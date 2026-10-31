package dev.niccc2007.filet.qr

/**
 * What came back from Android's "save this network?" sheet, and what to say about it.
 *
 * ## The fault this exists for
 *
 * Bug identified here: "Add this network" did nothing - no sheet, no message. The sheet was
 * started the way every other hand-off here is, with a plain `startActivity`, and the screen
 * behind `ACTION_WIFI_ADD_NETWORKS` asks for its caller's package before it shows anything -
 * which Android only answers for an activity started FOR A RESULT. Started any other way, it
 * sees nobody asking and closes in the same instant it opened. Nothing was thrown, so the
 * "nothing on this device can do that" message never fired either.
 *
 * Started for a result, the sheet both appears and says what happened, and every outcome now
 * gets a sentence - including the one where the phone still will not show it.
 */
object WifiAdd {

    enum class Outcome {
        /** Saved. */
        SAVED,

        /** It was saved already; nothing changed. */
        ALREADY,

        /** Asked, and Android could not save it. */
        FAILED,

        /** Shown, and turned down. */
        DECLINED,

        /** Closed before anybody could have read it: this phone will not show the sheet. */
        NOT_SHOWN,
    }

    /**
     * Faster than this, a cancel was not a person. Reading a sheet and pressing Cancel takes well
     * over a second; a screen that refuses its caller closes within a frame or two.
     */
    const val HUMAN_MS = 800L

    /** Android's result codes for one network, from `Settings.ADD_WIFI_RESULT_*`. */
    private const val SUCCESS = 0
    private const val ADD_OR_UPDATE_FAILED = 1
    private const val ALREADY_EXISTS = 2

    /**
     * @param ok the activity's result was RESULT_OK.
     * @param codes the per-network result list, when the sheet sent one.
     * @param elapsedMs from starting the sheet to its answer.
     */
    fun outcome(ok: Boolean, codes: List<Int>?, elapsedMs: Long): Outcome {
        if (!ok) return if (elapsedMs < HUMAN_MS) Outcome.NOT_SHOWN else Outcome.DECLINED
        return when (codes?.firstOrNull()) {
            SUCCESS, null -> Outcome.SAVED
            ALREADY_EXISTS -> Outcome.ALREADY
            ADD_OR_UPDATE_FAILED -> Outcome.FAILED
            else -> Outcome.FAILED
        }
    }
}
