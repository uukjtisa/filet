package dev.niccc2007.filet.vfs.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * The SD cards and USB drives the system knows about.
 *
 * Asked of `StorageManager` rather than read off the folders under `/storage`, because only the
 * system knows which volume is removable, what it is called, and whether it could be mounted at
 * all - a drive in a format the phone does not read has a volume and no folder, and reading the
 * folders alone made it vanish instead of saying so.
 *
 * Here and not in the app, for the same reason as [AndroidStorage]: this is storage knowledge, and
 * PLAN.md R3 keeps it in this package.
 */
object Removables {

    enum class Kind { SD, USB }

    /** What can be done with a drive right now. */
    enum class State {
        /** Mounted and readable; writable too where the phone allows it. */
        READY,

        /** Mounted, and the system itself will not write to it. */
        READ_ONLY,

        /** Plugged in, in a format the phone does not read - most often NTFS or exFAT on an old phone. */
        UNREADABLE,

        /** Being checked, ejected or prepared: there for a moment, not usable yet. */
        BUSY,
    }

    /**
     * One removable volume.
     *
     * @param uuid the volume ID - `1A2B-3C4D` on FAT. What a drive is remembered by: it is written
     *   into the volume itself, so it survives unplugging and does not depend on the port or the
     *   order drives were plugged in.
     * @param path where it is mounted, or null while it is not readable.
     * @param total its size in bytes, or null while it is not readable.
     */
    data class Drive(
        val uuid: String,
        val kind: Kind,
        val description: String,
        val path: String?,
        val state: State,
        val total: Long? = null,
    )

    /** Every removable volume the system lists now, the phone's own storage excluded. */
    fun plugged(context: Context): List<Drive> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return runCatching { sm.storageVolumes }.getOrDefault(emptyList())
            .filter { it.isRemovable && !it.isPrimary }
            .mapNotNull { v ->
                // A volume with no ID cannot be told apart from the next one, so it is not offered
                // as a drive at all rather than risk being remembered as another.
                val uuid = v.uuid ?: return@mapNotNull null
                val description = runCatching { v.getDescription(context) }.getOrNull().orEmpty()
                val state = stateOf(v.state)
                val path = when {
                    state != State.READY && state != State.READ_ONLY -> null
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> v.directory?.absolutePath
                    else -> "/storage/$uuid".takeIf { File(it).isDirectory }
                }
                val total = path?.let { runCatching { File(it).totalSpace }.getOrNull()?.takeIf { t -> t > 0 } }
                Drive(uuid, kindOf(description), description, path, state, total)
            }
    }

    /**
     * SD or USB, from what the system calls the volume.
     *
     * Decision: the description, because the public API has no kind. Every vendor names a USB
     * volume with "USB" in it ("USB drive", "USB storage", "Kingston USB") and an SD card without,
     * and the hidden disk flags that would say it directly are not reachable. A drive read the
     * wrong way round costs only its icon and whether it is indexed unasked.
     */
    fun kindOf(description: String): Kind =
        if (description.contains("usb", ignoreCase = true) || description.contains("otg", ignoreCase = true)) Kind.USB else Kind.SD

    fun stateOf(state: String?): State = when (state) {
        Environment.MEDIA_MOUNTED -> State.READY
        Environment.MEDIA_MOUNTED_READ_ONLY -> State.READ_ONLY
        Environment.MEDIA_UNMOUNTABLE, Environment.MEDIA_NOFS, "unsupported" -> State.UNREADABLE
        else -> State.BUSY
    }

    /**
     * Whether the phone lets an app with all-files access write to a removable volume by its path.
     * From Android 11 it does; before that, only through a folder granted in the system picker.
     */
    val writesByPath: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * The document the system picker should open on, to grant the whole of [uuid]: its root in
     * the external storage provider. The tree it grants has the document ID `<uuid>:`, which is
     * how [grantedRootOf] recognises it afterwards.
     */
    fun pickerStart(uuid: String): android.net.Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_AUTHORITY, "$uuid:")

    /** The volume ID a granted tree is the root of, or null for any other tree. */
    fun grantedRootOf(tree: android.net.Uri): String? {
        if (tree.authority != EXTERNAL_AUTHORITY) return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        return id.removeSuffix(":").takeIf { id.endsWith(":") && it.isNotEmpty() && ':' !in it }
    }

    /** The volume ID a granted tree's root [path] (a `saf://` path) is the whole of, or null. */
    fun grantedVolumeOf(path: dev.niccc2007.filet.vfs.VPath): String? {
        if (path.scheme != "saf") return null
        val tree = path.segments.firstOrNull() ?: return null
        return runCatching { grantedRootOf(android.net.Uri.parse(android.net.Uri.decode(tree))) }.getOrNull()
    }

    /** Android's storage settings, where a drive is ejected. Only the system can unmount one. */
    fun ejectIntent(): Intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)

    /**
     * Whether a USB device that stores files is attached. Asked so a drive the phone is not
     * reading can be told apart from no drive at all - some phones switch OTG off by themselves
     * after a while, and then a plugged-in drive is simply not there.
     */
    fun usbStorageAttached(context: Context): Boolean {
        val um = context.getSystemService(UsbManager::class.java) ?: return false
        return runCatching {
            um.deviceList.values.any { d ->
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
            }
        }.getOrDefault(false)
    }

    /**
     * Calls [onChange] whenever a volume is mounted, unmounted, removed or ejected, and whenever a
     * USB device comes or goes. Returns the way to stop.
     */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onChange()
        }
        val media = IntentFilter().apply {
            for (a in MEDIA_ACTIONS) addAction(a)
            addDataScheme("file")
        }
        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // System broadcasts: nothing else can send them, so not exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, media, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(receiver, usb, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, media)
            context.registerReceiver(receiver, usb)
        }
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"

    private val MEDIA_ACTIONS = listOf(
        Intent.ACTION_MEDIA_MOUNTED,
        Intent.ACTION_MEDIA_UNMOUNTED,
        Intent.ACTION_MEDIA_REMOVED,
        Intent.ACTION_MEDIA_BAD_REMOVAL,
        Intent.ACTION_MEDIA_EJECT,
        Intent.ACTION_MEDIA_UNMOUNTABLE,
        Intent.ACTION_MEDIA_NOFS,
        Intent.ACTION_MEDIA_CHECKING,
    )
}
