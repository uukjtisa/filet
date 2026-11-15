package dev.niccc2007.filet.storage

import android.content.Context
import dev.niccc2007.filet.data.DriveRegistry
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.provider.Removables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The SD cards and USB drives plugged in now, kept for the whole process.
 *
 * On the graph rather than a screen, because a drive is plugged in whether or not Home is open, and
 * the registry has to see it either way. A mount broadcast re-reads the volumes, brings the
 * registry up to date, shows a hidden drive's card again when it arrives, and tells the screens
 * through [changed] so the cards follow without a restart.
 */
class Drives(
    private val context: Context,
    private val prefs: Prefs,
    scope: CoroutineScope,
) {
    private val _plugged = MutableStateFlow<List<Removables.Drive>>(emptyList())

    /** Every removable volume the system lists now, readable or not. */
    val plugged: StateFlow<List<Removables.Drive>> = _plugged.asStateFlow()

    private val _otg = MutableStateFlow(false)

    /**
     * A USB storage device is attached and no USB drive shows up for it at all: some phones only
     * mount a drive with OTG turned on in Settings, and some turn it off by themselves.
     */
    val otg: StateFlow<Boolean> = _otg.asStateFlow()

    private val _arrived = MutableStateFlow<Set<String>>(emptySet())

    /** Volume IDs plugged in since the cards were last drawn, for the arrival animation. */
    val arrived: StateFlow<Set<String>> = _arrived.asStateFlow()

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Something was plugged in, pulled out or changed state. */
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    private val lock = Mutex()

    init {
        scope.launch(Dispatchers.IO) { read() }
        Removables.watch(context) {
            scope.launch(Dispatchers.IO) {
                // The broadcast lands as the volume starts mounting; its path and size are there
                // a moment later. Two reads: one now, one once it has settled.
                read()
                _changed.tryEmit(Unit)
                delay(SETTLE_MS)
                read()
                _changed.tryEmit(Unit)
            }
        }
    }

    /** Read the volumes again and bring everything that depends on them up to date. */
    suspend fun read() = lock.withLock {
        val now = Removables.plugged(context)
        _plugged.value = now
        val readable = now.filter { it.path != null }
        val known = prefs.knownDrives.value
        val arrived = DriveRegistry.arrived(known, readable.map { it.uuid }.toSet())
        prefs.setKnownDrives(
            DriveRegistry.seen(
                known,
                readable.map { DriveRegistry.Seen(it.uuid, kindOf(it.kind), it.description, it.path!!, it.total) },
                System.currentTimeMillis(),
            ),
        )
        // A drive hidden from Home shows again the moment it is plugged in: hiding it was about
        // the drive not being wanted while it sat there, not about never wanting to see it.
        for (d in readable) if (d.uuid in arrived) prefs.showCard(cardId(d.path!!))
        if (arrived.isNotEmpty()) _arrived.value = _arrived.value + arrived
        // Any USB volume at all, readable or not, means the drive did show up: an unreadable one
        // has its own card, and blaming OTG beside it would be wrong.
        _otg.value = now.none { it.kind == Removables.Kind.USB } && Removables.usbStorageAttached(context)
    }

    /** The arrival animation has played for these. */
    fun shown(ids: Set<String>) {
        _arrived.value = _arrived.value - ids
    }

    /** The plugged-in drive mounted at [path], if [path] is one. */
    fun at(path: String): Removables.Drive? = _plugged.value.firstOrNull { it.path == path }

    /** Forget a drive that is not plugged in: the registry entry, its name and its card's place. */
    fun forget(uuid: String) {
        prefs.setKnownDrives(DriveRegistry.forget(prefs.knownDrives.value, uuid))
    }

    fun setIndexed(uuid: String, on: Boolean) {
        prefs.setKnownDrives(DriveRegistry.setIndexed(prefs.knownDrives.value, uuid, on))
    }

    companion object {
        private const val SETTLE_MS = 1_500L

        fun kindOf(k: Removables.Kind): DriveRegistry.Kind = when (k) {
            Removables.Kind.SD -> DriveRegistry.Kind.SD
            Removables.Kind.USB -> DriveRegistry.Kind.USB
        }

        /** The id Home keys a local drive's card by - its hidden state, its place, its name. */
        fun cardId(path: String): String = VPath.of("local", path).toString()
    }
}
