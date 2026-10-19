package dev.niccc2007.filet.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How much the player may spend per second on this phone.
 *
 * ## What this is for
 *
 * The player's surfaces - the glow, the beat follower, the wave round the artwork - were tuned
 * on a fast phone and run at the same rate everywhere. On a mid-range or older phone the same
 * per-second budget is a much larger share of a much smaller CPU, and that share is heat. The
 * answer is not a plainer player: every surface is present at every tier. What changes is how
 * often a surface is recomputed and how finely it is drawn - the dials in [PlayerDials] - and
 * on a [CAPABLE] phone every dial is exactly the value that was hardcoded before this existed.
 * The tier is advice: the Full setting gives any phone the capable values, with no ceiling.
 *
 * ## What it classifies on, and why each one
 *
 * | Input | Source | Why |
 * |---|---|---|
 * | Low-RAM flag | `ActivityManager.isLowRamDevice` | The OEM's own statement that this is an entry device. Decisive on its own. |
 * | Total RAM | `ActivityManager.MemoryInfo.totalMem` | Separates entry (3 GB and under) from mid-range (4-6) from flagship (8+). Total, not available - available moves with whatever else is open. |
 * | Core count | `Runtime.availableProcessors` | Four or fewer is an entry SoC on anything still running Android 8+. |
 * | Media performance class | `Build.VERSION.MEDIA_PERFORMANCE_CLASS` (31+) | Google's certified "this is a high-end device" bit. A phone that declares it is capable whatever else is true. |
 * | SDK level | `Build.VERSION.SDK_INT` | Below 26 the render pipeline itself is older and slower; such a phone is low whatever its specs say. |
 *
 * ## What it deliberately does NOT classify on
 *
 * - **Model names.** A blocklist is wrong on the first phone nobody put on it and stays wrong
 *   after an update fixes the phone it named.
 * - **Display refresh rate.** It was a candidate and it points the wrong way: 90 and 120 Hz
 *   panels ship on mid-range phones - exactly the class this exists for - so a high refresh
 *   rate would have promoted them to [CAPABLE]. A faster panel also asks MORE of the GPU, not
 *   less.
 * - **`memoryClass`.** The per-app heap limit is 256 or 512 MB on nearly every phone made in
 *   the last five years, entry ones included, so it does not separate anything.
 * - **CPU frequency from `/sys`.** It is the sharpest line between mid-range and flagship
 *   silicon, but it is a filesystem read below the storage layer (rule R3) and unreadable on
 *   some kernels. Without it the only cost is in the safe direction: an 8 GB mid-range phone
 *   classifies CAPABLE and keeps today's cost. No flagship of the last five years has under
 *   8 GB, so none is demoted.
 *
 * ## Misclassification
 *
 * Possible in both directions, which is why [override] exists and is a setting: Automatic,
 * Full (always [CAPABLE]) and Light (always [LOW]). The override is for the phone that is
 * wrong; the automatic answer is the fix.
 */
enum class DeviceTier { LOW, MEDIUM, CAPABLE }

/**
 * The facts [classifyTier] reads, as plain numbers so the decision is testable off a phone.
 *
 * Zero means "could not be read" for every numeric field, and an unknown never counts against
 * a phone - a kernel that hides its CPU frequency is not evidence of a slow CPU.
 */
data class DeviceFacts(
    val sdk: Int,
    val cores: Int,
    val lowRam: Boolean,
    val totalRamMb: Long,
    val mediaPerformanceClass: Int,
)

/**
 * The tier for a set of facts. Pure.
 *
 * LOW is checked first so that a single decisive entry-level fact wins over any number of
 * good ones: a low-RAM phone with eight slow cores is still a low-RAM phone.
 */
