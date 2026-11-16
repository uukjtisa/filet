package dev.niccc2007.filet.nearby

import android.content.Context
import kotlin.math.hypot
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import android.widget.FrameLayout
import android.view.ViewConfiguration
import android.view.View
import android.view.MotionEvent
import android.view.KeyEvent
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.niccc2007.filet.browser.FileKind
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.data.Prefs
import dev.niccc2007.filet.ui.theme.DarkGround
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.ui.theme.FiletTheme
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * A send from another Filet, asked about over whatever app is open, in the look
 * [IncomingRules.look] picks - the side card, the mini card or the full dialog - and the file
 * viewed before accepting, full screen or in a floating window. Drawn in windows of Filet's own
 * above other apps, so nothing of Filet's full screen comes up.
 *
 * Two windows, never more: the ask, and the look (the viewer). Opening the look takes the ask
 * down; closing it puts the ask back while the send still waits. Inside Filet the same pieces are
 * drawn by [IncomingHost].
 *
 * Needs Android's "display over other apps" permission, granted once in its settings; without it
 * the same send asks with a notification instead (see [IncomingRules.place]).
 *
 * Always drawn on the dark side of the palette, as the viewers are: it floats over an app whose
 * colours are unknown, and the mock's card is a dark panel in either theme.
 */
class IncomingOverlay(private val context: Context, private val prefs: Prefs) {

    private val wm = context.getSystemService(WindowManager::class.java)

    private class Shown(val view: View, val owner: OverlayOwner)

    private var ask: Shown? = null
    private var lookWin: Shown? = null
    private var visible: MutableTransitionState<Boolean>? = null

    private var offer: PushOffer? = null
    private var look: AskLook = AskLook.CARD
    private var settings = IncomingSettings()
    private var inbox: PushInbox? = null

    /** The offer on screen now, or null. */
    var showing: String? = null
        private set

    fun canDraw(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * Shows [offer] as [look], replacing whatever was up. Main thread.
     *
     * Called again for the same send whenever anything about it is re-read; that leaves it as it
     * is - an open viewer included - unless the look itself changed.
     */
    fun show(offer: PushOffer, look: AskLook, settings: IncomingSettings, inbox: PushInbox) {
        this.settings = settings
        if (showing == offer.id && this.look == look && (ask != null || lookWin != null)) return
        removeAll()
        if (!canDraw()) return
        this.offer = offer
        this.look = look
        this.inbox = inbox
        showing = offer.id
        addAsk()
    }

    /** Takes it all down: the card slides out, a dialog or a viewer goes at once. Main thread. */
    fun hide() {
        lookWin?.let { remove(it) }
        lookWin = null
        val s = visible
        if (ask != null && s != null && look != AskLook.DIALOG) {
            s.targetState = false
        } else {
            removeAll()
        }
    }

    private fun addAsk() {
        val offer = offer ?: return
        val inbox = inbox ?: return
        if (look == AskLook.DIALOG) {
            val v = compose(back = { inbox.later() }) {
                FullDialog(
                    offer, settings, rememberThumbs(offer),
                    onAccept = { inbox.accept(offer, always = false) },
                    onDecline = { inbox.decline(offer) },
                    onClose = { inbox.later() },
                    onView = { openLook(it) },
                )
            }
            ask = add(v, fullScreen(focusable = true))
            return
        }
        val state = MutableTransitionState(false).apply { targetState = true }
        val v = compose(back = null) {
            // Gone once it has slid out: the window goes with it.
            LaunchedEffect(state.isIdle, state.currentState) {
                if (state.isIdle && !state.currentState) removeAll()
            }
            AnimatedVisibility(
                visibleState = state,
                enter = slideInHorizontally(tween(SLIDE_MS, easing = SLIDE)) { (it * 1.2f).roundToInt() } +
                    fadeIn(tween(SLIDE_MS, easing = SLIDE)),
                exit = slideOutHorizontally(tween(EXIT_MS)) { (it * 1.2f).roundToInt() } + fadeOut(tween(EXIT_MS)),
            ) {
                AskCard(offer, look, inbox) { openLook(it) }
            }
        }
        val px = context.resources.displayMetrics.density
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.END
            // Flush with the edge, so the card slides in from the screen's edge rather than from
            // a margin; the card's own end padding is the gap. Under the status bar.
            x = 0
            y = statusBar() + (8 * px).roundToInt()
        }
        ask = add(v, lp)
        visible = state
    }

