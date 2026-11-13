package dev.niccc2007.filet.handlers

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory

/**
 * The F-Droid build has no FFmpeg: F-Droid builds everything from source, and the GitHub build's
 * decoders come as prebuilt native libraries. Same shape as the GitHub version so the engine
 * compiles against either; [available] is false, so [VideoLadder] never offers the FFmpeg rung and
 * Media3 plays with the phone's own decoders.
 */
internal object FfmpegDecoders {
    val available: Boolean = false
    val version: String? = null

    class Setup(val factory: RenderersFactory) {
        fun attach(player: ExoPlayer) = Unit
        fun detach() = Unit
    }

    @Suppress("UNUSED_PARAMETER")
    fun setup(context: Context, forcePicture: Boolean, pan: SidePan): Setup? = null
}
