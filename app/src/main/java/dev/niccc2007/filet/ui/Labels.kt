package dev.niccc2007.filet.ui

/**
 * Words that more than one surface shows for the same action, declared once.
 *
 * Bug identified here: the way to an item's folder was "Show in folder" in the selection menu,
 * the viewers and Activity, and "Go to containing folder" on Home's rows - four sites, two
 * names, one action. Each site spelled its own string, so nothing kept them together. Every
 * surface now reads its label from here.
 */
object Labels {
    /**
     * The item, selected, in the folder that holds it. Neutral on purpose: it reads the same for
     * a folder as for a file, which "Open file location" does not.
     */
    const val OPEN_LOCATION = "Open location"
}
