package dev.niccc2007.filet.desktop.ui

import dev.niccc2007.filet.desktop.KnownPhone
import dev.niccc2007.filet.desktop.Peer
import dev.niccc2007.filet.desktop.mount.Attached
import dev.niccc2007.filet.desktop.mount.DavTarget
import dev.niccc2007.filet.desktop.mount.DriveLetters
import dev.niccc2007.filet.desktop.mount.MountResult
import dev.niccc2007.filet.desktop.mount.Mounter
import dev.niccc2007.filet.desktop.mount.Platform
import dev.niccc2007.filet.desktop.mount.Probe
import dev.niccc2007.filet.desktop.mount.ProbeErrors
import dev.niccc2007.filet.desktop.mount.ProbeResult
import dev.niccc2007.filet.desktop.mount.Space
import dev.niccc2007.filet.desktop.mount.WebClientLimit
import dev.niccc2007.filet.desktop.mount.WindowsMounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One mounted drive as the window shows it. */
data class MountView(
    val attached: Attached,
    /** The share's own figure; see [Probe] for why Explorer's cannot be trusted. Null until read. */
    val space: Space? = null,
    /** Null until probed. False means the OS still holds the drive and nothing answers behind it. */
    val answering: Boolean? = null,
    /** Why it is not answering, when the share said something more specific than silence. */
    val note: String? = null,
)

/**
 * The mounting half's state: the saved phones, and the drives as the OS reports them.
 *
 * The list of drives is re-read from the OS every [POLL_MS] and never edited in place. That is
 * what keeps a drive disconnected from Explorer, or reattached by Windows at login, from leaving
 * the window out of step: there is no second copy of "what is mounted" here to go stale. What is
 * kept is the other half, which the OS cannot say - which phone a drive is and what it is called -
 * in [known], saved through [savePhones] on every change.
 *
 * Every state change happens on [scope]'s thread, the window's; only the OS calls go to IO.
 */
