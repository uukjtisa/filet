package dev.niccc2007.filet.desktop.ui

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
    val label: String,
    /** The share's own figure; see [Probe] for why Explorer's cannot be trusted. Null until read. */
    val space: Space? = null,
    /** Null until probed. False means the OS still holds the drive and nothing answers behind it. */
    val answering: Boolean? = null,
    /** Why it is not answering, when the share said something more specific than silence. */
    val note: String? = null,
)

/**
 * The mounting half's state, kept current from the OS.
 *
 * The list of drives is re-read from the OS every [POLL_MS] and never edited in place. That is
 * what keeps a drive disconnected from Explorer, or reattached by Windows at login, from leaving
 * the window out of step: there is no second copy of "what is mounted" here to go stale.
 */
class MountController(
    private val mounter: Mounter,
    private val peers: StateFlow<List<Peer>>,
    private val scope: CoroutineScope,
) {
    private val _mounts = MutableStateFlow<List<MountView>>(emptyList())
    val mounts: StateFlow<List<MountView>> = _mounts.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** Why this platform cannot mount, or null. Read once off the event thread. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _letterFree = MutableStateFlow(true)
    val letterFree: StateFlow<Boolean> = _letterFree.asStateFlow()

    private val _limit = MutableStateFlow<WebClientLimit.Verdict?>(null)
    /** Windows' WebDAV file-size cap, or null off Windows. */
    val limit: StateFlow<WebClientLimit.Verdict?> = _limit.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Peer keys and drive names with an operation in flight. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, MountResult.Failed>>(emptyMap())
    /** The last refusal per peer key or drive name, cleared by the next attempt. */
    val errors: StateFlow<Map<String, MountResult.Failed>> = _errors.asStateFlow()

    /** Peer key -> the target mounted for it, so a row knows which drive is its own. */
    private val _remembered = MutableStateFlow<Map<String, DavTarget>>(emptyMap())
    val remembered: StateFlow<Map<String, DavTarget>> = _remembered.asStateFlow()

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
        _letterFree.value = (mounter as? WindowsMounter)?.let { m ->
            withContext(Dispatchers.IO) { runCatching { DriveLetters.pick(m.takenLetters()) != null }.getOrDefault(true) }
        } ?: true
        val p = peers.value
        val rem = _remembered.value
        _mounts.value = attached.map { a ->
            val s = spaces[a.target]
            val h = s?.second
            MountView(
                a, MountModel.labelFor(a, p, rem), s?.first,
                answering = if (!a.connected) false else h?.answering,
                note = h?.note,
            )
        }
    }

    /** Probe, then mount. [onDone] runs on the event thread with null on success. */
    fun mount(peer: Peer, code: String, onDone: (String?) -> Unit) {
        val target = MountModel.targetOf(peer, code) ?: return onDone("Type the access code shown on the device.")
        if (peer.key in _busy.value) return
        _busy.update { it + peer.key }
        _errors.update { it - peer.key }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Probe first: the share answers a wrong code with a distinct status in a second,
                // where the redirector folds it into one error after a long timeout.
                when (val pr = Probe.check(target)) {
                    is ProbeResult.Refused -> MountResult.Failed(pr.message, pr.status)
                    ProbeResult.Unreachable -> MountResult.Failed(ProbeErrors.UNREACHABLE)
                    is ProbeResult.Ok -> {
                        spaces[target] = pr.space to MountModel.Health(true)
                        mounter.mount(target, peer.name)
                    }
                }
            }
            if (result is MountResult.Ok) _remembered.update { it + (peer.key to target) }
            if (result is MountResult.Failed) _errors.update { it + (peer.key to result) }
            _busy.update { it - peer.key }
            refresh()
            onDone((result as? MountResult.Failed)?.message)
        }
    }

    fun unmount(view: MountView, force: Boolean = false) {
        val key = view.attached.where
        if (key in _busy.value) return
        _busy.update { it + key }
        _errors.update { it - key }
        scope.launch {
            // A drive nothing answers behind cannot have open files worth waiting on.
            val r = withContext(Dispatchers.IO) { mounter.unmount(view.attached, force || view.answering == false) }
            if (r is MountResult.Failed) _errors.update { it + (key to r) }
            _busy.update { it - key }
            refresh()
        }
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

    private companion object {
        const val POLL_MS = 3_000L
        /** Probe every share every this-many polls: about thirty seconds. */
        const val PROBE_EVERY = 10
    }
}
