package dev.niccc2007.filet.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a session's effects need from the platform, as an interface so the rules about when they
 * are made, kept and remade are tested without a phone.
 */
interface SoundPort {
    /** An equaliser on [session], or null when this phone will not give one. */
    fun openEq(session: Int): EqEffect?

    /** A peak-limited boost on [session], or null when this phone will not give one. */
    fun openBoost(session: Int): BoostEffect?

    /** A reverb on [session], or null when this phone will not give one. */
    fun openSpace(session: Int): SpaceEffect?

    /** A virtualizer on [session], or null when this phone will not give one. */
    fun openWide(session: Int): WideEffect? = null

    /**
     * Whether this phone has [part] at all, asked before anything is made, so a control for
     * something the phone does not have is not offered in the first place.
     */
    fun offers(part: SoundPart): Boolean = true

    /** Which reverb [openSpace] makes, for the headroom in front of it. Asked once, like [offers]. */
    val reverb: SoundMaths.Reverb get() = SoundMaths.Reverb.ENVIRONMENTAL
}

interface EqEffect : AutoCloseable {
    fun set(inputDb: Float, gains: FloatArray)

    /** The compressor after the curve, or null for none. See [SoundMaths.compression]. */
    fun compress(c: SoundMaths.Compression?) {}
}

interface BoostEffect : AutoCloseable {
    fun set(mb: Int)
}

interface SpaceEffect : AutoCloseable {
    fun set(space: SoundMaths.Space)
}

interface WideEffect : AutoCloseable {
    /** The virtualizer's strength, 0 to 1000. */
    fun set(strength: Int)
}

/** Which part of the sound this phone refused. Shown, not hidden: a slider that does nothing is a lie. */
enum class SoundPart { EQUALISER, BOOST, SPACE, WIDE }

/**
 * One player's session and the effects on it.
 *
 * ## Remade, not trusted to survive
 *
 * An effect is attached to a session, not to a player, and on some phones it does not survive
 * what happens to the session: one made before the first track on it started can be dropped,
 * and so can one that lived through a change of track or of output - a Bluetooth headset
 * connecting is a new output. Nothing reports that it has gone; the sound is simply flat again.
 * So the owner calls [reattach] on every new player on the session and the store calls it on
 * every change of output, and both close and remake every effect from the current plan. Making
 * an effect is a few milliseconds; a boost that silently stopped working is a support question
 * nobody can answer.
 *
 * ## What the player does itself
 *
 * Nightcore, Vaporwave and 8D are not effects on the session: the first two are the player's
 * rate and the last its two volumes. The owner is told when either changes, reads [rate] and
 * [panLeft] and [panRight], and multiplies them into its own speed and volume.
 *
 * @param onLevel told the player's own volume whenever it changes. The owner multiplies it into
 *   whatever else it does with the volume - a duck, a fade, a mute - so this never fights them.
 * @param onRate told [rate] whenever it changes.
 * @param onPan told [panLeft] and [panRight] whenever they move, which under 8D is many times
 *   a second.
 */
