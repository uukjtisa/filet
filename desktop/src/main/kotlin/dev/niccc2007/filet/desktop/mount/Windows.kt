package dev.niccc2007.filet.desktop.mount

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.platform.win32.Mpr
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.Winnetwk
import com.sun.jna.ptr.IntByReference
import java.io.File

/**
 * Windows: a drive letter through the WebDAV redirector, via `mpr.dll`.
 *
 * Decision: `WNetAddConnection3` rather than `WNetAddConnection2`. They are the same call - the
 * documentation defines 2 as 3 without an owner window - and `jna-platform` binds 3 and not 2, so
 * using 3 with a null owner keeps every native signature out of this repository. Never `net use`:
 * see [WinNetErrors] for why a localised console message cannot be branched on.
 *
 * `CONNECT_UPDATE_PROFILE` makes the mapping persistent, so Windows itself reattaches it at login.
 * That is the whole of "remembers it and reattaches" on this platform, and it is why [attached]
 * reads the OS's own list instead of a file of this tool's.
 */
class WindowsMounter : Mounter {
    override val platform = Platform.WINDOWS

    override fun unavailable(): String? = when (WebClientLimit.read()) {
        WebClientLimit.Verdict.NoWebClient ->
            "This Windows has no WebClient service, which is what mounts WebDAV. On Windows Server, add the WebDAV Redirector feature."
        else -> null
    }

    override fun attached(): List<Attached> {
        val connected = enumerate(RESOURCE_CONNECTED)
        val remembered = enumerate(RESOURCE_REMEMBERED)
        val live = connected.mapNotNull { DriveLetters.letterOf(it.first) }.toSet()
        // A remembered mapping that is not also connected is one whose server has gone: Windows
        // keeps it, Explorer draws it with a red cross, and it still holds its letter.
        return (connected + remembered)
            .distinctBy { DriveLetters.letterOf(it.first) }
            .mapNotNull { (local, remote) ->
                val letter = DriveLetters.letterOf(local) ?: return@mapNotNull null
                val target = remote?.let { DavTarget.parseUnc(it) } ?: return@mapNotNull null
                Attached(target, "$letter:", connected = letter in live)
            }
            .sortedBy { it.where }
    }

    /** Every letter taken: drives that exist, and remembered mappings that currently do not. */
    fun takenLetters(): Set<Char> =
        File.listRoots().mapNotNull { DriveLetters.letterOf(it.path) }.toSet() +
            (enumerate(RESOURCE_CONNECTED) + enumerate(RESOURCE_REMEMBERED)).mapNotNull { DriveLetters.letterOf(it.first) }

    override fun mount(target: DavTarget, label: String): MountResult {
        // Already mapped - a second mapping of the same share on another letter is never wanted,
        // and the redirector would happily make one.
        attached().firstOrNull { it.target == target }?.let { return MountResult.Ok(it.where) }

        val letter = DriveLetters.pick(takenLetters())
            ?: return MountResult.Failed("Every drive letter from D: to Z: is in use. Disconnect one first.")
        val local = "$letter:"
        val res = Winnetwk.NETRESOURCE().apply {
            dwType = Winnetwk.RESOURCETYPE.RESOURCETYPE_DISK
            lpLocalName = local
            lpRemoteName = target.unc
            lpProvider = null
        }
        // Null user and password: the share is selected by the code in the path and asks for no
        // sign-in. Not CONNECT_INTERACTIVE, so Windows never puts up a credential prompt that
        // has no right answer.
        val rc = Mpr.INSTANCE.WNetAddConnection3(null, res, null, null, Winnetwk.ConnectFlag.CONNECT_UPDATE_PROFILE)
        return if (rc == WinNetErrors.NO_ERROR) MountResult.Ok(local)
        else MountResult.Failed(WinNetErrors.message(rc), rc)
    }

