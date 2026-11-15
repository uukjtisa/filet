package dev.niccc2007.filet.nearby

import android.content.Context
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
 * A send from another Filet, asked about over whatever app is open: the side card, or the
 * one-line mini card for a single file. Drawn in a window of its own above other apps, so nothing
 * of Filet's full screen comes up and the app underneath stays usable around it.
 *
 * Needs Android's "display over other apps" permission, granted once in its settings; without it
 * the same send asks with a notification instead (see [IncomingRules.surface]).
 *
 * Always drawn on the dark side of the palette, as the viewers are: it floats over an app whose
 * colours are unknown, and the mock's card is a dark panel in either theme.
 */
class IncomingOverlay(private val context: Context, private val prefs: Prefs) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var visible: MutableTransitionState<Boolean>? = null

    /** The offer on screen now, or null. */
    var showing: String? = null
        private set

    fun canDraw(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** Shows [offer] as [surface], replacing whatever card was up. Main thread. */
    fun show(offer: PushOffer, surface: AskSurface, inbox: PushInbox) {
        if (showing == offer.id && view != null) return
        removeNow()
        if (!canDraw()) return
        val state = MutableTransitionState(false).apply { targetState = true }
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setContent {
                FiletTheme(theme = prefs.theme.value, accent = prefs.accent.value) {
                    DarkGround(CARD_BOTTOM) {
                        // Gone once it has slid out: the window goes with it.
                        LaunchedEffect(state.isIdle, state.currentState) {
                            if (state.isIdle && !state.currentState) removeNow()
                        }
                        AnimatedVisibility(
                            visibleState = state,
                            enter = slideInHorizontally(tween(SLIDE_MS, easing = SLIDE)) { (it * 1.2f).roundToInt() } +
                                fadeIn(tween(SLIDE_MS, easing = SLIDE)),
                            exit = slideOutHorizontally(tween(EXIT_MS)) { (it * 1.2f).roundToInt() } + fadeOut(tween(EXIT_MS)),
                        ) {
                            val later = { inbox.later() }
                            Swipeable(onGone = later) {
                                when (surface) {
                                    AskSurface.MINI_CARD -> MiniCard(
                                        offer,
                                        onGet = { inbox.accept(offer, always = false) },
                                        onClose = later,
                                    )
                                    else -> SideCard(
                                        offer,
                                        onAccept = { always -> inbox.accept(offer, always) },
                                        onDecline = { inbox.decline(offer) },
                                        onClose = later,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val px = context.resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            },
            // Never takes the keyboard or the touches beside it: the app underneath stays usable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // Flush with the edge, so the card slides in from the screen's edge rather than from
            // a margin; the card's own end padding is the gap. Under the status bar.
            x = 0
            y = statusBar() + (8 * px).roundToInt()
        }
        if (runCatching { wm.addView(v, lp) }.isFailure) {
            o.stop()
            return
        }
        view = v
        owner = o
        visible = state
        showing = offer.id
    }

    /** Slides the card out, if one is up. Main thread. */
    fun hide() {
        val s = visible ?: return
        if (view == null) return
        s.targetState = false
    }

    private fun removeNow() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        owner?.stop()
        view = null
        owner = null
        visible = null
        showing = null
    }

    private fun statusBar(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * context.resources.displayMetrics.density).roundToInt()
    }

    private companion object {
        /** `.card{animation:slide .32s cubic-bezier(.2,.9,.25,1)}` */
        const val SLIDE_MS = 320
        const val EXIT_MS = 200
        val SLIDE = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
        val CARD_TOP = Color(0xFF22262A)
        val CARD_BOTTOM = Color(0xFF1C1F21)
        val CARD_LINE = Color(0xFF39444A)
    }

    /**
     * Swiped toward the edge it came from, it goes, and the send waits on the Share page as the
     * close button leaves it. A short drag springs back.
     */
    @Composable
    private fun Swipeable(onGone: () -> Unit, content: @Composable () -> Unit) {
        var dx by remember { mutableFloatStateOf(0f) }
        var width by remember { mutableFloatStateOf(1f) }
        Box(
            Modifier
                .offset { IntOffset(dx.roundToInt(), 0) }
                .draggable(
                    rememberDraggableState { d -> dx = (dx + d).coerceAtLeast(0f) },
                    Orientation.Horizontal,
                    onDragStopped = { if (dx > width * 0.35f) onGone() else dx = 0f },
                )
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) },
        ) { content() }
    }

    @Composable
    private fun SideCard(offer: PushOffer, onAccept: (Boolean) -> Unit, onDecline: () -> Unit, onClose: () -> Unit) {
        val colors = Filet.colors
        var always by remember(offer.id) { mutableStateOf(false) }
        val thumbs = rememberThumbs(offer)
        Column(
            Modifier
                .padding(end = 8.dp, bottom = 16.dp, start = 16.dp)
                .width(256.dp)
                .shadow(14.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(CARD_TOP, CARD_BOTTOM)))
                .border(1.dp, CARD_LINE, RoundedCornerShape(20.dp))
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFF9CC3D2), Color(0xFF4E7787)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(IncomingRules.initial(offer.name), color = Color(0xFF0E1418), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(offer.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(IncomingRules.wants(offer.files.size), fontSize = 11.sp, color = colors.fg2)
                }
                Icon(
                    FiletIcons.Close, "Later",
                    tint = colors.fg3,
                    modifier = Modifier.align(Alignment.Top).size(22.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
                )
            }
            Row(Modifier.padding(top = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val n = IncomingRules.tiles(offer.files.size)
                for (i in 0 until n) {
                    val more = if (i == n - 1) IncomingRules.moreOnLast(offer.files.size) else 0
                    Tile(offer.files[i], thumbs[offer.files[i].name], more, Modifier.weight(1f))
                }
                // One or two files keep their tiles the size three would have.
                for (i in n until 3) Spacer(Modifier.weight(1f))
            }
            Text(
                IncomingRules.summary(offer.files) { humanSize(it) },
                fontSize = 11.sp, color = colors.fg2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton("Decline", primary = false, Modifier.weight(1f), onDecline)
                CardButton("Accept", primary = true, Modifier.weight(1f)) { onAccept(always) }
            }
            // A phone that has not paired cannot be trusted by name, so the tick is not offered.
            if (offer.paired) {
                Row(
                    Modifier.padding(top = 9.dp).clip(RoundedCornerShape(6.dp)).clickable { always = !always }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (always) colors.accent else Color.Transparent)
                            .border(1.5.dp, if (always) colors.accent else colors.fg3, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (always) Icon(FiletIcons.Check, null, tint = Color(0xFF10100F), modifier = Modifier.size(10.dp))
                    }
                    Spacer(Modifier.width(7.dp))
                    Text("Always accept from this phone", fontSize = 11.sp, color = colors.fg2)
                }
            }
        }
    }

    /** `.mini`: one line, one tile, Get or dismiss. */
    @Composable
    private fun MiniCard(offer: PushOffer, onGet: () -> Unit, onClose: () -> Unit) {
        val colors = Filet.colors
        val f = offer.files.first()
        val thumbs = rememberThumbs(offer)
        Row(
            Modifier
                .padding(end = 8.dp, bottom = 12.dp, start = 12.dp)
                .width(256.dp)
                .shadow(10.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(CARD_TOP, CARD_BOTTOM)))
                .border(1.dp, CARD_LINE, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tile(f, thumbs[f.name], 0, Modifier.size(40.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name + if (f.dir) ".zip" else "", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(offer.name).append(" · fast share")
                        if (!f.dir && f.size > 0) append(" · ").append(humanSize(f.size))
                    },
                    fontSize = 10.5.sp, color = colors.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                "Get",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0E1418),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.accent).clickable(onClick = onGet).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Icon(
                FiletIcons.Close, "Later",
                tint = colors.fg3,
                modifier = Modifier.padding(start = 4.dp).size(24.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
            )
        }
    }

    @Composable
    private fun CardButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val colors = Filet.colors
        Box(
            modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (primary) colors.accent else colors.raised)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, fontSize = 13.sp,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
                color = if (primary) Color(0xFF0E1418) else MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    /** `.th`: the picture when the sender gave one, else the file's kind; ▶ on a video, "+N" for the rest. */
    @Composable
    private fun Tile(f: PushFile, art: ImageBitmap?, more: Int, modifier: Modifier) {
        val colors = Filet.colors
        Box(
            modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(colors.high),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                val icon = if (f.dir) FiletIcons.Folder else FileKind.of(VNode(VPath.of("local", "/" + f.name), isDir = false, size = f.size, mtime = 0)).icon
                Icon(icon, null, tint = colors.fg2, modifier = Modifier.size(18.dp))
            }
            if (more == 0 && IncomingRules.isVideo(f.name)) {
                Icon(FiletIcons.Play, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (more > 0) {
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                    Text("+$more", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }

    /** Thumbnails asked of the sending phone, by file name; empty until they arrive. */
    @Composable
    private fun rememberThumbs(offer: PushOffer): Map<String, ImageBitmap> {
        val px = (64 * context.resources.displayMetrics.density).roundToInt()
        val state = produceState<Map<String, ImageBitmap>>(emptyMap(), offer.id) {
            val wanted = offer.files.take(3).filter { !it.dir && IncomingRules.hasThumb(it.name) }
            if (wanted.isEmpty()) return@produceState
            value = withContext(Dispatchers.IO) { IncomingPeek.thumbs(offer.base, offer.key, wanted.map { it.name }.toSet(), px) }
        }
        return state.value
    }
}

/**
 * Asking the sending phone about its files before any is accepted: their tokens, then a thumbnail
 * of each picture or video. A thumbnail is a look, not a download, and the sender does not count it
 * as one; an older Filet that cannot give one leaves the tile showing the file's kind.
 */
internal object IncomingPeek {

    /** Thumbnails for the files named in [names], by name. Blocking. */
    fun thumbs(base: String, key: String, names: Set<String>, px: Int): Map<String, ImageBitmap> {
        val tokens = tokens(base, key) ?: return emptyMap()
        val out = HashMap<String, ImageBitmap>()
        for ((name, token) in tokens) {
            if (name !in names) continue
            thumb(base, key, token, px)?.let { out[name] = it }
        }
        return out
    }

    /** Each offered file's token, by name, or null when the share has gone. */
    private fun tokens(base: String, key: String): Map<String, String>? = runCatching {
        val (code, text) = PushInbox.get("$base/api/fast?k=${enc(key)}")
        if (code != 200) return null
        val arr = JSONObject(text).optJSONArray("files") ?: JSONArray()
        (0 until arr.length()).associate { i -> arr.getJSONObject(i).let { it.optString("name") to it.optString("token") } }
    }.getOrNull()

    private fun thumb(base: String, key: String, token: String, px: Int): ImageBitmap? = runCatching {
        val conn = (URL("$base/t/${enc(token)}?k=${enc(key)}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 8_000
        }
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_THUMB) } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

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
