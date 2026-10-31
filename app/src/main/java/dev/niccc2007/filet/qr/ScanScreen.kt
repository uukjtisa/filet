package dev.niccc2007.filet.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PaneController
import dev.niccc2007.filet.browser.SplitMode
import dev.niccc2007.filet.log.FiletLog
import dev.niccc2007.filet.ui.tabs.TabButton
import dev.niccc2007.filet.ui.theme.Filet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A code the scanner has read, and whether it came from a picture rather than the camera. */
data class ScanHit(val text: String, val fromPicture: Boolean)

/**
 * The scanner: the camera, reading QR codes, and the same dialog a code in a photo opens.
 *
 * ## When the camera runs
 *
 * Only while this tab is the one on screen in its side. A tab swipe draws the neighbouring tab
 * for the length of the drag, and a camera started for a peek would light the privacy dot and
 * hand the camera back a second later; a tab left open behind others would hold the camera for
 * as long as the app lives. Both are decided by [onScreen], from the same active-tab state the
 * tab strip reads - not by composition, which a peek also is.
 *
 * Nothing is recorded. Each frame's brightness plane is read for a code and dropped; there is
 * no capture use case bound at all, so there is nothing to keep.
 */
@Composable
fun ScanScreen(vm: BrowserViewModel, pane: PaneController) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val app by vm.state.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val hit by vm.scanFound.collectAsState()
    val rebind by vm.cameraRevision.collectAsState()

    val hasCamera = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(cameraGranted(ctx)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var failed by remember(rebind) { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }

    // Allowed in Settings and come back: read it again on every return rather than once, or the
    // screen would keep saying the camera is off until the tab was reopened.
    DisposableEffect(owner) {
        val watch = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) granted = cameraGranted(ctx)
        }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }
    // Opening the scanner is asking to use the camera, so it is asked for straight away - once.
    // After a refusal the screen offers it again rather than reopening the prompt on its own.
    LaunchedEffect(Unit) {
        if (hasCamera && !granted && !asked) {
            asked = true
            ask.launch(Manifest.permission.CAMERA)
        }
    }

    val onScreen = tabs.getOrNull(app.activeA)?.id == pane.id ||
        (app.split != SplitMode.OFF && tabs.getOrNull(app.activeB)?.id == pane.id)
    val live = hasCamera && granted && onScreen && !failed
    LaunchedEffect(live) { if (!live) torch = false }

    val gate = remember { ScanGate() }
    val haptics = LocalHapticFeedback.current
    val showing by rememberUpdatedState(hit != null)
    val onFrame: (String) -> Unit = { text ->
        if (gate.admit(text, SystemClock.uptimeMillis(), showing)) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            vm.scanned(ScanHit(text, fromPicture = false))
        }
    }

    Box(Modifier.fillMaxSize().background(if (live) Color.Black else MaterialTheme.colorScheme.background)) {
        when {
            live -> {
                CameraFeed(
                    rebind = rebind,
                    torch = torch,
                    onTorchKnown = { hasTorch = it },
                    onFrame = onFrame,
                    onFailed = { failed = true },
                )
                Viewfinder()
                Text(
                    "Point at a QR code",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (hasTorch) CamPill(FiletIcons.Torch, if (torch) "Light on" else "Light", on = torch) { torch = !torch }
                    CamPill(FiletIcons.Image, "From a picture", on = false) { vm.scanPicture() }
                }
            }
            !onScreen -> Unit
            else -> Unavailable(
                title = when {
                    !hasCamera -> "This device has no camera"
                    failed -> "The camera did not start"
                    else -> "The camera is off for Filet"
                },
                body = when {
                    !hasCamera -> "A code in a picture can still be read."
                    failed -> "Another app may be using it. Close that app, then try again."
                    refused -> "Scanning needs the camera. If Android no longer asks, allow it in this app's settings."
                    else -> "Scanning needs the camera. Nothing is recorded: each frame is read for a code and dropped."
                },
            ) {
                when {
                    !hasCamera -> Unit
                    failed -> TabButton("Try again", primary = true) { vm.bumpCamera() }
                    else -> {
                        TabButton("Allow the camera", primary = true) { ask.launch(Manifest.permission.CAMERA) }
                        if (refused) {
                            TabButton("App settings") {
                                vm.onIntent?.invoke(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
                                )
                            }
                        }
                    }
                }
                TabButton("From a picture", icon = FiletIcons.Image) { vm.scanPicture() }
            }
        }
    }

    hit?.let { h ->
        QrDialog(QrParse.parse(h.text), vm, fromPicture = h.fromPicture) {
            if (!h.fromPicture) gate.closed(h.text, SystemClock.uptimeMillis())
            vm.scanned(null)
        }
    }
}

