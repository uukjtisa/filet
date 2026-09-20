package dev.niccc2007.filet.handlers

/**
 * Where "Share" can send a file from inside a viewer.
 *
 * Bug identified: the share button in the image, video, audio and text viewers handed straight
 * to Android's own share sheet, so the one kind of sharing Filet actually implements - a link
 * any browser on the network can open, with nothing installed at the other end - was the one
 * thing that button could not reach. The app's own feature was missing from the app's own
 * button.
 *
 * It is a choice and not a replacement: handing a file to another app is still right most of
 * the time, and it stays first for that reason.
 */
enum class ShareTarget(val label: String, val detail: String) {
    /** Android's share sheet. Whatever is installed. */
    APPS("Share to another app", "The usual Android share sheet"),

    /**
     * Filet's own network share.
     *
     * Not "nearby" in the Android sense - this is Filet's HTTP share, which needs no app on the
     * other end and works to a laptop, a console browser, anything that can open a URL.
     */
    NETWORK("Share over your network", "A link any browser here can open"),
}

/**
 * What a viewer's share button should offer for this file.
 *
 * @param local whether the file has a real path on this device. A file inside an archive or on
 *   a remote has nothing to serve, so the network option would fail after being offered - and
 *   offering something that cannot work is worse than not offering it (PLAN.md R1).
 * @return the targets to show, best first. A single-element result means there is no choice to
 *   make and the button should just do it rather than opening a menu over one option.
 */
fun shareTargets(local: Boolean): List<ShareTarget> =
    if (local) listOf(ShareTarget.APPS, ShareTarget.NETWORK) else listOf(ShareTarget.APPS)

/** Whether the button has a real choice to present, or should act straight away. */
fun needsShareChoice(local: Boolean): Boolean = shareTargets(local).size > 1