    /** The viewer, in the style the settings ask for; [full] forces full screen. */
    private fun openLook(index: Int, full: Boolean = false) {
        val offer = offer ?: return
        val inbox = inbox ?: return
        ask?.let { remove(it) }
        ask = null
        visible = null
        lookWin?.let { remove(it) }
        lookWin = null
        if (full || settings.view == ViewStyle.FULL) {
            val v = compose(back = { closeLook() }) {
                FullViewer(
                    offer, index, settings, rememberThumbs(offer),
                    onClose = { closeLook() },
                    onSave = { inbox.accept(offer, always = false) },
                    onOpenFilet = { openFilet() },
                    onDecline = { inbox.decline(offer) },
                )
            }
            lookWin = add(v, fullScreen(focusable = true))
        } else {
            val m = context.resources.displayMetrics
            val w = (FLOAT_W.value * m.density).roundToInt()
            val h = (FLOAT_H.value * m.density).roundToInt()
            val margin = (12 * m.density).roundToInt()
            val lp = params(w, h, focusable = false).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (m.widthPixels - w - margin).coerceAtLeast(0)
                // Above the navigation bar, roughly: the first drag puts it anywhere.
                y = (m.heightPixels - h - margin - (48 * m.density).roundToInt()).coerceAtLeast(0)
            }
            val content = compose(back = null) {
                FloatViewer(
                    offer, index, settings, rememberThumbs(offer),
                    onClose = { closeLook() },
                    onFull = { openLook(index, full = true) },
                    // The window is moved by FloatFrame, by the screen's own coordinates.
                    onMove = { _, _ -> },
                    onResize = { _, _ -> },
                )
            }
            val frame = FloatFrame(
                context, wm, lp,
                minW = (FLOAT_MIN_W.value * m.density).roundToInt(),
                minH = (FLOAT_MIN_H.value * m.density).roundToInt(),
                grip = (32 * m.density).roundToInt(),
            )
            frame.addView(content.view)
            lookWin = add(Shown(frame, content.owner), lp)
        }
    }

    /** Back from the viewer: the ask returns while the send still waits. */
    private fun closeLook() {
        lookWin?.let { remove(it) }
        lookWin = null
        if (showing != null && ask == null) addAsk()
    }

    /** Filet comes up; being on screen, it takes the question over (see [IncomingHost]). */
    private fun openFilet() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
    }

    /** A ComposeView in Filet's dark card palette, inside a [BackFrame] when Back should answer. */
    private fun compose(back: (() -> Unit)?, content: @Composable () -> Unit): Shown {
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent {
                FiletTheme(theme = prefs.theme.value, accent = prefs.accent.value) {
                    DarkGround(CARD_BOTTOM) { content() }
                }
            }
        }
        if (back == null) return Shown(v, o)
        val frame = BackFrame(context, back)
        frame.addView(v)
        return Shown(frame, o)
    }

    private fun add(s: Shown, lp: WindowManager.LayoutParams): Shown? {
        if (runCatching { wm.addView(s.view, lp) }.isFailure) {
            s.owner.stop()
            return null
        }
        return s
    }

    private fun remove(s: Shown) {
        runCatching { wm.removeViewImmediate(s.view) }
        s.owner.stop()
    }

    private fun removeAll() {
        ask?.let { remove(it) }
        lookWin?.let { remove(it) }
        ask = null
        lookWin = null
        visible = null
        showing = null
        offer = null
        inbox = null
    }

    private fun params(w: Int, h: Int, focusable: Boolean) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        },
        // A card never takes the keyboard or the touches beside it: the app underneath stays
        // usable. A dialog or a full viewer takes focus, which is what lets Back reach it.
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    /** The whole screen, under the bars and the camera hole; the content keeps clear of them. */
    private fun fullScreen(focusable: Boolean) = params(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable,
    ).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }

    private fun statusBar(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * context.resources.displayMetrics.density).roundToInt()
    }
}

/**
 * Answers Back in a window that is not an activity's.
 *
 * Bug identified here: the full dialog and the viewer over other apps had no way back but their
 * own buttons. With predictive back on (the manifest opts in), Android no longer delivers the Back
 * key to a view at all; it asks the window's back dispatcher, which a bare window has no callback
 * in. So the callback is registered on this window's dispatcher while attached, and the key is
 * still caught for the versions before it.
 */
private class BackFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    private var callback: Any? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val c = OnBackInvokedCallback { onBack() }
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, c)
            callback = c
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (callback as? OnBackInvokedCallback)?.let { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
        }
        callback = null
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * The floating viewer's window: dragged by its body, sized by its corner, by the screen's own
 * coordinates.
 *
 * Bug identified here: a window moved by a drag measured inside it reports each new touch against
 * where the window now is, so every step partly undoes the last and the window crawls behind the
 * finger, then shakes. Raw coordinates are the screen's and do not move with the window. A tap is
 * left to the viewer's own buttons: the drag is taken only once the finger has moved past the
 * touch slop.
 */
