package dev.niccc2007.filet.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.niccc2007.filet.vfs.provider.SafProvider

/**
 * Termux, as a place you can browse.
 *
 * Nic: *"i have termux here.. why dont you make it pair nicely with termux? and make termux
 * expose its own bin so its manageable in here in filet? make it detect termux.. so i can easily
 * transfer files using a gui from termux internals to my bin"*.
 *
 * ## Why this is a hand-off and not a provider
 *
 * Termux's files live under `/data/data/com.termux/files` - its `home`, and `usr/bin` where
 * everything it installs ends up. That is another app's private data directory, and on an
 * unrooted phone nothing can read it. Not Filet, not the system file manager, not adb as
 * shell. Writing a provider that tried would be a provider that always failed.
 *
 * Termux solves this itself: it ships a `DocumentsProvider` (`com.termux.documents`) that
 * exposes exactly that tree through the Storage Access Framework, and SAF is already a
 * first-class provider in Filet (PLAN.md L0). So the whole integration is: notice Termux is
 * installed, send the user to its provider with one tap, and let `saf://` do the rest. After
 * that the tree is an ordinary volume - two panes, drag between them, copy in either direction.
 *
 * The grant is persistent, so this is once per install rather than once per use.
 *
 * ## What this does NOT reach, verified on the device
 *
 * Termux's provider offers its **home** directory as the root - `.cache`, `.ssh`, `.termux`,
 * `storage` and whatever you have cloned. `usr/bin`, where everything Termux installs actually
 * lives, is a sibling of `home` under `files/` and is **outside that root**. So this gives you
 * the place you work, not the place your binaries are installed.
 *
 * Nothing here can widen it: the root is Termux's decision, made in its own manifest and its
 * own provider, and there is no non-root way around another app's private storage. Reaching
 * `usr/bin` needs either root, or Termux publishing a wider root, or moving what you want into
 * `~` from the Termux shell - which is one `cp` and is what most people do anyway.
 */
object Termux {

    const val PACKAGE = "com.termux"

    /** Termux's own DocumentsProvider. Not a guess - it is in its manifest as an exported one. */
    private const val AUTHORITY = "com.termux.documents"

    /**
     * Termux's document ids are absolute paths.
     *
     * `TermuxDocumentsProvider.getDocIdForFile` returns `file.getAbsolutePath()`, and its root
     * is the files dir, so these are the ids the picker understands.
     */
    private const val FILES_DIR = "/data/data/com.termux/files"
    private const val HOME_DIR = "$FILES_DIR/home"

    /** The Termux flavours worth noticing. F-Droid's is the one nearly everyone has. */
    private val PACKAGES = listOf(PACKAGE, "com.termux.fdroid")

    /** Which Termux is installed, or null. */
    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        return PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    fun isInstalled(context: Context): Boolean = installedPackage(context) != null

    /**
     * Where the folder picker should open, so Termux is one tap rather than a hunt.
     *
     * A hint, not a demand: if a Termux build ever changes its document ids the picker simply
     * opens where it normally would and the user picks Termux from the sidebar. A wrong hint
     * costs one extra tap, which is why it is worth attempting at all.
     *
     * Built through [SafProvider] rather than by calling `DocumentsContract` here, because
     * that is a storage API and R3 keeps those below the VFS. The checker caught this file
     * the first time it was written.
     */
    fun pickerHint(home: Boolean = true): Uri =
        SafProvider.documentUriFor(AUTHORITY, if (home) HOME_DIR else FILES_DIR)

    /**
     * Whether a granted tree is one of Termux's, so it can be labelled as such.
     *
     * Matched on the authority, not on the path: the path inside a tree URI is percent-encoded
     * and differs between "home" and "files", but the authority is the app.
     */
    fun isTermuxTree(uri: Uri): Boolean = uri.authority == AUTHORITY

    /**
     * A readable name for a granted Termux tree.
     *
     * "Termux" alone is not enough once somebody has granted both home and the whole files
     * directory - two identical cards is the thing Home already has too much of.
     */
    fun labelFor(uri: Uri): String {
        val decoded = Uri.decode(uri.toString())
        return when {
            decoded.contains("$HOME_DIR") -> "Termux home"
            decoded.contains("/usr/bin") -> "Termux bin"
            decoded.contains(FILES_DIR) -> "Termux files"
            else -> "Termux"
        }
    }

    /** Open Termux itself. Offered beside the browse action, because sometimes you want the shell. */
    fun launch(context: Context): Intent? {
        val pkg = installedPackage(context) ?: return null
        return context.packageManager.getLaunchIntentForPackage(pkg)
    }
}
