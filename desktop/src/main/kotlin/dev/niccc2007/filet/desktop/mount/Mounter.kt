package dev.niccc2007.filet.desktop.mount

/**
 * Attaching a share to this computer, on whichever OS this is.
 *
 * One interface, one implementation per platform, picked once at start by [Mounters.forThisOs].
 * The window never asks which OS it is on; it asks the mounter what it can do. An OS check
 * scattered through the UI is how one platform's button ends up calling another platform's code
 * path, and an exhaustive `when` over [Platform] in exactly one place is the cheaper guard.
 *
 * Every call blocks and may take seconds - a redirector timing out on a phone that has left the
 * network takes about twenty - so callers run them off the event thread.
 */
interface Mounter {
    val platform: Platform

    /**
     * Null when this mounter can attach things here, or a sentence saying what is missing.
     *
     * Asked before a Mount button is enabled, so a platform that cannot mount says so on the
     * screen instead of failing on the first press.
     */
    fun unavailable(): String?

    /** What is attached right now, read from the OS every time - never from memory. */
    fun attached(): List<Attached>

    /**
     * Attach [target] and name it [label].
     *
     * [letter] is the drive letter to take on Windows, or null for the next free one from `Z:`
     * down. Ignored elsewhere, where the OS picks the folder.
     */
    fun mount(target: DavTarget, label: String, letter: Char? = null): MountResult

    /**
     * Show [mount] as [label] in the file manager.
     *
     * Windows only. On macOS and Linux the name a drive shows is its mount folder's, which NetFS,
     * gio or the fstab line chooses and which cannot change while it is mounted, so the default
     * leaves the OS alone and the name lives in Filet's own list.
     */
    fun relabel(mount: Attached, label: String): MountResult = MountResult.Ok(mount.where)

    /** [force] closes the connection even with files open on it. */
    fun unmount(mount: Attached, force: Boolean = false): MountResult
}

/** A share the OS reports as attached. */
data class Attached(
    val target: DavTarget,
    /** `Z:` on Windows, a directory elsewhere. What the row shows and what Open opens. */
    val where: String,
    /**
     * False for a mapping the OS still holds but cannot reach - Windows keeps a remembered drive
     * after its server leaves. Shown, so it can be removed, and never presented as working.
     */
    val connected: Boolean = true,
    /**
     * The path exactly as the OS reports it, when it has one: the UNC path on Windows. Explorer
     * keys a drive's name by this string, so a drive mapped by something else is named by its
     * own spelling rather than one rebuilt from [target].
     */
    val remote: String? = null,
)

sealed interface MountResult {
    data class Ok(val where: String) : MountResult

    /**
     * A refusal with a reason a person can act on.
     *
     * [code] is the platform's own number, kept and shown, so an unmapped failure still says
     * something specific enough to search for instead of collapsing into "it failed".
     */
    data class Failed(val message: String, val code: Int? = null, val canForce: Boolean = false) : MountResult
}

enum class Platform { WINDOWS, LINUX, MAC, OTHER;

    companion object {
        /** From `os.name`. Pure, so the selection is testable on any one machine. */
        fun of(osName: String): Platform {
            val n = osName.lowercase()
            return when {
                n.startsWith("windows") -> WINDOWS
                n.startsWith("mac") || n.startsWith("darwin") -> MAC
                n.startsWith("linux") -> LINUX
                else -> OTHER
            }
        }

        val current: Platform get() = of(System.getProperty("os.name").orEmpty())
    }
}

object Mounters {
    /**
     * The mounter for this OS.
     *
     * Exhaustive over [Platform], so a fifth platform cannot be added without deciding this. An
     * unknown OS gets [Unsupported], which says so in the window rather than failing silently.
     */
    fun forThisOs(platform: Platform = Platform.current): Mounter = when (platform) {
        Platform.WINDOWS -> WindowsMounter()
        Platform.LINUX -> LinuxMounter()
        Platform.MAC -> MacMounter()
        Platform.OTHER -> Unsupported(System.getProperty("os.name").orEmpty())
    }
}

/** An OS with no mount path. Lists nothing and refuses with a reason, never quietly. */
class Unsupported(private val osName: String) : Mounter {
    override val platform = Platform.OTHER
    override fun unavailable() = "Mounting is not supported on ${osName.ifBlank { "this system" }} yet. Sharing still works."
    override fun attached(): List<Attached> = emptyList()
    override fun mount(target: DavTarget, label: String, letter: Char?) = MountResult.Failed(unavailable())
    override fun unmount(mount: Attached, force: Boolean) = MountResult.Failed(unavailable())
}
