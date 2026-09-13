package dev.niccc2007.filet.handlers

/**
 * What tapping a row in the opener picker should do.
 *
 * Three outcomes, and the third is the one that was missing. "Another app" names a category,
 * not an app, so picking it always has to open the list of installed apps. It used to be
 * treated as an ordinary selection, which meant that once an extension was already routed
 * externally, tapping the row it was already on did nothing at all. The only way to change
 * which app opened a `.pdf` was to set it to the code editor first and then back again.
 */
sealed interface OpenerChoice {
    /**
     * The user picked Filet's own built-in for this type.
     *
     * Clears the override rather than pinning it, so the row keeps following the default if
     * that default ever changes. Pinning it would mean "same as now" quietly becomes "frozen
     * at what now happened to be".
     */
    data object ClearOverride : OpenerChoice

    /** Another one of Filet's handlers. Route the extension there. */
    data class SetHandler(val id: HandlerId) : OpenerChoice

    /** Show the installed apps. Always, whatever the extension is set to already. */
    data object PickApp : OpenerChoice
}

/**
 * @param picked the row that was tapped.
 * @param builtIn what Filet would choose for this type with no override in the way.
 */
fun openerChoice(picked: HandlerId, builtIn: HandlerId): OpenerChoice = when {
    // Checked FIRST, before the built-in comparison. On a type whose built-in already IS
    // EXTERNAL - a .docx, say - the other order would send "Another app" down the clear
    // branch and the app list would never open for exactly the types that need it most.
    picked == HandlerId.EXTERNAL -> OpenerChoice.PickApp
    picked == builtIn -> OpenerChoice.ClearOverride
    else -> OpenerChoice.SetHandler(picked)
}

/**
 * Whether a freshly-opened chooser should be set to remember.
 *
 * **No.** Round 5 shipped this pre-ticked on the grounds that a setting the user has to go
 * and find is one they will not find, and that was the wrong call: it silently wrote a
 * permanent default every time somebody opened one file in one app once. Nic's words, and
 * they settle it: "do not auto remember unless said so".
 *
 * A function rather than a constant so the reasoning has somewhere to live and the test has
 * something to call.
 */
fun remembersByDefault(): Boolean = false
