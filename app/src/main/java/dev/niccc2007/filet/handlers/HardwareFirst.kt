package dev.niccc2007.filet.handlers

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The phone's hardware decoder tried first, even on a file past what it declares - what VLC does.
 *
 * Two places decide which decoder plays a video, and both prefer a decoder that declares the file
 * within its limits over one that does not:
 *
 * - **The track selector** picks a renderer. FFmpeg's renderer reports a 2160 x 3402, 60 fps H.264
 *   file as handled and the phone's renderer reports it as past its limits, so the selector gave the
 *   picture to FFmpeg. [upgrade] reads the phone renderer's "past its limits" as "handled".
 * - **The phone's renderer** then picks a decoder from its own list, sorted by the same test. Bug
 *   identified here: with the first fix in, the file still played at a few frames a second, and the
 *   log named the decoder - `c2.android.avc.decoder`, Google's software one, which declares the file
 *   within its limits and so sorts ahead of the hardware decoder that declares it past them. The
 *   first fix only changed what the selector heard. [order] puts hardware first in the list itself.
 *
 * Declared limits are conservative: a hardware decoder usually plays a file somewhat past them, and
 * VLC owes much of its smoothness to trying. If it fails to start, the renderer falls back to the
 * next decoder in the list, so trying costs a moment; not trying cost every frame.
 */
@OptIn(UnstableApi::class)
internal object HardwareFirst {

    /** [caps] with "past its limits" read as "handled"; every other answer, and every other bit, as it was. */
    fun upgrade(caps: Int): Int =
        if (caps and RendererCapabilities.FORMAT_SUPPORT_MASK == C.FORMAT_EXCEEDS_CAPABILITIES) {
            (caps and RendererCapabilities.FORMAT_SUPPORT_MASK.inv()) or C.FORMAT_HANDLED
        } else {
            caps
        }

    /**
     * [decoders] with every hardware decoder that [decodes] the file moved to the front. Pure.
     *
     * Each half keeps the order it came in, which is the renderer's own: among the hardware ones, a
     * decoder within its limits still goes before one past them. "Decodes" is the profile, not the
     * level: a hardware decoder that does not know the file's profile (10-bit H.264 on most phones)
     * draws garbage rather than failing, so it is left where the renderer put it.
     */
    fun <T> order(decoders: List<T>, hardware: (T) -> Boolean, decodes: (T) -> Boolean): List<T> {
        val (first, rest) = decoders.partition { hardware(it) && decodes(it) }
        return first + rest
    }

    /** Media3's video renderer, asking for decoders in [order] and answering for formats through [upgrade]. */
    class VideoRenderer(context: Context, builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
        private val app = context.applicationContext
        private var logged: Format? = null

        override fun getDecoderInfos(
            mediaCodecSelector: MediaCodecSelector,
            format: Format,
            requiresSecureDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val sorted = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
            val ordered = order(sorted, { it.hardwareAccelerated }, { runCatching { it.isFormatFunctionallySupported(app, format) }.getOrDefault(false) })
            // Once per format: the order a bug report about a slow or black picture needs.
            if (logged != format) {
                logged = format
                dev.niccc2007.filet.log.FiletLog.i("video") {
                    "decoders for ${format.sampleMimeType} ${format.width}x${format.height}" +
                        (if (format.frameRate > 0) " @${format.frameRate}" else "") + ": " +
                        ordered.joinToString { it.name + if (it.hardwareAccelerated) " (hardware)" else "" }
                }
            }
            return ordered
        }

        override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
            upgrade(super.supportsFormat(mediaCodecSelector, format))
    }

    /**
     * Swaps Media3's own video renderer in [out] for [VideoRenderer], built with what the factory
     * built it with. Called from a factory's `buildVideoRenderers`, after the factory's own.
     */
    fun replace(
        out: ArrayList<Renderer>,
        context: Context,
        adapters: MediaCodecAdapter.Factory,
        selector: MediaCodecSelector,
        fallback: Boolean,
        handler: Handler,
        listener: VideoRendererEventListener,
        joiningMs: Long,
    ) {
        for (i in out.indices) {
            val r = out[i]
            if (r is MediaCodecVideoRenderer && r !is VideoRenderer) {
                out[i] = VideoRenderer(
                    context,
                    MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(adapters)
                        .setMediaCodecSelector(selector)
                        .setAllowedJoiningTimeMs(joiningMs)
                        .setEnableDecoderFallback(fallback)
                        .setEventHandler(handler)
                        .setEventListener(listener)
                        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                )
            }
        }
    }

    /** Media3's own factory with [VideoRenderer] in it: Filet's player where FFmpeg is not in the build. */
    class Factory(context: Context) : DefaultRenderersFactory(context) {
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
            replace(out, context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs)
        }
    }
}