    override fun unmount(mount: Attached, force: Boolean): MountResult {
        // A drive whose server has gone has no open files worth protecting, and without force
        // the redirector waits on it.
        val rc = Mpr.INSTANCE.WNetCancelConnection2(
            mount.where, Winnetwk.ConnectFlag.CONNECT_UPDATE_PROFILE, force || !mount.connected,
        )
        when (rc) {
            WinNetErrors.NO_ERROR, WinNetErrors.NOT_CONNECTED -> Unit
            WinNetErrors.OPEN_FILES, WinNetErrors.DEVICE_IN_USE ->
                return MountResult.Failed(WinNetErrors.message(WinNetErrors.OPEN_FILES), rc, canForce = true)
            else -> return MountResult.Failed(WinNetErrors.message(rc), rc)
        }
        // Bug identified here: an inactive remembered drive stayed in Explorer after Unmount.
        // For a drive that is remembered but not connected, the cancel answers NOT_CONNECTED and
        // leaves the persistent entry in place - the one `CONNECT_UPDATE_PROFILE` wrote when it
        // was mounted, kept under HKCU\Network\<letter>. NOT_CONNECTED was taken as success
        // without looking, so the window said the drive was gone while Windows still drew it.
        // The entry is now removed directly, which is what `net use /delete` does for an
        // Unavailable drive, and the outcome is read back from Windows' own list.
        DriveLetters.letterOf(mount.where)?.let { forgetRemembered(it) }
        notifyExplorer(mount.where)
        val still = attached().any { it.where.equals(mount.where, ignoreCase = true) }
        return if (!still) MountResult.Ok(mount.where)
        else MountResult.Failed("Windows still lists ${mount.where} after disconnecting it. Signing out and back in clears it.", rc)
    }

    private fun forgetRemembered(letter: Char) {
        runCatching {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, "Network\\$letter")) {
                Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER, "Network", letter.toString())
            }
        }
    }

    /** Tells Explorer the drive went, or it keeps drawing the icon until it next refreshes. */
    private fun notifyExplorer(where: String) {
        runCatching { ShellNotify.INSTANCE.SHChangeNotify(SHCNE_DRIVEREMOVED, SHCNF_PATHW, WString("$where\\"), null) }
    }

    /** `SHChangeNotify`, which `jna-platform` does not bind. */
    private interface ShellNotify : StdCallLibrary {
        fun SHChangeNotify(eventId: Int, flags: Int, item1: WString?, item2: Pointer?)

        companion object {
            val INSTANCE: ShellNotify = Native.load("shell32", ShellNotify::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    /** (local name, remote name) for every disk connection in [scope]. Empty on any failure. */
    private fun enumerate(scope: Int): List<Pair<String?, String?>> {
        val handle = WinNT.HANDLEByReference()
        if (Mpr.INSTANCE.WNetOpenEnum(scope, Winnetwk.RESOURCETYPE.RESOURCETYPE_DISK, 0, null, handle) != 0) return emptyList()
        val out = ArrayList<Pair<String?, String?>>()
        try {
            val buf = Memory(BUFFER.toLong())
            val stride = Winnetwk.NETRESOURCE().size()
            while (true) {
                val count = IntByReference(-1)
                val size = IntByReference(BUFFER)
                val rc = Mpr.INSTANCE.WNetEnumResource(handle.value, count, buf, size)
                if (rc != 0) break // ERROR_NO_MORE_ITEMS (259) is the normal end
                for (i in 0 until count.value) {
                    val r = Winnetwk.NETRESOURCE(buf.share(i.toLong() * stride)).also { it.read() }
                    out += r.lpLocalName to r.lpRemoteName
                }
            }
        } finally {
            Mpr.INSTANCE.WNetCloseEnum(handle.value)
        }
        return out
    }

    private companion object {
        const val RESOURCE_CONNECTED = 1
        const val RESOURCE_REMEMBERED = 3
        const val BUFFER = 16 * 1024
        const val SHCNE_DRIVEREMOVED = 0x00000080
        const val SHCNF_PATHW = 0x0005
    }
}
