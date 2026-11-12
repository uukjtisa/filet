package dev.niccc2007.filet.handlers

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import android.os.Handler
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * FFmpeg's decoders for the Media3 engine. The GitHub build: the F-Droid build has a stub of the
 * same name, because this ships prebuilt native libraries and F-Droid builds from source.
 *
 * Two ways of using them, one per rung of [VideoLadder]:
 *
 * - **Phone first** ([forcePicture] false): the phone's own decoders, and FFmpeg only for what the
 *   phone has no decoder for at all. That is most of what VLC is reached for: DTS, TrueHD and
 *   E-AC-3 sound on phones without them, and video formats the phone never lists.
 * - **FFmpeg for the picture** ([forcePicture] true): the phone's decoder is not asked. For the
 *   files it claims and then gets wrong, the black-picture case, because a decoder that says it
 *   can play a file is picked over FFmpeg every time otherwise.
 *
 * Sound stays phone-first in both, since the phone's sound decoders are not the ones that fail
 * quietly and they cost nothing in battery.
 */
@OptIn(UnstableApi::class)
internal object FfmpegDecoders {

    /** Whether the native libraries loaded. False on x86, where the APK leaves them out. */
    val available: Boolean by lazy { runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false) }

    /** The version string FFmpeg reports, for the log. */
    val version: String? get() = if (available) runCatching { FfmpegLibrary.getVersion() }.getOrNull() else null

    /** A renderers factory and what to do with the player it builds. Null when FFmpeg is absent. */
    class Setup(val factory: RenderersFactory, private val manager: DecoderManager) {
        /** After the player is built, on its own thread: the manager applies its modes to the tracks. */
        fun attach(player: ExoPlayer) = manager.attach(player)
        fun detach() = runCatching { manager.detach() }
    }

    fun setup(context: Context, forcePicture: Boolean): Setup? {
        if (!available) return null
        val manager = DecoderManager(
            if (forcePicture) DecoderMode.FFMPEG else DecoderMode.AUTO,
            DecoderMode.AUTO,
        )
        // Phone first means the phone's hardware even past its declared limits: see HardwareFirst.
        val factory = (if (forcePicture) NextRenderersFactory(context) else PhoneFirstFactory(context))
            .setDecoderManager(manager)
        factory.setEnableDecoderFallback(true)
        return Setup(factory, manager)
    }

    private class PhoneFirstFactory(context: Context) : NextRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            super.buildVideoRenderers(
                context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
            )
            for (i in out.indices) {
                if (out[i] is MediaCodecVideoRenderer) out[i] = HardwareFirst.wrap(out[i])
            }
        }
    }
}
