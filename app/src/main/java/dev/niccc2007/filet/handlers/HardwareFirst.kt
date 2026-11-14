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
import androidx.media3.exoplayer.audio.AudioSink
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
 * VLC owes much of its smoothness to trying. Trying is a test start, once per kind of file (see
 * [HardwareProbe]): a decoder that starts gets the file, set up the way it started, and one that
 * refuses is passed over, so the picture goes to FFmpeg where the build has it rather than to
 * Google's software decoder.
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

    /**
     * Why a decoder would not start, in a line: the platform's error code where there is one
     * (1100 is "not enough resources", which is the chip already being busy), else the exception.
     */
    fun reason(error: Throwable): String {
        var e: Throwable? = error
        var deepest = error
        while (e != null) {
            if (e is android.media.MediaCodec.CodecException) {
                val code = runCatching { e.errorCode }.getOrDefault(0)
                return reasonWords("CodecException", e.message, code)
            }
            deepest = e
            e = e.cause?.takeIf { it !== e }
        }
        return reasonWords(deepest.javaClass.simpleName, deepest.message, null)
    }

    /** The words of [reason], kept apart so they can be tested off the device. Pure. */
    fun reasonWords(kind: String, message: String?, code: Int?): String {
        val what = when (code) {
            null, 0 -> null
            1100 -> "not enough resources (the decoder is busy)"
            1101 -> "taken back by the system"
            else -> "error $code"
        }
        val text = message?.lineSequence()?.firstOrNull()?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
        return listOfNotNull(what, text).joinToString(": ").ifEmpty { kind }
    }

    /** Media3's `CODEC_OPERATING_RATE_UNSET`: no operating rate given to the decoder. */
    const val NO_OPERATING_RATE = -1f

    /**
     * Media3's video renderer, asking for decoders in [order] and answering for formats through
     * [upgrade] - but only for a hardware decoder that really starts on files like this one, and
     * set up the way it was found to start. See [HardwareProbe].
     */
    class VideoRenderer(context: Context, builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
        private val app = context.applicationContext
        private var logged: Format? = null

        override fun getDecoderInfos(
            mediaCodecSelector: MediaCodecSelector,
            format: Format,
            requiresSecureDecoder: Boolean,
        ): List<MediaCodecInfo> {
            val sorted = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
            // A hardware decoder found to refuse files like this one stays where the renderer put
            // it, behind the decoders that declare the file within their limits: asking it again
            // would only fail again, a few milliseconds each time.
            val ordered = order(
                sorted,
                { it.hardwareAccelerated && HardwareProbe.refused(it.name, format) != true },
                { runCatching { it.isFormatFunctionallySupported(app, format) }.getOrDefault(false) },
            )
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

        /**
         * Every start written down with the setup it was given: the half of a refusal the
         * platform's message leaves out. A decoder the test start found to start only with no rate
         * declared is set up that way here - see [HardwareProbe.Start.UNDECLARED].
         */
        override fun getMediaCodecConfiguration(
            codecInfo: MediaCodecInfo,
            format: Format,
            crypto: android.media.MediaCrypto?,
            codecOperatingRate: Float,
        ): MediaCodecAdapter.Configuration {
            val config = super.getMediaCodecConfiguration(codecInfo, format, crypto, codecOperatingRate)
            val undeclared = HardwareProbe.undeclared(codecInfo.name, format)
            if (undeclared) HardwareProbe.undeclare(config.mediaFormat)
            dev.niccc2007.filet.log.FiletLog.i("video") {
                "starting ${codecInfo.name}" + (if (undeclared) " with no rate declared" else "") + ": ${config.mediaFormat}"
            }
            return config
        }

        /**
         * No operating rate for a decoder that only starts without one. Bug identified here: taking
         * the rate out of the first setup alone is not enough, because Media3 sets the operating
         * rate again on the running decoder once it has estimated the frame rate, or when the
         * speed changes, and the chip would be asked again for the budget it already refused.
         */
        override fun getCodecOperatingRateV23(targetPlaybackSpeed: Float, format: Format, streamFormats: Array<out Format>): Float {
            val info = codecInfo
            if (info != null && HardwareProbe.undeclared(info.name, format)) return NO_OPERATING_RATE
            return super.getCodecOperatingRateV23(targetPlaybackSpeed, format, streamFormats)
        }

        /**
         * "Past its limits" read as "handled" only when a hardware decoder that knows the file's
         * profile has been seen to start on files like it. Otherwise the answer stays "past its
         * limits", and where FFmpeg is in the build its renderer, which answers "handled", gets the
         * picture instead of Google's software decoder. See [HardwareProbe].
         */
        override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
            val caps = super.supportsFormat(mediaCodecSelector, format)
            if (caps and RendererCapabilities.FORMAT_SUPPORT_MASK != C.FORMAT_EXCEEDS_CAPABILITIES) return caps
            val mime = format.sampleMimeType ?: return caps
            val hardware = runCatching { mediaCodecSelector.getDecoderInfos(mime, false, false) }.getOrDefault(emptyList())
                .filter { it.hardwareAccelerated && runCatching { it.isFormatFunctionallySupported(app, format) }.getOrDefault(false) }
            val starts = hardware.any { HardwareProbe.starts(it.name, format) }
            // What was found, for Playback stats: the half of "FFmpeg, software" it cannot say.
            HardwareProbe.noteLook(
                format,
                HardwareProbe.lookWords(format.codecs, hardware.map { it.name to HardwareProbe.words(it.name, format) }),
            )
            return if (starts) upgrade(caps) else caps
        }
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
                        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                        // VLC's "hurry up", done before the decoder rather than after it: a frame
                        // that is already late and that no other frame is decoded from is thrown
                        // away unread. Bug identified here: the renderer decoded every frame and
                        // only then dropped the late ones, so a decoder that could not keep up
                        // spent all its time on pictures nobody would see and fell further behind,
                        // a second and more, until it jumped to the next keyframe. Which frames
                        // nothing depends on is read by the extractor: see Media3Engine.extractors.
                        .experimentalSetLateThresholdToDropDecoderInputUs(
                            MediaCodecVideoRenderer.DEFAULT_LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US,
                        ),
                )
            }
        }
    }

    /**
     * Media3's own factory with [VideoRenderer] in it, and [pan] in its sound: Filet's player where
     * FFmpeg is not in the build.
     */
    class Factory(context: Context, private val pan: SidePan) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            SidePan.sink(context, enableFloatOutput, enableAudioOutputPlaybackParams, pan)

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