class SoundLink internal constructor(
    val session: Int,
    /** Whose sound this session plays with. See [SoundTarget]. */
    val target: SoundTarget,
    private val port: SoundPort,
    private val onLevel: (Float) -> Unit,
    private val onRefused: (SoundPart) -> Unit,
    private val onRate: (Float) -> Unit = {},
    private val onPan: () -> Unit = {},
) : AutoCloseable {

    private var eq: EqEffect? = null
    private var boost: BoostEffect? = null
    private var space: SpaceEffect? = null
    private var wide: WideEffect? = null
    private var plan: SoundPlan? = null
    private var closed = false

    /** The 8D sweep moving [panLeft] and [panRight], while there is one. Started by [Sound]. */
    internal var sweep: Job? = null

    /** The player's own volume under the current plan, 1 before there is one. */
    val level: Float get() = plan?.level ?: 1f

    /** What the player's speed and pitch are both multiplied by: Nightcore's or Vaporwave's. */
    val rate: Float get() = plan?.rate ?: 1f

    /** How far 8D swings, 0 for not at all. */
    internal val orbit: Float get() = plan?.orbit ?: 0f

    /** What the player's left volume is multiplied by: 1, except under 8D. */
    @Volatile var panLeft: Float = 1f
        private set

    /** What the player's right volume is multiplied by. */
    @Volatile var panRight: Float = 1f
        private set

    internal val isClosed: Boolean get() = closed

    /** Whether an effect is in the signal now. For the tests and for nothing else. */
    internal val equalising: Boolean get() = eq != null
    internal val boosting: Boolean get() = boost != null
    internal val spacing: Boolean get() = space != null
    internal val widening: Boolean get() = wide != null

    @Synchronized
    fun apply(next: SoundPlan) {
        if (closed) return
        val before = plan
        plan = next
        if (before?.level != next.level) onLevel(next.level)
        if (before?.rate != next.rate) onRate(next.rate)
        bring(next)
    }

    /** Move the two sides to [left] and [right]. See [SoundMaths.orbitGains]. */
    internal fun pan(left: Float, right: Float) {
        if (closed || (left == panLeft && right == panRight)) return
        panLeft = left
        panRight = right
        onPan()
    }

    /** Close and remake every effect. See the note on the class. */
    @Synchronized
    fun reattach() {
        if (closed) return
        drop()
        plan?.let { bring(it) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        sweep?.cancel()
        sweep = null
        drop()
    }

    private fun bring(p: SoundPlan) {
        if (p.boosts) {
            val b = boost ?: port.openBoost(session).also { boost = it }
            if (b == null) onRefused(SoundPart.BOOST) else runCatching { b.set(p.boostMb) }
        } else {
            boost?.let { runCatching { it.close() } }
            boost = null
        }
        val gains = p.eqGains
        if (gains != null) {
            val e = eq ?: port.openEq(session).also { eq = it }
            if (e == null) {
                onRefused(SoundPart.EQUALISER)
            } else {
                runCatching { e.set(p.eqInputDb, gains) }
                runCatching { e.compress(p.evenOut) }
            }
        } else {
            eq?.let { runCatching { it.close() } }
            eq = null
        }
        if (p.spaces) {
            val r = space ?: port.openSpace(session).also { space = it }
            if (r == null) onRefused(SoundPart.SPACE) else runCatching { r.set(p.space) }
        } else {
            space?.let { runCatching { it.close() } }
            space = null
        }
        if (p.widens) {
            val w = wide ?: port.openWide(session).also { wide = it }
            if (w == null) onRefused(SoundPart.WIDE) else runCatching { w.set(p.wide) }
        } else {
            wide?.let { runCatching { it.close() } }
            wide = null
        }
    }

    private fun drop() {
        eq?.let { runCatching { it.close() } }
        boost?.let { runCatching { it.close() } }
        space?.let { runCatching { it.close() } }
        wide?.let { runCatching { it.close() } }
        eq = null
        boost = null
        space = null
        wide = null
    }
}

/**
 * The sound each player plays with, on every session that player has.
 *
 * Music and video are apart ([SoundTarget]): each player links its session here with [link]
 * under its own target, and a change to one player's settings reaches that player's links and
 * no other. It was one sound for the process, and a hall put on a song turned up on the next
 * video. The settings are each player's in `SoundState` - the general ones, or a remembered
 * file's - so they outlive every screen that changes them.
 *
 * See [SoundMaths] for what each setting does to the signal and why, and [SoundLink] for why the
 * effects are remade rather than trusted.
 */
class Sound internal constructor(
    private val settings: (SoundTarget) -> StateFlow<SoundSettings>,
    private val port: SoundPort,
    private val scope: CoroutineScope,
    /** Milliseconds on a clock that only goes forward, for the 8D sweep. */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val links = ArrayList<SoundLink>()

    private val _refused = MutableStateFlow<Set<SoundPart>>(emptySet())

    /** The parts this phone would not give, so their controls can say so. */
    val refused: StateFlow<Set<SoundPart>> = _refused.asStateFlow()

    /** The parts this phone has at all. Asked once: what is installed does not change while running. */
    val offers: Set<SoundPart> = SoundPart.entries.filter { runCatching { port.offers(it) }.getOrDefault(false) }.toSet()

    /** Which reverb this phone's Reverb is, so a plan keeps that one's headroom. */
    val reverb: SoundMaths.Reverb = runCatching { port.reverb }.getOrDefault(SoundMaths.Reverb.PRESET)

    init {
        for (target in SoundTarget.entries) {
            scope.launch {
                settings(target).collect { s ->
                    val plan = SoundMaths.plan(s, reverb)
                    for (l in synchronized(links) { links.filter { it.target == target } }) {
                        l.apply(plan)
                        sweep(l)
                    }
                }
            }
        }
    }

    /**
     * Put a player's session under [target]'s sound. Close the link when the player goes.
     *
     * Linking a session that is already linked returns that link, remade - which is what a new
     * player on the same session needs, and it means a caller cannot stack two sets of effects.
     *
     * @param onRate told when Nightcore's or Vaporwave's rate changes; the player reads
     *   [SoundLink.rate] and sets its speed again.
     * @param onPan told when 8D moves the sound; the player reads [SoundLink.panLeft] and
     *   [SoundLink.panRight] and sets its volume again.
     */
    fun link(
        session: Int,
        target: SoundTarget,
        onRate: (Float) -> Unit = {},
        onPan: () -> Unit = {},
        onLevel: (Float) -> Unit,
    ): SoundLink {
        synchronized(links) {
            links.removeAll { it.isClosed }
            links.firstOrNull { it.session == session }?.let { old ->
                links.remove(old)
                old.close()
            }
            val l = SoundLink(session, target, port, onLevel, { part -> _refused.value = _refused.value + part }, onRate, onPan)
            links += l
            l.apply(SoundMaths.plan(settings(target).value, reverb))
            sweep(l)
            return l
        }
    }

    /**
     * Start or stop [l]'s 8D sweep to match its plan: a step every [SWEEP_STEP_MS] while it swings,
     * and both sides back at 1 the moment it stops.
     *
     * One sweep per link, reading the link's swing at every step, so a change of strength is
     * heard on the next step and not by starting again from the middle.
     */
    private fun sweep(l: SoundLink) {
        if (l.orbit <= 0f || l.isClosed) {
            l.sweep?.cancel()
            l.sweep = null
            l.pan(1f, 1f)
            return
        }
        if (l.sweep?.isActive == true) return
        l.sweep = scope.launch {
            while (!l.isClosed) {
                val swing = l.orbit
                if (swing > 0f) {
                    val (left, right) = SoundMaths.orbitGains(swing, clockMs())
                    l.pan(left, right)
                }
                delay(SWEEP_STEP_MS)
            }
        }
    }

    /** The output changed - a headset, a speaker, a car. Every effect is remade on the new one. */
    fun outputChanged() {
        for (l in synchronized(links) { links.toList() }) l.reattach()
    }

    companion object {
        /**
         * How often 8D moves the sound. The mixer's own block is 10 to 20 ms and it glides each
         * change across one, so a step twice that long is still a slope; any shorter is waking
         * the phone for nothing.
         */
        const val SWEEP_STEP_MS = 30L

        @Volatile private var instance: Sound? = null

        /**
         * The one instance, watching the phone's outputs for as long as the process lives.
         *
         * A second instance would put a second set of effects on the same session, and two
         * limiters in a row are a different sound from one.
         */
        fun of(context: Context): Sound =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val state = SoundState.of(app)
                    Sound({ state.sound(it) }, AndroidSoundPort(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
                        .also { sound ->
                            instance = sound
                            app.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(
                                object : AudioDeviceCallback() {
                                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = sound.outputChanged()
                                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = sound.outputChanged()
                                },
                                Handler(Looper.getMainLooper()),
                            )
                        }
                }
            }
    }
}

/**
 * The platform's effects. The equaliser is `DynamicsProcessing`'s first EQ and nothing else of
 * it; the boost is `LoudnessEnhancer`, whose make-up gain sits in front of its own peak limiter;
 * the space is `EnvironmentalReverb` as an INSERT effect on the session, set to [SoundMaths.room],
 * or `PresetReverb` where a phone has no insert environmental one. See [SoundMaths] for why
 * those and not the others this phone has, and [SoundMaths.Space] for why the presets come last.
 * Wide is `Virtualizer`, and Even out the multi-band stage of the equaliser's own
 * `DynamicsProcessing` - never a second one, for the reason in [SoundMaths.compression].
 *
 * ## Why the reverb is an insert and not an auxiliary
 *
 * The usual way to put a reverb on a `MediaPlayer` is auxiliary: one reverb on the output mix,
 * every player attached to it with a send level. That is one effect shared with every other app
 * that does the same, made and kept on its own clock, outside this session's re-attach rules.
 * The insert form is the same reverb engine on this session only; it returns the dry sound with
 * the room added, and it is made and remade exactly like the other two.
 */
private class AndroidSoundPort : SoundPort {

    override fun offers(part: SoundPart): Boolean {
        val type = when (part) {
            SoundPart.EQUALISER -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
                AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
            }
            SoundPart.BOOST -> AudioEffect.EFFECT_TYPE_LOUDNESS_ENHANCER
            SoundPart.SPACE -> return insertReverb != null
            // An insert, like the reverb: a virtualizer on the output mix is everybody's.
            SoundPart.WIDE -> return AudioEffect.queryEffects().orEmpty().any { d ->
                d.type == AudioEffect.EFFECT_TYPE_VIRTUALIZER && d.connectMode == AudioEffect.EFFECT_INSERT
            }
        }
        return AudioEffect.queryEffects().orEmpty().any { d -> d.type == type }
    }

    /** The reverb this phone gives as an insert, or null for none. The descriptors, asked once. */
    private val insertReverb: SoundMaths.Reverb? by lazy {
        val inserts = AudioEffect.queryEffects().orEmpty().filter { d -> d.connectMode == AudioEffect.EFFECT_INSERT }
        SoundMaths.reverbFor(
            environmental = inserts.any { it.type == AudioEffect.EFFECT_TYPE_ENV_REVERB },
            preset = inserts.any { it.type == AudioEffect.EFFECT_TYPE_PRESET_REVERB },
        )
    }

    override val reverb: SoundMaths.Reverb get() = insertReverb ?: SoundMaths.Reverb.PRESET

    override fun openEq(session: Int): EqEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return openDynamicsEq(session)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun openDynamicsEq(session: Int): EqEffect? = runCatching {
        val bands = SoundMaths.EQ_EDGES_HZ.size
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, bands, // the first EQ, the equaliser
            true, 1, // one band of the multi-band compressor, Even out: off until it is on
            false, 0,
            false,
        ).build()
        val eq = DynamicsProcessing.Eq(true, true, bands)
        for (i in 0 until bands) eq.setBand(i, DynamicsProcessing.EqBand(true, SoundMaths.EQ_EDGES_HZ[i], 0f))
        config.setPreEqAllChannelsTo(eq)
        config.setMbcAllChannelsTo(mbcOf(null))
        val dp = DynamicsProcessing(PRIORITY, session, config)
        dp.enabled = true
        object : EqEffect {
            override fun set(inputDb: Float, gains: FloatArray) {
                dp.setInputGainAllChannelsTo(inputDb)
                for (i in gains.indices) {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, SoundMaths.EQ_EDGES_HZ[i], gains[i]))
                }
            }

            override fun compress(c: SoundMaths.Compression?) = dp.setMbcAllChannelsTo(mbcOf(c))

            override fun close() = dp.release()
        }
    }.getOrNull()

    /**
     * The compressor stage for [c]: one band to the top of the equaliser's range, and switched
     * off, at a ratio of one, for none. No gain before or after it and no gate or expander, so
     * all it can ever do is bring a loud part down. See [SoundMaths.compression].
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun mbcOf(c: SoundMaths.Compression?): DynamicsProcessing.Mbc {
        val mbc = DynamicsProcessing.Mbc(true, c != null, 1)
        mbc.setBand(
            0,
            DynamicsProcessing.MbcBand(
                c != null,
                SoundMaths.EQ_EDGES_HZ.last(),
                c?.attackMs ?: 10f,
                c?.releaseMs ?: 300f,
                c?.ratio ?: 1f,
                c?.thresholdDb ?: 0f,
                c?.kneeDb ?: 0f,
                GATE_OFF_DB,
                1f, // no expander
                0f, // no gain in front
                0f, // and none after
            ),
        )
        return mbc
    }

    override fun openBoost(session: Int): BoostEffect? = runCatching {
        val le = LoudnessEnhancer(session)
        le.enabled = true
        object : BoostEffect {
            override fun set(mb: Int) = le.setTargetGain(mb)
            override fun close() = le.release()
        }
    }.getOrNull()

    override fun openSpace(session: Int): SpaceEffect? = when (insertReverb) {
        SoundMaths.Reverb.ENVIRONMENTAL -> openRoom(session)
        SoundMaths.Reverb.PRESET -> openPreset(session)
        null -> null
    }

    /**
     * The environmental reverb at [SoundMaths.room]'s numbers, every one of them set, so nothing
     * is left at whatever the wrapper starts it at.
     */
    private fun openRoom(session: Int): SpaceEffect? = runCatching {
        val reverb = EnvironmentalReverb(PRIORITY, session)
        // Refused rather than kept: an effect that will not switch on is a room nobody hears.
        if (reverb.setEnabled(true) != AudioEffect.SUCCESS) {
            reverb.release()
            return@runCatching null
        }
        object : SpaceEffect {
            override fun set(space: SoundMaths.Space) {
                val room = SoundMaths.room(space) ?: return
                reverb.roomLevel = room.roomLevelMb.toShort()
                reverb.reverbLevel = room.reverbLevelMb.toShort()
                reverb.roomHFLevel = room.roomHfLevelMb.toShort()
                reverb.decayTime = room.decayMs
                reverb.decayHFRatio = room.decayHfRatio.toShort()
                reverb.diffusion = room.diffusion.toShort()
                reverb.density = room.density.toShort()
            }

            override fun close() = reverb.release()
        }
    }.getOrNull()

    /** The virtualizer, refused where it has no strength to set: a Wide that cannot widen. */
    override fun openWide(session: Int): WideEffect? = runCatching {
        val v = Virtualizer(PRIORITY, session)
        if (!v.strengthSupported || v.setEnabled(true) != AudioEffect.SUCCESS) {
            v.release()
            return@runCatching null
        }
        object : WideEffect {
            override fun set(strength: Int) = v.setStrength(strength.coerceIn(0, 1000).toShort())
            override fun close() = v.release()
        }
    }.getOrNull()

    /** The fallback: the preset reverb, at the preset nearest each size. See [SoundMaths.Space]. */
    private fun openPreset(session: Int): SpaceEffect? = runCatching {
        val reverb = PresetReverb(PRIORITY, session)
        reverb.preset = PresetReverb.PRESET_NONE
        // Refused rather than kept: an effect that will not switch on is a room nobody hears.
        if (reverb.setEnabled(true) != AudioEffect.SUCCESS) {
            reverb.release()
            return@runCatching null
        }
        object : SpaceEffect {
            override fun set(space: SoundMaths.Space) {
                reverb.preset = when (space) {
                    SoundMaths.Space.NONE -> PresetReverb.PRESET_NONE
                    SoundMaths.Space.SMALL_ROOM -> PresetReverb.PRESET_SMALLROOM
                    SoundMaths.Space.ROOM -> PresetReverb.PRESET_MEDIUMROOM
                    SoundMaths.Space.LARGE_ROOM -> PresetReverb.PRESET_LARGEROOM
                    SoundMaths.Space.MEDIUM_HALL -> PresetReverb.PRESET_MEDIUMHALL
                    SoundMaths.Space.HALL -> PresetReverb.PRESET_LARGEHALL
                }
            }

            override fun close() = reverb.release()
        }
    }.getOrNull()

    companion object {
        private const val CHANNELS = 2

        /** A gate threshold no sound is under, which is no gate. */
        private const val GATE_OFF_DB = -90f

        /** Above the default, so an equaliser another app put on the same session does not take ours over. */
        private const val PRIORITY = 1
    }
}