fun classifyTier(f: DeviceFacts): DeviceTier {
    val low = f.lowRam ||
        f.sdk in 1 until 26 ||
        f.cores in 1..4 ||
        f.totalRamMb in 1..LOW_RAM_MB
    if (low) return DeviceTier.LOW
    if (f.mediaPerformanceClass >= Build.VERSION_CODES.S) return DeviceTier.CAPABLE
    // Both, and neither may be unknown. CAPABLE is the tier that spends the most, so it is the
    // one that has to be earned on evidence rather than granted by default.
    val capable = f.cores >= 8 && f.totalRamMb >= CAPABLE_RAM_MB
    return if (capable) DeviceTier.CAPABLE else DeviceTier.MEDIUM
}

/** Three gigabytes and under is entry-level. `totalMem` reports a little under the label. */
const val LOW_RAM_MB = 3_200L

/** Eight gigabytes and up, measured the same way. */
const val CAPABLE_RAM_MB = 7_200L

/**
 * What the settings override says, applied to what was detected. Pure.
 *
 * @param choice `null` or `"auto"` for the automatic answer, `"full"` or `"light"` to force one.
 *   Anything unrecognised is treated as automatic, so a stale stored value cannot pin a phone to
 *   a tier nobody chose.
 */
fun resolveTier(choice: String?, detected: DeviceTier): DeviceTier = when (choice) {
    TIER_FULL -> DeviceTier.CAPABLE
    TIER_LIGHT -> DeviceTier.LOW
    else -> detected
}

const val TIER_AUTO = "auto"
const val TIER_FULL = "full"
const val TIER_LIGHT = "light"

/**
 * Every tier-dependent dial on the playback path, in one value.
 *
 * ## The table
 *
 * | Dial | Low | Medium | Capable | Base value | What it costs |
 * |---|---|---|---|---|---|
 * | [pulseFps] | 30 | 40 | 60 | `PULSE_FPS = 60L` (BeatPulse.kt:305 at aaa092b) | One follower step, five state writes and one redraw of every surface reading the beat, per tick |
 * | [glowFps] | 10 | 12 | 15 | `GLOW_FPS = 15L` (LivingGlow.kt:326 at aaa092b) | One redraw of three radial gradients over the whole face, per tick |
 * | [phaseFps] | 12 | 15 | 20 | `fps: Long = 20L` default of `rememberPhase` (LivingGlow.kt:299 at aaa092b) | One redraw of the ring round the artwork, per tick, per clock |
 * | [wavePoints] | 90 | 120 | 180 | `WAVE_POINTS = 180` (BeatRing.kt:297 at aaa092b) | Points round the wave, each `waveLobes` sines, per frame |
 * | [discFps] | 30 | 40 | every frame (0) | the disc's infinite transition ran per display frame (AudioScreen.kt the disc face (then named Aurora) at aaa092b) | One integration step and one layer redraw of the disc, per tick, only while it moves |
 * | [emitEvery] | 600 | 300 | 150 | `EMIT_EVERY = 150` (BeatMap.kt:258 at aaa092b) | How often a growing map is re-normalised while a track is measured; each one sorts everything so far |
 *
 * ## Why these floors and not lower
 *
 * - **30 Hz is the least the follower may step at.** `BeatMap.strengthAt` looks at the bucket
 *   under the position and the next one, a 40 ms window. A step every 33 ms still lands inside
 *   every window, so no onset is stepped over; at 25 Hz (40 ms) one could fall exactly between
 *   two reads. The punch is therefore never lost at any tier - its attack is at most one tick
 *   later, which at 30 Hz is under a frame and a half.
 * - **10 Hz for the glow.** The fastest light goes round in 23 seconds; at 10 Hz it moves a
 *   sixth of a degree of its ellipse per redraw, under a pixel on a blurred gradient.
 * - **90 points round the wave** is one every four degrees. The highest harmonic still drawn at
 *   that tier has 8 lobes, so each lobe gets eleven points - smooth with round joins.
 * - **All five harmonics at every tier.** Dropping the finest ripple on a low tier was
 *   considered and rejected: it removes part of the picture rather than slowing it, and a
 *   device characteristic must never take a piece of the player away. The wave's cost is
 *   scaled through [wavePoints] instead, which changes how finely the same shape is drawn.
 *
 * ## Advice, never a ceiling
 *
 * The tier only chooses a starting point. [resolveTier] returns CAPABLE for the Full setting
 * whatever was detected, and nothing downstream of it reads the detected tier again or clamps
 * a dial against it: every consumer takes its number from [rememberPlayerDials], which is
 * `dialsFor(resolveTier(choice, detected))` and nothing else. A low-tier phone set to Full
 * runs exactly what a capable phone runs.
 *
 * Nothing here is ever zero. A dial whose only cheap setting is "off" is not a dial.
 */
