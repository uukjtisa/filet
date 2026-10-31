package dev.niccc2007.filet.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /**
     * Whether this phone has [part] at all, asked before anything is made, so a control for
     * something the phone does not have is not offered in the first place.
     */
    fun offers(part: SoundPart): Boolean = true
}

interface EqEffect : AutoCloseable {
    fun set(inputDb: Float, gains: FloatArray)
}

interface BoostEffect : AutoCloseable {
    fun set(mb: Int)
}

interface SpaceEffect : AutoCloseable {
    fun set(space: SoundMaths.Space)
}

/** Which part of the sound this phone refused. Shown, not hidden: a slider that does nothing is a lie. */
enum class SoundPart { EQUALISER, BOOST, SPACE }

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
 * @param onLevel told the player's own volume whenever it changes. The owner multiplies it into
 *   whatever else it does with the volume - a duck, a fade, a mute - so this never fights them.
 */
class SoundLink internal constructor(
    val session: Int,
    /** Whose sound this session plays with. See [SoundTarget]. */
    val target: SoundTarget,
    private val port: SoundPort,
    private val onLevel: (Float) -> Unit,
    private val onRefused: (SoundPart) -> Unit,
) : AutoCloseable {

    private var eq: EqEffect? = null
    private var boost: BoostEffect? = null
    private var space: SpaceEffect? = null
    private var plan: SoundPlan? = null
    private var closed = false

    /** The player's own volume under the current plan, 1 before there is one. */
    val level: Float get() = plan?.level ?: 1f

    internal val isClosed: Boolean get() = closed

    /** Whether an effect is in the signal now. For the tests and for nothing else. */
    internal val equalising: Boolean get() = eq != null
    internal val boosting: Boolean get() = boost != null
    internal val spacing: Boolean get() = space != null

    @Synchronized
    fun apply(next: SoundPlan) {
        if (closed) return
        val before = plan
        plan = next
        if (before?.level != next.level) onLevel(next.level)
        bring(next)
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
            if (e == null) onRefused(SoundPart.EQUALISER) else runCatching { e.set(p.eqInputDb, gains) }
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
    }

    private fun drop() {
        eq?.let { runCatching { it.close() } }
        boost?.let { runCatching { it.close() } }
        space?.let { runCatching { it.close() } }
        eq = null
        boost = null
        space = null
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
    scope: CoroutineScope,
) {
    private val links = ArrayList<SoundLink>()

    private val _refused = MutableStateFlow<Set<SoundPart>>(emptySet())

    /** The parts this phone would not give, so their controls can say so. */
    val refused: StateFlow<Set<SoundPart>> = _refused.asStateFlow()

    /** The parts this phone has at all. Asked once: what is installed does not change while running. */
    val offers: Set<SoundPart> = SoundPart.entries.filter { runCatching { port.offers(it) }.getOrDefault(false) }.toSet()

    init {
        for (target in SoundTarget.entries) {
            scope.launch {
                settings(target).collect { s ->
                    val plan = SoundMaths.plan(s)
                    for (l in synchronized(links) { links.filter { it.target == target } }) l.apply(plan)
                }
            }
        }
    }

    /**
     * Put a player's session under [target]'s sound. Close the link when the player goes.
     *
     * Linking a session that is already linked returns that link, remade - which is what a new
     * player on the same session needs, and it means a caller cannot stack two sets of effects.
     */
    fun link(session: Int, target: SoundTarget, onLevel: (Float) -> Unit): SoundLink {
        synchronized(links) {
            links.removeAll { it.isClosed }
            links.firstOrNull { it.session == session }?.let { old ->
                links.remove(old)
                old.close()
            }
            val l = SoundLink(session, target, port, onLevel) { part -> _refused.value = _refused.value + part }
            links += l
            l.apply(SoundMaths.plan(settings(target).value))
            return l
        }
    }

    /** The output changed - a headset, a speaker, a car. Every effect is remade on the new one. */
    fun outputChanged() {
        for (l in synchronized(links) { links.toList() }) l.reattach()
    }

    companion object {
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
 * the space is `PresetReverb` as an INSERT effect on the session. See [SoundMaths] for why those
 * and not the others this phone has.
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
            SoundPart.SPACE -> AudioEffect.EFFECT_TYPE_PRESET_REVERB
        }
        return AudioEffect.queryEffects().orEmpty().any { d ->
            d.type == type && (part != SoundPart.SPACE || d.connectMode == AudioEffect.EFFECT_INSERT)
        }
    }

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
            true, bands, // the first EQ, which is all of it this uses
            false, 0,
            false, 0,
            false,
        ).build()
        val eq = DynamicsProcessing.Eq(true, true, bands)
        for (i in 0 until bands) eq.setBand(i, DynamicsProcessing.EqBand(true, SoundMaths.EQ_EDGES_HZ[i], 0f))
        config.setPreEqAllChannelsTo(eq)
        val dp = DynamicsProcessing(PRIORITY, session, config)
        dp.enabled = true
        object : EqEffect {
            override fun set(inputDb: Float, gains: FloatArray) {
                dp.setInputGainAllChannelsTo(inputDb)
                for (i in gains.indices) {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, SoundMaths.EQ_EDGES_HZ[i], gains[i]))
                }
            }

            override fun close() = dp.release()
        }
    }.getOrNull()

    override fun openBoost(session: Int): BoostEffect? = runCatching {
        val le = LoudnessEnhancer(session)
        le.enabled = true
        object : BoostEffect {
            override fun set(mb: Int) = le.setTargetGain(mb)
            override fun close() = le.release()
        }
    }.getOrNull()

    override fun openSpace(session: Int): SpaceEffect? = runCatching {
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
                    SoundMaths.Space.ROOM -> PresetReverb.PRESET_MEDIUMROOM
                    SoundMaths.Space.HALL -> PresetReverb.PRESET_LARGEHALL
                }
            }

            override fun close() = reverb.release()
        }
    }.getOrNull()

    companion object {
        private const val CHANNELS = 2

        /** Above the default, so an equaliser another app put on the same session does not take ours over. */
        private const val PRIORITY = 1
    }
}
