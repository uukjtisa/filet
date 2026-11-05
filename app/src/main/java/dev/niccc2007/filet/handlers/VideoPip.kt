package dev.niccc2007.filet.handlers

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import dev.niccc2007.filet.R

/**
 * Picture-in-picture for the video player: the video going on in a small window over other apps.
 *
 * Two ways in. The player's menu has it, on any phone that offers it. And with the setting on, a
 * video that is PLAYING goes into the window when Filet is left for Home or another app, rather
 * than stopping - off by default, because stopping is what leaving has always done. The second
 * is the platform's auto-enter, which needs Android 12; before that it would need the activity
 * itself to catch the leaving, so the setting is not offered there.
 *
 * In the window there is the picture and nothing else: no bars, no transport, no frame bar, and
 * a play and pause button of the window's own. Closing the window stops the video, as leaving
 * did; opening it out brings the player back where it was.
 *
 * The rules are here and pure; the activity's side is the two composables below.
 */
object VideoPip {

    /**
     * The widest and the tallest a window may be. The platform refuses past 2.39 to 1 either
     * way, and throws rather than clamps; one step inside so a rounding never lands on the edge.
     */
    const val MOST = 2.38

    /** The window's shape for a video [width] by [height]: its own, or held to [MOST]. Null before it is known. */
    fun shape(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val r = width.toDouble() / height
        return when {
            r > MOST -> 238 to 100
            r < 1 / MOST -> 100 to 238
            else -> width to height
        }
    }

    /**
     * Whether leaving Filet now goes into the window: the setting on, and a video playing, ready
     * and not on its way to the music player - which is leaving with its sound already.
     */
    fun autoEnters(onHome: Boolean, playing: Boolean, ready: Boolean, handingOver: Boolean): Boolean =
        onHome && playing && ready && !handingOver

    /** What the window's play and pause button sends, to this app only. */
    const val ACTION_TOGGLE = "dev.niccc2007.filet.PIP_TOGGLE"
}

/** Whether this phone gives picture-in-picture at all. */
fun pipOffered(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** Whether leaving can go into the window on its own: the platform's auto-enter, Android 12 on. */
fun pipOnLeaveOffered(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pipOffered(context)

/** Whether [activity] is in picture-in-picture, following every change. */
@Composable
internal fun rememberInPip(activity: Activity?): Boolean {
    val host = activity as? ComponentActivity
    var inPip by remember(host) { mutableStateOf(host?.isInPictureInPictureMode == true) }
    DisposableEffect(host) {
        if (host == null) return@DisposableEffect onDispose {}
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        host.addOnPictureInPictureModeChangedListener(listener)
        onDispose { host.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}

/**
 * The window's settings, kept in step with the player while it is up - its shape, its button,
 * whether leaving goes into it - and its button answered.
 *
 * Auto-enter is switched off again when the player goes: a setting left on would put the file
 * list into a window the next time anybody left Filet from it.
 */
@Composable
internal fun PipEffects(
    activity: Activity?,
    playing: Boolean,
    size: Pair<Int, Int>?,
    autoEnter: Boolean,
    sourceRect: () -> Rect?,
    onToggle: () -> Unit,
) {
    if (activity == null || !pipOffered(activity)) return
    val context = LocalContext.current
    val toggle by rememberUpdatedState(onToggle)
    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = toggle()
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(VideoPip.ACTION_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            }
        }
    }
    LaunchedEffect(playing, size, autoEnter) {
        runCatching { activity.setPictureInPictureParams(pipParams(context, playing, size, autoEnter, sourceRect())) }
    }
}

/** Into the window now, from the player's menu. False when the phone or the activity will not. */
internal fun enterPip(activity: Activity, playing: Boolean, size: Pair<Int, Int>?, autoEnter: Boolean, sourceRect: Rect?): Boolean =
    runCatching { activity.enterPictureInPictureMode(pipParams(activity, playing, size, autoEnter, sourceRect)) }.getOrDefault(false)

private fun pipParams(
    context: Context,
    playing: Boolean,
    size: Pair<Int, Int>?,
    autoEnter: Boolean,
    sourceRect: Rect?,
): PictureInPictureParams {
    val b = PictureInPictureParams.Builder()
    size?.let { (w, h) -> VideoPip.shape(w, h) }?.let { (w, h) -> b.setAspectRatio(Rational(w, h)) }
    // Where the picture is on screen, so the window grows out of it rather than out of the middle.
    sourceRect?.takeIf { !it.isEmpty }?.let { b.setSourceRectHint(it) }
    b.setActions(listOf(toggleAction(context, playing)))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        b.setAutoEnterEnabled(autoEnter)
        // A video is not a layout that reflows: a resize is the picture scaled, not redrawn.
        b.setSeamlessResizeEnabled(false)
    }
    return b.build()
}

private fun toggleAction(context: Context, playing: Boolean): RemoteAction {
    val intent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(VideoPip.ACTION_TOGGLE).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val label = if (playing) "Pause" else "Play"
    val icon = Icon.createWithResource(context, if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play)
    return RemoteAction(icon, label, label, intent)
}