data class PlayerDials(
    val pulseFps: Long,
    val glowFps: Long,
    val phaseFps: Long,
    val wavePoints: Int,
    val emitEvery: Int,
    /** The disc's step rate; [DISC_EVERY_FRAME] means every display frame. */
    val discFps: Long,
)

/** `PlayerDials.discFps` for "every display frame", the capable value. */
const val DISC_EVERY_FRAME = 0L

/** The dials for a tier. Pure; the table above is this function written out. */
fun dialsFor(tier: DeviceTier): PlayerDials = when (tier) {
    DeviceTier.LOW -> PlayerDials(
        pulseFps = 30L, glowFps = 10L, phaseFps = 12L,
        wavePoints = 90, emitEvery = 600, discFps = 30L,
    )
    DeviceTier.MEDIUM -> PlayerDials(
        pulseFps = 40L, glowFps = 12L, phaseFps = 15L,
        wavePoints = 120, emitEvery = 300, discFps = 40L,
    )
    DeviceTier.CAPABLE -> PlayerDials(
        pulseFps = PULSE_FPS, glowFps = GLOW_FPS, phaseFps = PHASE_FPS,
        wavePoints = WAVE_POINTS, emitEvery = BeatMap.EMIT_EVERY,
        discFps = DISC_EVERY_FRAME,
    )
}

/**
 * The detected tier for this process, and the settings override on top of it.
 *
 * Detection runs once: the hardware does not change while the process is alive, and reading
 * `/sys` on every composition would be the kind of cost this exists to remove.
 */
object Tiers {

    /**
     * The settings choice - [TIER_AUTO], [TIER_FULL] or [TIER_LIGHT] - pushed in by whoever
     * owns the preference. A flow rather than a read of the prefs here, so this file keeps
     * knowing nothing about settings storage and the change applies without a restart.
     */
    val override = MutableStateFlow<String?>(null)

    @Volatile
    private var detected: DeviceTier? = null

    fun detected(context: Context): DeviceTier =
        detected ?: classifyTier(factsOf(context.applicationContext)).also {
            detected = it
            dev.niccc2007.filet.data.Diag.log("tier", "detected $it")
        }

    fun current(context: Context): DeviceTier = resolveTier(override.value, detected(context))

    private fun factsOf(context: Context): DeviceFacts {
        val am = context.getSystemService(ActivityManager::class.java)
        val total = runCatching {
            ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem / (1024 * 1024)
        }.getOrDefault(0L)
        return DeviceFacts(
            sdk = Build.VERSION.SDK_INT,
            cores = Runtime.getRuntime().availableProcessors(),
            lowRam = am?.isLowRamDevice == true,
            totalRamMb = total,
            mediaPerformanceClass =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Build.VERSION.MEDIA_PERFORMANCE_CLASS
                } else {
                    0
                },
        )
    }
}

/** The dials for this phone, following the settings override live. */
@Composable
fun rememberPlayerDials(): PlayerDials {
    val context = LocalContext.current
    val choice = Tiers.override.collectAsState().value
    return remember(choice) { dialsFor(resolveTier(choice, Tiers.detected(context))) }
}
