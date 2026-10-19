package dev.niccc2007.filet.desktop.mount

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Files
import java.nio.file.Path

/**
 * macOS: `NetFSMountURLSync`, the call behind Finder's Connect to Server.
 *
 * Mounted under `/Volumes` with no credentials. Unmounted with `unmount(2)` - a syscall, so the
 * error is an errno like everything else here. These bindings are declared here rather than taken
 * from `jna-platform`, which maps neither NetFS nor the handful of CoreFoundation calls used.
 *
 * Not verified on a Mac: there is none to run it on, which is also why the build has no DMG.
 */
class MacMounter : Mounter {
    override val platform = Platform.MAC

    private interface NetFS : Library {
        @Suppress("FunctionName")
        fun NetFSMountURLSync(
            url: Pointer, mountPath: Pointer?, user: Pointer?, passwd: Pointer?,
            openOptions: Pointer?, mountOptions: Pointer?, mountpoints: PointerByReference,
        ): Int
    }

    @Suppress("FunctionName")
    private interface CF : Library {
        fun CFStringCreateWithCString(alloc: Pointer?, s: String, encoding: Int): Pointer?
        fun CFURLCreateWithString(alloc: Pointer?, s: Pointer, base: Pointer?): Pointer?
        fun CFArrayGetCount(a: Pointer): Long
        fun CFArrayGetValueAtIndex(a: Pointer, i: Long): Pointer
        fun CFStringGetCString(s: Pointer, buf: ByteArray, size: Long, encoding: Int): Boolean
        fun CFRelease(p: Pointer)
    }

    private interface LibC : Library {
        fun unmount(dir: String, flags: Int): Int
    }

    private val netfs by lazy { Native.load("NetFS", NetFS::class.java) }
    private val cf by lazy { Native.load("CoreFoundation", CF::class.java) }
    private val libc by lazy { Native.load("c", LibC::class.java) }

    override fun unavailable(): String? = runCatching { netfs; null }.getOrElse { "The NetFS framework could not be loaded." }

    /**
     * WebDAV volumes, from the file-store type. `BsdFileStore` reports `statfs.f_fstypename`,
     * which is `webdav` for these, and the mount's source URL is its name.
     */
    override fun attached(): List<Attached> = runCatching {
        Files.newDirectoryStream(Path.of("/Volumes")).use { dirs ->
            dirs.mapNotNull { p ->
                val store = runCatching { Files.getFileStore(p) }.getOrNull() ?: return@mapNotNull null
                if (store.type() != "webdav") return@mapNotNull null
                LinuxMounts.parseUrl(store.name())?.let { Attached(it, p.toString()) }
            }
        }
    }.getOrDefault(emptyList())

    override fun mount(target: DavTarget, label: String): MountResult {
        attached().firstOrNull { it.target == target }?.let { return MountResult.Ok(it.where) }
        val str = cf.CFStringCreateWithCString(null, target.url, UTF8) ?: return MountResult.Failed("Could not build the address.")
        val url = cf.CFURLCreateWithString(null, str, null)
        cf.CFRelease(str)
        url ?: return MountResult.Failed("macOS would not accept the address ${target.url}.")
        val points = PointerByReference()
        val rc = try {
            netfs.NetFSMountURLSync(url, null, null, null, null, null, points)
        } finally {
            cf.CFRelease(url)
        }
        if (rc != 0) return MountResult.Failed(NetFsErrors.message(rc), rc)
        val where = points.value?.let { arr ->
            val first = if (cf.CFArrayGetCount(arr) > 0) cf.CFArrayGetValueAtIndex(arr, 0) else null
            val text = first?.let { s -> ByteArray(1024).also { cf.CFStringGetCString(s, it, 1024, UTF8) }.let { String(it).substringBefore('\u0000') } }
            cf.CFRelease(arr)
            text
        } ?: "/Volumes"
        return MountResult.Ok(where)
    }

    override fun unmount(mount: Attached, force: Boolean): MountResult {
        val rc = libc.unmount(mount.where, if (force) MNT_FORCE else 0)
        if (rc == 0 || attached().none { it.where == mount.where }) return MountResult.Ok(mount.where)
        val errno = Native.getLastError()
        return MountResult.Failed(NetFsErrors.message(errno), errno, canForce = errno == 16)
    }

    private companion object {
        const val UTF8 = 0x08000100
        const val MNT_FORCE = 0x00080000
    }
}
