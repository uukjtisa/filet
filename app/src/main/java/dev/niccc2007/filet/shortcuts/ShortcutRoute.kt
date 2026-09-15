package dev.niccc2007.filet.shortcuts

/**
 * What an inbound shortcut intent should make the app do.
 *
 * Identified twice: home-screen shortcuts for Search, Start sharing, Index now and
 * Recent launched Filet and then sat there on whichever tab was last open. The cause was one
 * missing line. `ShortcutRouterActivity` puts the action in an extra and hands it to
 * `MainActivity`, and `MainActivity` read the file-target extra and nothing else - so the
 * action arrived, every time, and was dropped on the floor.
 *
 * It is a function rather than a `when` inside `handleIncoming` for the reason round 6
 * established: a decision buried in an Activity callback is a decision nothing can reach to
 * prove wrong, and this one was wrong for three rounds.
 */
sealed interface ShortcutRoute {

    /** A file or a folder. The view model stats it and decides which. */
    data class OpenTarget(val raw: String, val handler: String?) : ShortcutRoute

    /** A script or an app action: Search, Start sharing, Index now, Recent. */
    data class RunAction(val action: String) : ShortcutRoute

    /** An ordinary launch. Not a failure - most launches are this. */
    data object Nothing : ShortcutRoute
}

/**
 * @param target `ShortcutRouterActivity.EXTRA_TARGET`, already resolved from a stable id.
 * @param action `ShortcutRouterActivity.EXTRA_ACTION`, for anything that is not a file.
 * @param handler a handler id to force, or null to follow the per-type default.
 *
 * A target wins over an action when both somehow arrive, because a target is the more
 * specific instruction and an intent carrying both is a bug upstream rather than a choice.
 * Blank is treated as absent: an extra put with an empty string is what a half-built intent
 * looks like, and routing on it would open a pane at nowhere.
 */
fun shortcutRoute(target: String?, action: String?, handler: String?): ShortcutRoute = when {
    !target.isNullOrBlank() -> ShortcutRoute.OpenTarget(target, handler?.takeIf { it.isNotBlank() })
    !action.isNullOrBlank() -> ShortcutRoute.RunAction(action)
    else -> ShortcutRoute.Nothing
}

/** A script shortcut carries its id behind this prefix rather than being a fifth enum value. */
const val SCRIPT_PREFIX = "script:"

fun scriptIdOrNull(action: String): String? =
    if (action.startsWith(SCRIPT_PREFIX)) action.removePrefix(SCRIPT_PREFIX).takeIf { it.isNotBlank() } else null