private class FloatFrame(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val minW: Int,
    private val minH: Int,
    private val grip: Int,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0
    private var resizing = false
    private var dragging = false

    private fun down(e: MotionEvent) {
        downX = e.rawX
        downY = e.rawY
        startX = lp.x
        startY = lp.y
        startW = width
        startH = height
        resizing = e.x > width - grip && e.y > height - grip
        dragging = false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_MOVE -> if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) dragging = true
        }
        return dragging
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downX).roundToInt()
                val dy = (e.rawY - downY).roundToInt()
                val m = resources.displayMetrics
                if (resizing) {
                    lp.width = (startW + dx).coerceIn(minW, (m.widthPixels - lp.x).coerceAtLeast(minW))
                    lp.height = (startH + dy).coerceIn(minH, (m.heightPixels - lp.y).coerceAtLeast(minH))
                } else {
                    lp.x = (startX + dx).coerceIn(0, (m.widthPixels - width).coerceAtLeast(0))
                    lp.y = (startY + dy).coerceIn(0, (m.heightPixels - height).coerceAtLeast(0))
                }
                runCatching { wm.updateViewLayout(this, lp) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

/**
 * Asking the sending phone about its files before any is accepted: their tokens, then a thumbnail
 * of each picture or video. A thumbnail is a look, not a download, and the sender does not count it
 * as one; an older Filet that cannot give one leaves the tile showing the file's kind.
 */
internal object IncomingPeek {

    /** One offered file and the token the sending phone serves it by. */
    data class Peeked(val token: String, val file: PushFile)

    /** Thumbnails for the files named in [names], by name. Blocking. */
    fun thumbs(base: String, key: String, names: Set<String>, px: Int): Map<String, ImageBitmap> {
        val files = files(base, key) ?: return emptyMap()
        val out = HashMap<String, ImageBitmap>()
        for (p in files) {
            if (p.file.name !in names) continue
            thumb(base, key, p.token, px)?.let { out[p.file.name] = it }
        }
        return out
    }

    /** The offered files in the order sent, each with its token, or null when the share has gone. Blocking. */
    fun files(base: String, key: String): List<Peeked>? = runCatching {
        val (code, text) = PushInbox.get("$base/api/fast?k=${enc(key)}")
        if (code != 200) return null
        val arr = JSONObject(text).optJSONArray("files") ?: JSONArray()
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let {
                Peeked(it.optString("token"), PushFile(it.optString("name"), it.optLong("size", 0L), it.optBoolean("dir", false)))
            }
        }
    }.getOrNull()

    /** The address a player streams a file from. A look, so the sender does not count it as taken. */
    fun url(base: String, key: String, token: String): String = "$base/f/${enc(token)}?k=${enc(key)}&peek=1"

    /**
     * The top of a text file, at most [MAX_TEXT] bytes: enough to read what it is. A range is
     * asked for, and the read stops at the limit even when the sender ignores it. Blocking.
     */
    fun text(base: String, key: String, token: String): String? = runCatching {
        val conn = open(url(base, key, token)).apply { setRequestProperty("Range", "bytes=0-${MAX_TEXT - 1}") }
        try {
            if (conn.responseCode !in 200..206) return null
            val bytes = conn.inputStream.use { it.readUpTo(MAX_TEXT) }
            String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** A picture decoded no larger than [maxPx] on its short side, or null past [MAX_PICTURE]. Blocking. */
    fun picture(base: String, key: String, token: String, maxPx: Int): ImageBitmap? = runCatching {
        val conn = open(url(base, key, token))
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_PICTURE) } ?: return null
            decode(bytes, maxPx)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 4_000
        readTimeout = 8_000
    }

    private fun decode(bytes: ByteArray, px: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    private fun thumb(base: String, key: String, token: String, px: Int): ImageBitmap? = runCatching {
        val conn = open("$base/t/${enc(token)}?k=${enc(key)}")
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_THUMB) } ?: return null
            decode(bytes, px)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** At most [max] bytes from the start of the stream; the rest is never read. */
    private fun java.io.InputStream.readUpTo(max: Int): ByteArray {
        val buf = ByteArray(max)
        var got = 0
        while (got < max) {
            val n = read(buf, got, max - got)
            if (n < 0) break
            got += n
        }
        return buf.copyOf(got)
    }

    /** The whole stream when it is at most [max] bytes, else null: a thumbnail is never large. */
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > max) return null
        }
        return buf.toByteArray()
    }

    private const val MAX_THUMB = 2 * 1024 * 1024
    private const val MAX_TEXT = 64 * 1024
    private const val MAX_PICTURE = 40 * 1024 * 1024
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** Lifecycle, saved state and view models for a Compose window that belongs to no activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
