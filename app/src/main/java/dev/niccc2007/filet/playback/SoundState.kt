package dev.niccc2007.filet.playback

import android.content.Context
import dev.niccc2007.filet.FiletApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Which player a sound belongs to.
 *
 * Music and video are set apart: a hall put on a song is not on the next video, and a video's
 * boost is not on the music. They used to be one sound for the process, and the music's look
 * turning up in the video player was the bug that ended it.
 */
enum class SoundTarget(val id: String, val label: String, val rememberLabel: String) {
    MUSIC("music", "Music", "Remember for this track"),
    VIDEO("video", "Video", "Remember for this video"),
}

/** Each player's own settings, the ones a file follows unless it is remembered. `Prefs` is the real one. */
interface GeneralSound {
    fun soundOf(target: SoundTarget): StateFlow<SoundSettings>
    fun setSound(target: SoundTarget, settings: SoundSettings)
}

/** Where something kept per file is stored: text by key. A file of its own on a phone. */
interface RememberStore {
    fun all(): Map<String, String>
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * One file's whole sheet, kept for it while "Remember for this track" is on: speed, pitch, and
 * every sound setting, the volume with its boost included.
 *
 * A remembered file keeps its boost on purpose. The general volume is read back no higher than
 * 100% so a boost does not greet the next file at twice the level; a file somebody chose to keep
 * louder is the opposite case, and it is theirs.
 */
data class Remembered(val speed: Float, val pitch: PitchMode, val sound: SoundSettings) {

    /**
     * One line, versioned, so a later sound model can read what this one wrote. The last two
     * fields are the effects as the general settings store them: the reverb's level, then every
     * other effect's by name. See `SoundMaths.filtersText`.
     *
     * Still v2 with the effects that came after Muffled and Radio: they went into the same field
     * by name, a v2 line from before them reads back with them off, and a build from before them
     * reading a line from after passes the names it does not know.
     */
    fun encode(): String = listOf(
        VERSION,
        speed.toString(),
        pitch.id,
        if (sound.eqOn) "1" else "0",
        SoundMaths.bandsText(sound.bands),
        sound.bass.toString(),
        sound.volume.toString(),
        SoundMaths.levelText(sound.reverb),
        SoundMaths.filtersText(sound),
    ).joinToString(SEP)