private fun cameraGranted(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * The preview and the frame reader, bound to the activity's lifecycle so the camera is let go
 * when the app is in the background and taken back on return.
 *
 * TextureView rather than SurfaceView (`COMPATIBLE`): the whole app card is scaled and clipped
 * when the side drawer opens, and a SurfaceView is composited outside the view tree, so it
 * would stay full size and square-cornered over a card that had shrunk.
 */
@Composable
private fun CameraFeed(
    rebind: Int,
    torch: Boolean,
    onTorchKnown: (Boolean) -> Unit,
    onFrame: (String) -> Unit,
    onFailed: () -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val frame by rememberUpdatedState(onFrame)
    val failed by rememberUpdatedState(onFailed)
    val torchKnown by rememberUpdatedState(onTorchKnown)
    val view = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(owner, rebind) {
        val gone = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(ctx)
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            // A frame that cannot be decoded in time is dropped, never queued: a scanner that
            // falls behind shows a result for where the phone WAS pointed.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        // 720p: enough for a code at arm's length, a quarter of the pixels of a
                        // full sensor frame to search.
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .build()
        analysis.setAnalyzer(worker, LumaReader { text -> main.execute { if (!gone.get()) frame(text) } })

        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            if (gone.get()) return@addListener
            val p = runCatching { future.get() }.getOrNull()
            if (p == null) {
                FiletLog.w("scan", "no camera provider")
                failed()
                return@addListener
            }
            provider = p
            preview.setSurfaceProvider(view.surfaceProvider)
            // A tablet can have only the front camera; reading a code with it beats refusing.
            val selector = if (runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val bound = runCatching { p.bindToLifecycle(owner, selector, preview, analysis) }
                .onFailure { FiletLog.w("scan", "bind failed: $it") }
                .getOrNull()
            camera = bound
            if (bound == null) failed() else torchKnown(bound.cameraInfo.hasFlashUnit())
        }, main)

        onDispose {
            gone.set(true)
            runCatching { provider?.unbind(preview, analysis) }
            analysis.clearAnalyzer()
            worker.shutdown()
            camera = null
        }
    }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/**
 * Hands each frame's brightness plane to [QrDecode]. Only the Y plane: a QR code is black and
 * white, so the colour planes are half the data for none of the answer.
 */
private class LumaReader(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var buf = ByteArray(0)
    private val scratch = Luma.Scratch()
    private var frame = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val stride = plane.rowStride
            // The plane's buffer can stop short of a full last row - its final row is the image
            // width, not the stride - so it is copied into a whole stride-by-height array rather
            // than read in place, which would run off the end on the last line.
            val need = stride * it.height
            if (buf.size < need) buf = ByteArray(need)
            val src = plane.buffer
            src.rewind()
            src.get(buf, 0, minOf(src.remaining(), need))
            QrDecode.fromLuma(buf, stride, it.width, it.height, frame++, scratch)?.let(onText)
        }
    }
}

/** Where to point: four corners, with the rest of the frame dimmed. */
@Composable
private fun Viewfinder() {
    Canvas(Modifier.fillMaxSize()) {
        val side = minOf(size.width, size.height) * 0.62f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val radius = 18.dp.toPx()
        val dim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(left, top, left + side, top + side, CornerRadius(radius)))
        }
        drawPath(dim, Color.Black.copy(alpha = 0.38f))

        val arm = side * 0.16f
        val w = 3.5.dp.toPx()
        val right = left + side
        val bottom = top + side
        for ((corner, dx, dy) in listOf(
            Triple(Offset(left, top), 1f, 1f),
            Triple(Offset(right, top), -1f, 1f),
            Triple(Offset(left, bottom), 1f, -1f),
            Triple(Offset(right, bottom), -1f, -1f),
        )) {
            drawLine(Color.White, corner, corner + Offset(arm * dx, 0f), w, StrokeCap.Round)
            drawLine(Color.White, corner, corner + Offset(0f, arm * dy), w, StrokeCap.Round)
        }
    }
}

/** A control on the camera: light on dark, whatever the theme, because it sits on a picture. */
@Composable
private fun CamPill(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (on) Color.White else Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val tint = if (on) Color.Black else Color.White
        Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** No camera, no permission, or a camera that would not start - and what can still be done. */
@Composable
private fun Unavailable(title: String, body: String, actions: @Composable () -> Unit) {
    val colors = Filet.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(FiletIcons.Scan, null, tint = colors.fg3, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            fontSize = 12.5.sp,
            color = colors.fg3,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}
