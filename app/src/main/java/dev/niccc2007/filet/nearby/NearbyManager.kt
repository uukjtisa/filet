package dev.niccc2007.filet.nearby

import android.content.Context
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.Vfs
import dev.niccc2007.filet.vfs.provider.net.PeerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One object the UI talks to for everything Nearby.
 *
 * Sharing is a *mode you enter*, and this is what it means to enter it: create the folders,
 * start the server, announce on mDNS, and put a visible foreground notification up. Stopping
 * reverses all four. Nothing here runs at startup.
 */
class NearbyManager(
    private val context: Context,
    private val vfs: Vfs,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    /** Where everything the share does is logged, as other jobs are. Null in tests. */
    private val ledger: dev.niccc2007.filet.jobs.JobLedger? = null,
) {
    val shared = SharedSet(context, vfs, prefs)
    val discovery = NearbyDiscovery(context)

    /**
     * Where a file somebody sends you lands.
     *
     * One folder, never the shared set: something arriving from the network must not become
     * something Filet is offering back out. It has always been here; round 7 is what made it
     * reachable from the screen that fills it.
     */
    val receivedFolder: VPath get() = shared.quarantine

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val server = NearbyHttpServer(
        context = context,
        vfs = vfs,
        shared = shared,
        deviceName = { PairedPeers.selfName(context) },
        onEvent = { _state.value = it },
        onServed = { s -> logServed(s) },
        onSending = { name, who, total -> sending(name, who, total) },
        onInbox = { p, b, a -> inbox.handle(p, b, a) },
        onFastHit = { k, a, fetching -> fastHit(k, a, fetching) },
    )

    private val _fastSeen = MutableStateFlow<Map<String, Map<String, FastSeen>>>(emptyMap())

    /**
     * What this phone's server has seen of each fast share: by the share's key, then by the
     * address that asked. The fast share sheet reads it beside each push's own reply.
     */
    val fastSeen: StateFlow<Map<String, Map<String, FastSeen>>> = _fastSeen.asStateFlow()

    private fun fastHit(key: String, address: String, fetching: Boolean) {
        val host = NetHost.plain(address)
        _fastSeen.update { all ->
            val seen = all[key].orEmpty()
            all + (key to (seen + (host to PushRow.seen(seen[host], fetching))))
        }
    }

    /** Pushes from other Filets. See [PushInbox]. */
    val inbox: PushInbox by lazy {
        PushInbox(context, vfs, prefs, scope, shared.quarantine, ledger) { PairedPeers.selfName(context) }
    }

    private val listener = PushListener(
        handle = { p, b, a -> inbox.handle(p, b, a) },
        info = { org.json.JSONObject().put("name", PairedPeers.selfName(context)).put("app", "Filet").put("inbox", true) },
    )

    /** Filet is on screen: the inbox is open. Set by [openInbox] and [closeInbox]. */
    @Volatile private var inFront = false

    /** The same, for the incoming card: a send asks inside Filet while it is on screen. */
    private val front = MutableStateFlow(false)

    /**
     * [InboxService] is keeping the inbox open with Filet in the background. See [holdInboxOpen].
     */
    @Volatile private var held = false

    /** Share › Incoming files. See [IncomingStore]. */
    val incoming = IncomingStore(prefs)

    /** The card over other apps. Lazy: nothing of it exists until a send asks with Filet away. */
    private val overlay by lazy { IncomingOverlay(context, prefs) }

    /** The offer the notification is up for, so a repeat emission does not sound it twice. */
    private var noticed: String? = null

    init {
        // One place decides where a send asks: inside Filet, over other apps, or in the shade.
        scope.launch(Dispatchers.Main.immediate) {
            combine(inbox.asking, front, incoming.settings) { a, f, s -> Triple(a, f, s) }
                .collect { (offer, inFront, settings) -> present(offer, inFront, settings) }
        }
        // Ready while Filet is closed, however it was changed: the switch, the settings file or
        // an import. Turning it on starts the service only from the screen - Android refuses a
        // start from the background - and the next time Filet is opened starts it otherwise.
        scope.launch(Dispatchers.Main.immediate) {
            incoming.settings.map { it.ready }.distinctUntilChanged().drop(1).collect { on ->
                if (on) InboxService.start(context) else InboxService.stop(context)
            }
        }
    }

    private fun present(offer: PushOffer?, inFront: Boolean, settings: IncomingSettings) {
        if (offer == null) {
            overlay.hide()
            IncomingNotice.cancel(context)
            noticed = null
            return
        }
        when (IncomingRules.place(inFront, overlay.canDraw(), settings)) {
            // Drawn over Filet's own screen by IncomingHost, in the same look.
            AskPlace.IN_FILET -> {
                overlay.hide()
                IncomingNotice.cancel(context)
                noticed = null
            }
            AskPlace.NOTIFICATION -> {
                overlay.hide()
                if (noticed != offer.id) IncomingNotice.show(context, offer)
                noticed = offer.id
            }
            AskPlace.OVER_APPS -> {
                IncomingNotice.cancel(context)
                noticed = null
                overlay.show(offer, IncomingRules.look(settings, offer.files.size), settings, inbox)
            }
        }
    }

    /**
     * Filet came on screen: pushes from other Filets can arrive. While sharing is on, its own
     * server answers them; otherwise the inbox opens its own door and announces it, so Filets
     * nearby list this phone. Not without the local network permission, which an announcement
     * without it would make Android ask for in the middle of something else.
     */
    fun openInbox() {
        inFront = true
        front.value = true
        openDoor()
        // Started from the screen, which is the one place Android allows it. Already running is
        // a no-op for the system.
        if (incoming.settings.value.ready) InboxService.start(context)
    }

    /** Filet left the screen: the inbox's own door shuts, unless it is being held open. A running share keeps answering. */
    fun closeInbox() {
        inFront = false
        front.value = false
        if (!held) shutDoor()
    }

    /** [InboxService] started: the door stays open with Filet in the background. */
    fun holdInboxOpen() {
        held = true
        openDoor()
    }

    /** [InboxService] stopped: the door goes the way Filet being on screen or not says. */
    fun releaseInbox() {
        held = false
        if (!inFront) shutDoor()
    }

    private fun openDoor() {
        if (server.isRunning() || !LocalNetwork.allowed(context)) return
        scope.launch(Dispatchers.IO) {
            val port = listener.open()
            if (port > 0 && !server.isRunning()) runCatching { discovery.advertise(port) }
        }
    }

    private fun shutDoor() {
        scope.launch(Dispatchers.IO) {
            if (!listener.isOpen) return@launch
            listener.close()
            if (!server.isRunning()) runCatching { discovery.stopAdvertising() }
        }
    }

    /**
     * Offer [f] to [peer], AirDrop-like: it asks there, or arrives without asking from a phone
     * that set this one to Always accept. Sharing must be on, because the other phone fetches
     * from this one. Answers "asked", "accepted", "held" or "blocked" as the other phone said,
     * [PushReply.UNREACHED] or [PushReply.UNANSWERED] when it said nothing, or null when this
     * phone is not sharing.
     */
    suspend fun push(peer: Peer, f: FastShare): String? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val s = _state.value
        if (!s.running) return@withContext null
        val files = org.json.JSONArray()
        for (t in f.tokens) {
            val e = shared.byToken(t) ?: continue
            val node = runCatching { vfs.stat(e.path) }.getOrNull() ?: continue
            files.put(org.json.JSONObject().put("name", e.alias).put("size", node.size).put("dir", node.isDir))
        }
        val body = org.json.JSONObject()
            .put("from", PairedPeers.serviceName(context))
            .put("name", PairedPeers.selfName(context))
            .put("port", s.port)
            .put("key", f.key)
            .put("files", files)
        PairedPeers.tokenFor(context, peer.uuid)?.let { body.put("proof", PushRules.proof(it, f.key)) }
        val started = android.os.SystemClock.elapsedRealtime()
        val posted = PushInbox.postFor("${peer.base}/api/push", body)
        dev.niccc2007.filet.log.FiletLog.i("nearby") {
            "push to ${peer.base}: ${posted::class.simpleName} in ${android.os.SystemClock.elapsedRealtime() - started} ms"
        }
        when (posted) {
            is PushInbox.Companion.Posted.Reply -> {
                val answer = posted.json.optString("answer").ifEmpty { null } ?: return@withContext PushReply.UNANSWERED
                pushedTo.getOrPut(f.key) { mutableSetOf() } += peer.base
                log("Pushed ${files.length().let { if (it == 1) files.getJSONObject(0).optString("name") else "$it files" }} to ${peer.label}", answer)
                answer
            }
            PushInbox.Companion.Posted.Unanswered -> {
                // It may have landed: Stop must still reach it to take the push back.
                pushedTo.getOrPut(f.key) { mutableSetOf() } += peer.base
                PushReply.UNANSWERED
            }
            PushInbox.Companion.Posted.Unreached -> {
                discovery.refresh(peer)
                PushReply.UNREACHED
            }
        }
    }

    private val pushedTo = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** Stop a fast share: its key stops working, and phones it was pushed to drop the push. */
    /**
     * Whether the share running now was started by a fast share rather than by Share over this
     * network. Any stop clears it, so a share turned on by hand afterwards is never taken down by a
     * fast share ending.
     */
    @Volatile private var startedForFast = false

    /**
     * Ends the fast share [key]: its key, the files it put on the Share page, and - when it was
     * the last fast share and sharing was on only for it - the share over this network.
     *
     * Bug identified here: this stopped the key alone. Sharing that the fast share had switched
     * on stayed on after Stop and after the dialog closed, so the phone went on announcing itself
     * and answering on the network with nothing left to hand out.
     */
    fun stopFast(key: String) {
        shared.stopFast(key)
        val now = System.currentTimeMillis()
        if (FastEnd.stopsSharing(startedForFast, shared.fast.value.count { !it.isExpired(now) })) stop()
        _fastSeen.update { it - key }
        val to = pushedTo.remove(key) ?: return
        scope.launch(Dispatchers.IO) {
            val body = org.json.JSONObject().put("from", PairedPeers.serviceName(context)).put("key", key)
            for (base in to) PushInbox.post("$base/api/push/cancel", body)
        }
    }

    /**
     * A download by someone else, on the Activity page as a finished job of its own. Its end
     * is the small message the Activity settings switch, so the share itself never pops up
     * anything else.
     */
    private fun logServed(s: Served) {
        val detail = if (s.ended) "${s.who} · one-time link, now stopped" else s.who
        s.row?.let { it.done("${s.name} downloaded", detail); return }
        val l = ledger ?: return
        val id = l.start(
            title = "${s.name} downloaded",
            subtitle = s.who,
            source = "Nearby",
            kind = dev.niccc2007.filet.jobs.JobKind.SERVED,
        )
        l.finish(id, detail)
    }

    /** A large download's row while it goes; it becomes the finished row, or leaves if dropped. */
    private fun sending(name: String, who: String, total: Long): Sending? {
        val l = ledger ?: return null
        val id = l.start(
            title = "Sending $name",
            subtitle = who,
            source = "Nearby",
            kind = dev.niccc2007.filet.jobs.JobKind.SERVED,
        )
        return object : Sending {
            private var shown = 0L
            override fun moved(sent: Long) {
                if (sent - shown < SHOW_EVERY && sent < total) return
                shown = sent
                l.progress(id, (sent.toFloat() / total).coerceIn(0f, 1f), who, doneBytes = sent, totalBytes = total)
            }
            override fun dropped() = l.remove(id)
            override fun done(title: String, detail: String) {
                l.retitle(id, title)
                l.finish(id, detail)
            }
        }
    }

    /** A step of the share itself (started, stopped, paused), logged with no message. */
    fun log(title: String, detail: String = "") {
        val l = ledger ?: return
        val id = l.start(title = title, subtitle = detail, source = "Nearby", kind = dev.niccc2007.filet.jobs.JobKind.OTHER)
        l.finish(id, detail)
    }

    /** The lock code, shown on the card under the access code. See [SharedSet.lockCode]. */
    val lockCode: String get() = shared.lockCode

    /**
     * The direct link for [f] on this phone's own address, for its QR and Copy link: one file
     * downloads as itself, one folder as a zip, several files as one zip. Works on a Wi-Fi with
     * no internet; empty while sharing is off.
     */
    fun fastLink(f: FastShare): String {
        val s = _state.value
        if (!s.running || s.address.isEmpty()) return ""
        val base = "http://${s.address}:${s.port}"
        val one = f.tokens.singleOrNull()?.let { shared.byToken(it) }
        return when {
            one == null -> "$base/z?k=${f.key}"
            f.oneFolder -> "$base/zd/${one.token}?k=${f.key}"
            else -> "$base/f/${one.token}?k=${f.key}"
        }
    }

    /**
     * Fast-share [nodes]: shared for a day unless already shared, under one new key. Starts
     * sharing when it is off, as opening the fast share sheet does.
     */
    fun fastShare(nodes: List<dev.niccc2007.filet.vfs.VNode>): FastShare {
        if (!_state.value.running && !_starting.value) {
            start()
            startedForFast = true
        }
        val f = shared.fastShare(nodes, expires = System.currentTimeMillis() + FAST_DAY_MS)
        log(
            if (nodes.size == 1) "Fast share: ${nodes.first().name}" else "Fast share: ${nodes.size} files",
            "for a day",
        )
        return f
    }


    val selfName: String get() = PairedPeers.selfName(context)
    fun setSelfName(name: String) = PairedPeers.setSelfName(context, name)

    fun isRunning() = server.isRunning()
    fun idleExpired() = server.idleExpired()

    /**
     * Why sharing cannot start right now, or null when it can.
     *
     * Checked *before* anything is started, and surfaced as a disabled button with the reason
     * under it. Starting a server on a device with no usable address used to print a mobile
     * address nobody could reach, or nothing at all, and then the foreground service died on
     * its own timeout - a crash, for a situation the app could see coming.
     */
    fun blockedReason(): String? = when {
        NetAddresses.reachable().isEmpty() ->
            "Join a Wi-Fi network or turn on your hotspot first — there is no local network to share over."
        else -> null
    }

    /**
     * The same answer, published rather than computed where it is read.
     *
     * Bug identified: the screen called [blockedReason] directly inside composition, with a
     * comment saying it was recomputed every time on purpose so a stale answer could not
     * survive a network coming up. The intent was right and the placement was not -
     * `NetAddresses.reachable()` walks every network interface on the device, which is tens of
     * milliseconds and occasionally much worse, and composition runs on the main thread and
     * runs often. Every unrelated state change on that screen paid for an interface walk.
     *
     * So it keeps the freshness and loses the cost: the walk happens off the main thread on a
     * slow poll while the screen is open, and the screen reads a value.
     */
    private val _blocked = MutableStateFlow<String?>(null)
    val blocked: StateFlow<String?> = _blocked.asStateFlow()

    /**
     * Recompute [blockedReason] away from the main thread.
     *
     * Called on a poll rather than on a connectivity callback because the question is not only
     * "is there a network" - a hotspot coming up is a change to the interface list that no
     * single callback covers on every vendor.
     */
    suspend fun refreshBlocked() {
        val answer = withContext(Dispatchers.IO) { blockedReason() }
        if (_blocked.value != answer) _blocked.value = answer
    }

    /** Consecutive failed starts, and when the last one was. See [ServerStartPolicy]. */
    private var failures = 0
    private var lastFailureAt = 0L

    /** Set when a start is refused, so the screen can say why instead of doing nothing. */
    private val _startRefusal = MutableStateFlow<String?>(null)
    val startRefusal: StateFlow<String?> = _startRefusal.asStateFlow()

    /**
     * True from the moment Start is pressed until the server is up or has given up.
     *
     * Starting is not instant - it binds a socket, reads preferences and brings up a
     * foreground service - and a button that shows nothing in the meantime reads as a button
     * that did not register the press. Which is what provokes a second press, and a second
     * press is how two accept loops end up running at once.
     */
    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    /** True from the moment Stop is pressed until the socket is actually down. */
    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    fun clearStartRefusal() { _startRefusal.value = null }

    /**
     * Moved every time sharing is stopped, so a start still finishing can tell it was
     * contradicted. See [startStillWanted] for what goes wrong without it.
     */
    private val stopToken = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Bug identified: the press did its deciding on the main thread, so the spinner could not
     * draw until the deciding had finished - which is the whole of what it was there to cover.
     *
     * Three things ran inline on the click before this. The trace wrote a file (see
     * [ShareTrace]). `blockedReason()` enumerates the device's network interfaces, which is
     * tens of milliseconds on a phone and occasionally far worse. And the flag that shows the
     * spinner was set *after* both, so the main thread never yielded in between and no frame
     * was drawn until the slow part was over.
     *
     * The press now does exactly one thing: it says it is busy. Every decision and every piece
     * of work happens on [Dispatchers.IO] behind it, including the ones that end in a refusal.
     * That costs a frame of spinner on a start that was going to be refused anyway, which is
     * the right trade - a refusal is rare and a press is not.
     */
    fun start() {
        // First statement, deliberately. Everything below this line is off the main thread, so
        // this is the last chance to say anything before the frame is drawn.
        _starting.value = true
        scope.launch(Dispatchers.IO) { startOffThread() }
    }

    private suspend fun startOffThread() {
        ShareTrace.attach(context)
        ShareTrace.line { "start() pressed: serverRunning=${server.isRunning()} failures=$failures starting=${_starting.value}" }
        when (val d = ServerStartPolicy.decide(
            running = server.isRunning(),
            blockedReason = blockedReason(),
            consecutiveFailures = failures,
            lastFailureAt = lastFailureAt,
            now = System.currentTimeMillis(),
        )) {
            is ServerStartPolicy.Decision.AlreadyRunning -> {
                // Republish rather than return in silence.
                //
                // Reaching here means the server is up while the screen was showing Start, so
                // the two had drifted apart - and a silent return is what turned that drift
                // into a button that could not be made to respond again. Saying what is
                // actually true puts the screen back in step and gives him a Stop to press.
                ShareTrace.line { "  -> AlreadyRunning; republished ${server.state().running}" }
                _state.value = server.state()
                _starting.value = false
                return
            }
            is ServerStartPolicy.Decision.Refuse -> {
                // Said out loud. The old code returned silently on a blocked start, so the
                // button looked broken rather than blocked.
                ShareTrace.line { "  -> Refuse: ${d.why}" }
                _startRefusal.value = d.why
                _starting.value = false
                return
            }
            is ServerStartPolicy.Decision.Start -> Unit
        }
        _startRefusal.value = null
        // Remembered before any of the slow work, so a Stop pressed during it is detectable.
        val began = stopToken.get()
        ShareTrace.line { "  -> Start; launching (token=$began)" }
        run {
            try {
            ShareTrace.line { "  coroutine running" }
            shared.ensureFolders()
            ShareTrace.line { "  folders ready" }
            server.pinRequired = prefs.getBool(KEY_PIN, true)
            server.uploadsAllowed = prefs.getBool(KEY_UPLOADS, false)
            server.idleStopMinutes = prefs.getLong(KEY_IDLE, 30L).toInt()
            server.fixedPin = fixedPin()
            runCatching { server.start() }
                .onSuccess {
                    ShareTrace.line { "  server.start() ok: running=${it.running} port=${it.port}" }
                    if (!startStillWanted(began, stopToken.get())) {
                        // Stop was pressed while this was still binding. His last instruction
                        // wins: undo the start rather than leave a server running that the
                        // screen has already been told is off.
                        runCatching { server.stop() }
                        _state.value = server.state()
                        failures = 0
                        return@onSuccess
                    }
                    failures = ServerStartPolicy.countAfter(failures, succeeded = true)
                    _state.value = it
                    listener.close()
                    runCatching { discovery.advertise(it.port) }
                    log("Sharing started", it.url)
                    // Guarded: on Android 12+ starting a foreground service from the background
                    // throws, and the throw happens here, after the server is already up. The
                    // server itself is fine and stopping it is the honest response - a running
                    // server with no notification is one he cannot see or turn off.
                    val svc = runCatching { NearbyService.start(context) }
                    svc.exceptionOrNull()?.let { e ->
                        android.util.Log.w("FiletNearby", "NearbyService.start failed", e)
                    }
                    if (svc.isFailure) {
                        runCatching { server.stop() }
                        _state.value = ServerState(running = false)
                        failures = ServerStartPolicy.countAfter(failures, succeeded = false)
                        lastFailureAt = System.currentTimeMillis()
                        _startRefusal.value =
                            "Sharing could not show its notification, so it was stopped. " +
                                "Open Filet and start sharing from the app."
                    }
                }
                .onFailure {
                    ShareTrace.line { "  server.start() FAILED: $it" }
                    // Logged, not only counted. A start that fails silently is why this took
                    // three rounds: every symptom was downstream of an exception nobody saw.
                    android.util.Log.w("FiletNearby", "server.start() failed", it)
                    failures = ServerStartPolicy.countAfter(failures, succeeded = false)
                    lastFailureAt = System.currentTimeMillis()
                    _state.value = ServerState(running = false)
                    _startRefusal.value = "Sharing could not start: ${it.message ?: it.javaClass.simpleName}"
                }
            } catch (t: Throwable) {
                ShareTrace.line { "  threw before the server was up: $t" }
                // Everything above `server.start()` - creating the folders, reading four
                // preferences - could throw straight out of this coroutine, where nothing was
                // watching. The button stopped spinning and sharing stayed off with no reason
                // given anywhere, which is indistinguishable from a button that does nothing.
                android.util.Log.w("FiletNearby", "start: threw before the server was up", t)
                failures = ServerStartPolicy.countAfter(failures, succeeded = false)
                lastFailureAt = System.currentTimeMillis()
                _state.value = ServerState(running = false)
                _startRefusal.value = "Sharing could not start: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                // In a finally: a throw anywhere above would otherwise leave the button
                // spinning for ever, which is a worse lie than showing nothing.
                _starting.value = false
            }
        }
    }

    // ── the code on the lock screen ──

    val grants: AccessGrants get() = server.grants

    /** Null means "a fresh random code each session", which is the default. */
    fun fixedPin(): String? = prefs.getString(KEY_FIXED_PIN)?.takeIf { it.length == 6 }

    /**
     * Re-read the server rather than trusting the last event.
     *
     * The state here is pushed by the server through `onEvent`, and a push that happens
     * while this screen is not composed is a push nobody hears. That is why the Start
     * sharing button could sit there saying Start while the server was already running.
     */
    fun resync() { _state.value = server.state() }

    fun setFixedPin(value: String?) {
        val clean = value?.filter(Char::isDigit)?.take(6)?.takeIf { it.length == 6 }
        prefs.putString(KEY_FIXED_PIN, clean ?: "")
        server.setPin(clean)
        _state.value = server.state()
    }

    fun revokeGrant(token: String) {
        server.grants.revoke(token)
        _state.value = server.state()
    }

    fun revokeAllGrants() {
        server.grants.revokeAll()
        _state.value = server.state()
    }

    fun setGrantLifetimeMinutes(minutes: Int) {
        server.grants.defaultLifetimeMinutes = minutes
        _state.value = server.state()
    }

    fun setGrantIdleMinutes(minutes: Int) {
        server.grants.defaultIdleMinutes = minutes
        _state.value = server.state()
    }

    /**
     * Leave sharing mode.
     *
     * @param origin who is stopping. Only a stop that came from the screen has a foreground
     *   service left to tell; see [tellsTheService]. This used to tell the service every time,
     *   including when the service was the caller, and the two then stopped each other without
     *   end - which is what made sharing impossible to restart.
     */
    fun stop(origin: StopOrigin = StopOrigin.SCREEN) {
        startedForFast = false
        // Synchronous on purpose, and the only thing that is: a start still finishing reads
        // this token to find out it has been contradicted. Posting it would open the window
        // where a stop is pressed and the start that follows it does not know.
        stopToken.incrementAndGet()
        // Said before the work, for the same reason start says it: the frame that greys the
        // button has to be able to run.
        _stopping.value = true
        scope.launch(Dispatchers.IO) { stopOffThread(origin) }
    }

    /**
     * Bug identified: closing the listening socket and tearing down the accept pool are both
     * blocking, and both ran on the press - as did the trace and the discovery teardown, which
     * is a binder call into the platform NSD service. A stop could hold the main thread for as
     * long as a client took to notice the socket had gone.
     */
    private fun stopOffThread(origin: StopOrigin) {
        ShareTrace.attach(context)
        ShareTrace.line { "stop($origin): serverRunning=${server.isRunning()}" }
        try {
            discovery.stopAdvertising()
            val was = server.isRunning()
            server.stop()
            _state.value = server.state()
            if (was) log("Sharing stopped")
            if (inFront || held) openDoor()
            // A stop clears the cooldown too: the app has just been told plainly what to do,
            // and enforcing a backoff earned by earlier failures is the app arguing with that.
            failures = 0
            lastFailureAt = 0L
            if (tellsTheService(origin)) NearbyService.stop(context)
        } finally {
            _stopping.value = false
        }
    }

    fun setPinRequired(required: Boolean) {
        server.pinRequired = required
        prefs.putBool(KEY_PIN, required)
        _state.value = server.state()
    }

    fun setUploadsAllowed(allowed: Boolean) {
        server.uploadsAllowed = allowed
        prefs.putBool(KEY_UPLOADS, allowed)
        _state.value = server.state()
    }

    fun setIdleMinutes(minutes: Int) {
        server.idleStopMinutes = minutes
        prefs.putLong(KEY_IDLE, minutes.toLong())
        _state.value = server.state()
    }

    fun kick(address: String) = server.kick(address)

    fun startScan() = discovery.startScan()
    fun stopScan() = discovery.stopScan()
    fun restartScan() = discovery.restartScan()

    /**
     * Pair with a peer.
     *
     * Both sides show a four-digit code derived from both UUIDs and the shared token. The
     * user compares them by eye; if they differ, something is between the two devices.
     */
    /**
     * Pair with [peer]. The other phone is asked as well and shows the same four digits, so each
     * person checks the two screens match; each phone keeps the pairing once its own person
     * confirms. Only a paired phone can be set to Always accept.
     */
    fun beginPairing(peer: Peer): Pairing {
        val token = PairedPeers.newToken()
        val self = PairedPeers.serviceName(context)
        scope.launch(Dispatchers.IO) {
            PushInbox.post(
                "${peer.base}/api/pair",
                org.json.JSONObject().put("from", self).put("name", PairedPeers.selfName(context)).put("token", token),
            )
        }
        return Pairing(peer, token, PairedPeers.shortCode(self, peer.uuid, token))
    }

    fun confirmPairing(pairing: Pairing) {
        PairedPeers.remember(context, pairing.peer.uuid, pairing.peer.label, pairing.token)
    }

    fun forget(uuid: String) = PairedPeers.forget(context, uuid)

    /** What [dev.niccc2007.filet.vfs.provider.net.PeerProvider] needs to reach a peer. */
    fun endpoints(): List<PeerEndpoint> = discovery.state.value.peers.map { p ->
        PeerEndpoint(
            uuid = p.uuid,
            label = p.label,
            host = p.host,
            port = p.port,
            token = PairedPeers.tokenFor(context, p.uuid),
        )
    }

    private companion object {
        /** Bytes between two progress reports on a download's row. */
        const val SHOW_EVERY = 512L * 1024
        const val KEY_PIN = "nearby.pin"
        const val KEY_UPLOADS = "nearby.uploads"
        const val KEY_IDLE = "nearby.idle"
        /** Empty string means "random each session", which is the default. */
        const val KEY_FIXED_PIN = "nearby.fixedPin"
        /** How long a fast share lasts unless changed: a day. */
        const val FAST_DAY_MS = 24L * 60 * 60 * 1000
    }
}

data class Pairing(val peer: Peer, val token: String, val code: String)