    companion object {
        const val VERSION = "v2"

        /** What a file remembered before the effects had a switch each was written as: a space and a filter by name. */
        private const val V1 = "v1"
        private const val SEP = ";"

        /** The file's sheet, or null for anything that is not one this version or the last wrote whole. */
        fun decode(text: String?): Remembered? {
            val f = text?.split(SEP) ?: return null
            if (f.size != 9 || (f[0] != VERSION && f[0] != V1)) return null
            val speed = f[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            val eqOn = f[3] == "1"
            // A v1 field is a name and a v2 field a level; the readers take either, so the two
            // versions differ only in what the fields hold.
            val base = SoundSettings(
                eqOn = eqOn,
                bands = SoundMaths.bandsOf(f[4]),
                bass = (f[5].toIntOrNull() ?: 0).coerceIn(0, 100),
                volume = SoundMaths.clampVolume(f[6].toIntOrNull() ?: 100),
                reverb = SoundMaths.reverbOf(f[7], eqOn),
            )
            return Remembered(
                speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
                pitch = PitchMode.of(f[2]),
                sound = SoundMaths.withFilters(base, f[8], eqOn),
            )
        }
    }
}

/**
 * What each player sounds like right now, and where an edit to it goes.
 *
 * ## Two layers
 *
 * Each player has its general settings ([GeneralSound]), which every file follows. A file can be
 * remembered: its whole sheet is then kept under its own key, it plays with that whenever it
 * plays again, and an edit while it is on screen goes to it and leaves the general settings
 * alone. Turning remembering off for a file throws its sheet away and the general settings
 * apply again. Other files are not touched either way.
 *
 * Off by default, per file. Not every listener wants a song to come back at its own speed.
 *
 * The players say which file they are on ([playing]) and follow [rememberedNow] for the speed and
 * pitch; the sound itself is [sound], which `Sound` puts on every link of that player.
 */
class SoundState(
    private val general: GeneralSound,
    private val store: RememberStore,
    scope: CoroutineScope,
) {
    private val remembered = MutableStateFlow(load())
    private val playingFile = SoundTarget.entries.associateWith { MutableStateFlow<String?>(null) }

    private val now: Map<SoundTarget, StateFlow<Remembered?>> = SoundTarget.entries.associateWith { t ->
        combine(playingFile.getValue(t), remembered) { file, all -> file?.let { all[keyOf(t, it)] } }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    private val effective: Map<SoundTarget, StateFlow<SoundSettings>> = SoundTarget.entries.associateWith { t ->
        combine(general.soundOf(t), now.getValue(t)) { g, r -> r?.sound ?: g }
            .stateIn(scope, SharingStarted.Eagerly, general.soundOf(t).value)
    }

    /** The sound [target] plays with now: the remembered file's, or the general one. */
    fun sound(target: SoundTarget): StateFlow<SoundSettings> = effective.getValue(target)

    /** The remembered sheet of the file [target] is on, or null while the general settings apply. */
    fun rememberedNow(target: SoundTarget): StateFlow<Remembered?> = now.getValue(target)

    /**
     * [file]'s remembered sheet as it changes, for a screen that knows its own file. Keyed on the
     * file, not on "whatever the player is on", so a screen never reads its predecessor's.
     */
    fun rememberedOf(target: SoundTarget, file: String): Flow<Remembered?> {
        val key = keyOf(target, file)
        return remembered.map { it[key] }
    }

    /** What [file] would play with, before it is the one playing. */
    fun rememberedFor(target: SoundTarget, file: String?): Remembered? =
        file?.let { remembered.value[keyOf(target, it)] }

    /** Whether [target]'s player is on a file at all, which is what a Remember toggle needs. */
    fun fileOf(target: SoundTarget): String? = playingFile.getValue(target).value

    /** The player says which file it is on; null when it is on none. */
    fun playing(target: SoundTarget, file: String?) {
        playingFile.getValue(target).value = file
    }

    /** A sound edit from a player's sheet: to the remembered file if there is one, else to the general settings. */
    fun editSound(target: SoundTarget, change: (SoundSettings) -> SoundSettings) {
        val file = fileOf(target)
        val r = rememberedFor(target, file)
        if (file != null && r != null) save(target, file, r.copy(sound = change(r.sound)))
        else general.setSound(target, change(general.soundOf(target).value))
    }

    /**
     * A speed or pitch edit while the file is remembered, kept with it. False when the file is
     * not remembered, so the caller applies it to the general setting instead.
     */
    fun editPlayback(target: SoundTarget, change: (Remembered) -> Remembered): Boolean {
        val file = fileOf(target) ?: return false
        val r = rememberedFor(target, file) ?: return false
        save(target, file, change(r))
        return true
    }

    /** The pitch from a player: the remembered file's, or else the general setting via [general]. */
    fun setPitch(target: SoundTarget, mode: PitchMode, general: (PitchMode) -> Unit) {
        if (!editPlayback(target) { it.copy(pitch = mode) }) general(mode)
    }

    /**
     * Turn remembering on or off for the file [target] is on.
     *
     * On keeps what is heard now - [speed], [pitch] and the current sound - so nothing jumps.
     * Off throws the file's sheet away, and the general settings apply again.
     */
    fun remember(target: SoundTarget, on: Boolean, speed: Float, pitch: PitchMode) {
        val file = fileOf(target) ?: return
        if (on) save(target, file, Remembered(speed, pitch, sound(target).value))
        else forget(target, file)
    }

    private fun save(target: SoundTarget, file: String, r: Remembered) {
        val key = keyOf(target, file)
        remembered.value = remembered.value + (key to r)
        store.put(key, r.encode())
    }

    private fun forget(target: SoundTarget, file: String) {
        val key = keyOf(target, file)
        remembered.value = remembered.value - key
        store.remove(key)
    }

    private fun load(): Map<String, Remembered> =
        store.all().mapNotNull { (k, v) -> Remembered.decode(v)?.let { k to it } }.toMap()

    companion object {
        /** A file is remembered per player: the same file played as music and as video is two sheets. */
        fun keyOf(target: SoundTarget, file: String): String = "${target.id}|$file"

        @Volatile private var instance: SoundState? = null

        /** The one instance, so every sheet and player sees one set of remembered files. */
        fun of(context: Context): SoundState =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    SoundState(
                        FiletApp.graphOf(app).prefs,
                        PrefsTextStore(app, "filet.remember"),
                        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                    ).also { instance = it }
                }
            }
    }
}

/**
 * Something kept per file in a store of its own: remembered files in `filet.remember` beside
 * `filet.resume`, the beat nudges in `filet.beatnudge`.
 *
 * Not in the main store, and so not in a settings export: like a resume point, it is what this
 * phone's listener did with these files, not how the app is set up.
 */
internal class PrefsTextStore(context: Context, name: String) : RememberStore {
    private val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun all(): Map<String, String> =
        sp.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }

    override fun remove(key: String) { sp.edit().remove(key).apply() }
}