class MountController(
    private val mounter: Mounter,
    private val peers: StateFlow<List<Peer>>,
    private val scope: CoroutineScope,
    loadPhones: () -> List<KnownPhone> = { emptyList() },
    private val savePhones: (List<KnownPhone>) -> Unit = {},
) {
    /** Windows mounts on a drive letter; everywhere else the OS picks a folder. */
    val usesLetters: Boolean = mounter.platform == Platform.WINDOWS

    private val _known = MutableStateFlow(loadPhones())
    /** Every phone share mounted here, mounted now or not. */
    val known: StateFlow<List<KnownPhone>> = _known.asStateFlow()

    private val _mounts = MutableStateFlow<List<MountView>>(emptyList())
    val mounts: StateFlow<List<MountView>> = _mounts.asStateFlow()

    private val _taken = MutableStateFlow<Set<Char>>(emptySet())
    /** Every drive letter in use, Filet's own included. Empty off Windows. */
    val taken: StateFlow<Set<Char>> = _taken.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** Why this platform cannot mount, or null. Read once off the event thread. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _letterFree = MutableStateFlow(true)
    val letterFree: StateFlow<Boolean> = _letterFree.asStateFlow()

    private val _limit = MutableStateFlow<WebClientLimit.Verdict?>(null)
    /** Windows' WebDAV file-size cap, or null off Windows. */
    val limit: StateFlow<WebClientLimit.Verdict?> = _limit.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Row ids with an operation in flight, plus "refresh" and "limit". */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, MountResult.Failed>>(emptyMap())
    /** The last refusal per row id, cleared by the next attempt. */
    val errors: StateFlow<Map<String, MountResult.Failed>> = _errors.asStateFlow()

    private val spaces = ConcurrentHashMap<DavTarget, Pair<Space?, MountModel.Health>>()

    fun start() {
        scope.launch {
            withContext(Dispatchers.IO) {
                _problem.value = mounter.unavailable()
                if (mounter.platform == Platform.WINDOWS) _limit.value = WebClientLimit.read()
            }
            var tick = 0
            while (isActive) {
                refresh(probe = tick % PROBE_EVERY == 0)
                tick++
                delay(POLL_MS)
            }
        }
    }

    suspend fun refresh(probe: Boolean = false) {
        val attached = withContext(Dispatchers.IO) { runCatching { mounter.attached() }.getOrDefault(emptyList()) }
        val fresh = attached.filter { !spaces.containsKey(it.target) }
        if (probe || fresh.isNotEmpty()) withContext(Dispatchers.IO) {
            for (a in if (probe) attached else fresh) {
                val r = Probe.check(a.target)
                val before = spaces[a.target]
                spaces[a.target] = ((r as? ProbeResult.Ok)?.space ?: before?.first) to MountModel.nextHealth(before?.second, r)
            }
        }
        spaces.keys.retainAll(attached.map { it.target }.toSet())
        val taken = (mounter as? WindowsMounter)?.let { m ->
            withContext(Dispatchers.IO) { runCatching { m.takenLetters() }.getOrDefault(emptySet()) }
        } ?: emptySet()
        _taken.value = taken
        _letterFree.value = !usesLetters || DriveLetters.pick(taken) != null
        // A drive mapped before the list was saved still gets a row, so it can be unmounted here.
        MountModel.adopt(_known.value, attached, peers.value).let { if (it != _known.value) save(it) }
        _mounts.value = attached.map { a ->
            val s = spaces[a.target]
            val h = s?.second
            MountView(a, s?.first, answering = if (!a.connected) false else h?.answering, note = h?.note)
        }
    }

    /**
     * Probe, free the letter if another Filet drive holds it, mount, and save the row.
     *
     * The address comes from the live peer when there is one, so a phone that came back on a new
     * address is mounted there, and from the saved entry otherwise. [onDone] runs on the event
     * thread with null on success.
     */
    fun mount(row: MountModel.PhoneRow, label: String, code: String, letter: MountModel.LetterChoice?, onDone: (String?) -> Unit) {
        val clean = DavTarget.cleanCode(code) ?: return onDone("Type the access code shown on the device.")
        val target = row.peer?.let { MountModel.targetOf(it, clean) } ?: row.phone?.target?.copy(code = clean)
            ?: return onDone("This device has left the network.")
        val name = label.trim().ifEmpty { row.peer?.name ?: row.phone?.name.orEmpty() }
        val id = row.id
        if (id in _busy.value) return
        _busy.update { it + id }
        _errors.update { it - id }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Probe first: the share answers a wrong code with a distinct status in a second,
                // where the redirector folds it into one error after a long timeout.
                when (val pr = Probe.check(target)) {
                    is ProbeResult.Refused -> MountResult.Failed(pr.message, pr.status)
                    ProbeResult.Unreachable -> MountResult.Failed(ProbeErrors.UNREACHABLE)
                    is ProbeResult.Ok -> {
                        spaces[target] = pr.space to MountModel.Health(true)
                        free(letter) ?: mounter.mount(target, name, letter?.letter)
                    }
                }
            }
            if (result is MountResult.Ok) {
                val peer = row.peer
                save(MountModel.upsert(_known.value, KnownPhone(
                    id = id,
                    name = peer?.name ?: row.phone?.name ?: name,
                    label = name,
                    host = target.host, port = target.port, code = target.code,
                    secure = target.secure, base = target.base,
                    deviceId = peer?.deviceId?.takeIf { it.isNotBlank() } ?: row.phone?.deviceId.orEmpty(),
                    letter = DriveLetters.letterOf(result.where) ?: row.phone?.letter,
                )))
            }
            finish(id, result)
            onDone((result as? MountResult.Failed)?.message)
        }
    }

    fun unmount(row: MountModel.PhoneRow, view: MountView?, force: Boolean = false) {
        val drive = row.drive ?: return
        val id = row.id
        if (id in _busy.value) return
        _busy.update { it + id }
        _errors.update { it - id }
        scope.launch {
            // A drive nothing answers behind cannot have open files worth waiting on.
            val r = withContext(Dispatchers.IO) { mounter.unmount(drive, force || view?.answering == false) }
            finish(id, r)
        }
    }

    /**
     * Call the drive [label] here, and in Explorer when it is mounted.
     *
     * The saved name changes even if the OS refuses, so the row shows what was asked for and the
     * refusal says why Explorer does not.
     */
    fun rename(row: MountModel.PhoneRow, label: String, onDone: (String?) -> Unit) {
        val name = label.trim().ifEmpty { return onDone("Type a name.") }
        val phone = row.phone ?: return onDone("Mount it first; the name is set in the mount dialog.")
        save(_known.value.map { if (it.id == phone.id) it.copy(label = name) else it })
        val drive = row.drive ?: return onDone(null)
        val id = row.id
        if (id in _busy.value) return
        _busy.update { it + id }
        _errors.update { it - id }
        scope.launch {
            val r = withContext(Dispatchers.IO) { mounter.relabel(drive, name) }
            finish(id, r)
            onDone((r as? MountResult.Failed)?.message)
        }
    }

    /**
     * Move a mounted drive to another letter: unmount, free the new letter if a Filet drive holds
     * it, and mount again.
     *
     * Windows has no call that moves a mapping, so this is the only way. If the second mount
     * fails, the drive is put back on its old letter, so a refused move does not also lose it.
     */
    fun changeLetter(row: MountModel.PhoneRow, choice: MountModel.LetterChoice, onDone: (String?) -> Unit) {
        val drive = row.drive ?: return onDone("It is not mounted.")
        val phone = row.phone ?: return onDone("It is not mounted.")
        val id = row.id
        if (id in _busy.value) return
        _busy.update { it + id }
        _errors.update { it - id }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                (mounter.unmount(drive) as? MountResult.Failed) ?: run {
                    val moved = free(choice) ?: mounter.mount(drive.target, phone.label, choice.letter)
                    if (moved is MountResult.Failed) mounter.mount(drive.target, phone.label, DriveLetters.letterOf(drive.where))
                    moved
                }
            }
            if (r is MountResult.Ok) save(_known.value.map { if (it.id == phone.id) it.copy(letter = DriveLetters.letterOf(r.where)) else it })
            finish(id, r)
            onDone((r as? MountResult.Failed)?.message)
        }
    }

    /**
     * Move a drive left on an old address to the one its phone answers on now: probe the new
     * address with the same code, unmount the old drive, and mount on the same letter.
     *
     * A button, not automatic, until moved devices are detected on their own.
     */
    fun remount(row: MountModel.PhoneRow, view: MountView?, onDone: (String?) -> Unit) {
        val drive = row.drive ?: return onDone("It is not mounted.")
        val peer = MountModel.movedTo(row) ?: return onDone("The phone is already at that address.")
        val phone = row.phone ?: return onDone("It is not mounted.")
        val target = drive.target.copy(host = peer.host, port = peer.port, secure = peer.secure)
        val id = row.id
        if (id in _busy.value) return
        _busy.update { it + id }
        _errors.update { it - id }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                when (val pr = Probe.check(target)) {
                    is ProbeResult.Refused -> MountResult.Failed(pr.message, pr.status)
                    ProbeResult.Unreachable -> MountResult.Failed(ProbeErrors.UNREACHABLE)
                    is ProbeResult.Ok -> {
                        spaces[target] = pr.space to MountModel.Health(true)
                        // The old address answers nothing, so nothing open on it can be saved by waiting.
                        (mounter.unmount(drive, force = view?.answering == false) as? MountResult.Failed)
                            ?: mounter.mount(target, phone.label, DriveLetters.letterOf(drive.where))
                    }
                }
            }
            if (r is MountResult.Ok) save(MountModel.upsert(_known.value, phone.copy(
                host = target.host, port = target.port, secure = target.secure,
                deviceId = peer.deviceId.ifBlank { phone.deviceId },
                letter = DriveLetters.letterOf(r.where) ?: phone.letter,
            )))
            finish(id, r)
            onDone((r as? MountResult.Failed)?.message)
        }
    }

    /** Drop a saved phone that is not mounted. A mounted one has to be unmounted first. */
    fun forget(row: MountModel.PhoneRow) {
        if (row.drive != null || row.phone == null) return
        _errors.update { it - row.id }
        save(_known.value.filterNot { it.id == row.phone.id })
    }

    /**
     * Ask again now: re-read the drives, probe every one, and restart discovery.
     *
     * Discovery is passive - a host that announced while this PC was asleep, or whose packet was
     * lost, is not heard until it announces again. [rescan] is the watch's own restart.
     */
    fun refreshNow(rescan: () -> Unit) {
        if ("refresh" in _busy.value) return
        _busy.update { it + "refresh" }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { rescan() } }
            refresh(probe = true)
            _busy.update { it - "refresh" }
        }
    }

    fun raiseLimit() {
        if ("limit" in _busy.value) return
        _busy.update { it + "limit" }
        scope.launch {
            _limit.value = withContext(Dispatchers.IO) { WebClientLimit.raise() }
            _busy.update { it - "limit" }
        }
    }

    /** Unmount the Filet drive on [choice]'s letter when it replaces one. Null when nothing failed. */
    private fun free(choice: MountModel.LetterChoice?): MountResult.Failed? {
        val holder = choice?.replaces ?: return null
        val drive = mounter.attached().firstOrNull { DriveLetters.letterOf(it.where) == choice.letter } ?: return null
        val r = mounter.unmount(drive) as? MountResult.Failed ?: return null
        return r.copy(message = "${choice.letter}: ($holder) could not be unmounted. ${r.message}")
    }

    private fun save(phones: List<KnownPhone>) {
        _known.value = phones
        runCatching { savePhones(phones) }
    }

    private suspend fun finish(id: String, r: MountResult) {
        if (r is MountResult.Failed) _errors.update { it + (id to r) }
        _busy.update { it - id }
        refresh()
    }

    private companion object {
        const val POLL_MS = 3_000L
        /** Probe every share every this-many polls: about thirty seconds. */
        const val PROBE_EVERY = 10
    }
}
