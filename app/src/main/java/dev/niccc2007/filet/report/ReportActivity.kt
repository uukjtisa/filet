package dev.niccc2007.filet.report

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.log.FiletLog
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.ui.theme.FiletTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Report a problem, from About or from the crash screen. One form, two ways in.
 *
 * ## Why an Activity and not a pane
 *
 * One of the two ways in is the crash screen, which runs after the app has died, in its own
 * process. A pane needs the browser's view model and the app's object graph, and building either
 * there would be rebuilding the thing that just crashed. So the form is a screen of its own that
 * needs neither: it reads the theme straight from preferences the way the crash screen does,
 * keeps its state in saved instance state rather than in a view model, and talks to the server
 * through [ReportClient], which needs nothing but a context.
 *
 * About opens [ReportActivity] in the app's own process, where the logger is running and its runs
 * can be listed. The crash screen opens [CrashReportActivity], the same form declared in the
 * crash process, so a crash during startup cannot take the report down with it.
 *
 * ## Nothing goes anywhere without a tap
 *
 * Including from the crash screen. Arriving with a crash fills in a suggested title and offers
 * the crash report as an attachment - unticked. A crash that reported itself would publish a
 * stack trace and a device description on a public page without anybody having read them.
 */
open class ReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Redacted on the way in, so the title, the preview and the attachment are all the same
        // cleaned text, and what is shown is what would be published. The logger's runs are
        // redacted when written; a crash report is not, and an exception message can carry a
        // share's URL with a password in it.
        val crash = intent.getStringExtra(EXTRA_CRASH)
            ?.lineSequence()?.joinToString("\n") { dev.niccc2007.filet.log.Redact.line(it) }
        setContent {
            // Prefs directly, never the graph: see the class comment, and CrashActivity.
            val prefs = remember { dev.niccc2007.filet.data.Prefs(this) }
            val theme by prefs.theme.collectAsState()
            val accent by prefs.accent.collectAsState()
            FiletTheme(theme = theme, accent = accent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ReportScreen(
                        crash = crash,
                        inCrashProcess = this is CrashReportActivity,
                        onOpen = { url -> open(url) },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        /** The crash report text, when arriving from the crash screen. */
        const val EXTRA_CRASH = "filet.report.crash"

        fun intent(context: Context): Intent = Intent(context, ReportActivity::class.java)

        fun forCrash(context: Context, report: String): Intent =
            Intent(context, CrashReportActivity::class.java).putExtra(EXTRA_CRASH, report)
    }
}

/** The same form, declared in the `:crash` process. See [ReportActivity]. */
class CrashReportActivity : ReportActivity()

/** Where the form is in its life. Saved as an int, so it survives a process death. */
private object Phase {
    const val EDITING = 0
    const val SENDING = 1
    const val FILED = 2
    const val FAILED = 3
}

@Composable
private fun ReportScreen(
    crash: String?,
    inCrashProcess: Boolean,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Filet.colors
    val scope = rememberCoroutineScope()
    // Before any saved picture path is read back: after a process death this is a new process,
    // and the cache helpers refuse every path until they know which cache is theirs.
    remember { Collect.bind(context) }

    // ── the draft, all of it saveable ──
    //
    // Rotation and a process death both bring the screen back through saved state, so a report
    // half-typed in the background is still there. Pictures are kept as cache files for the same
    // reason - see Collect.image.
    var kind by rememberSaveable { mutableStateOf(ReportForm.Kind.BUG.ordinal) }
    var title by rememberSaveable { mutableStateOf(crash?.let { ReportForm.crashTitle(it) } ?: "") }
    var description by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    var deviceOn by rememberSaveable { mutableStateOf(false) }
    var deviceText by rememberSaveable { mutableStateOf("") }
    // Its own field, sent with the log: the server holds the phone details to 2,000 characters
    // and a snapshot alone is several thousand. Appending it to the details made every report
    // that used the button one the server would refuse.
    var snapshot by rememberSaveable { mutableStateOf("") }
    var logOn by rememberSaveable { mutableStateOf(false) }
    var logName by rememberSaveable { mutableStateOf<String?>(null) }
    var pictures by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pictureNote by rememberSaveable { mutableStateOf<String?>(null) }
    // A report given up on leaves its pictures behind. This form keeps its own and no others.
    LaunchedEffect(Unit) {
        val own = pictures
        withContext(Dispatchers.IO) { Collect.clearPictures(context, own) }
    }
    // Made once per draft and kept across retries. See ReportForm.requestJson.
    val submissionId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var phase by rememberSaveable { mutableStateOf(Phase.EDITING) }
    var resultText by rememberSaveable { mutableStateOf("") }
    var retryable by rememberSaveable { mutableStateOf(true) }

    // A send that was in flight when the process died comes back as SENDING with nothing behind
    // it, and would spin for ever. It is turned into a failure that can be retried; the kept
    // submission id is what lets the server recognise the retry if the first attempt did land.
    LaunchedEffect(Unit) {
        if (phase == Phase.SENDING) {
            resultText = "Sending was interrupted. It may have arrived - trying again will not file it twice."
            retryable = true
            phase = Phase.FAILED
        }
    }

    // The runs the logger has kept. Empty in the crash process, where the logger is not running;
    // the crash report itself is offered there instead.
    val runs = remember { if (inCrashProcess) emptyList() else FiletLog.runs() }
    val logChoices: List<Pair<String, Long>> = remember(runs, crash) {
        buildList {
            if (crash != null) add(CRASH_LOG to crash.toByteArray().size.toLong())
            runs.forEach { add(it.name to it.bytes) }
        }
    }
    LaunchedEffect(logChoices) {
        if (logName == null) logName = logChoices.firstOrNull()?.first
    }

    // Pictures go through the same intake whichever picker supplied them: copied in, sized
    // against the server's limits, and attached only here, after an explicit pick. Nothing
    // leaves the phone until Send.
    fun takePictures(uris: List<Uri>) {
        scope.launch {
            var note: String? = null
            for ((i, uri) in uris.withIndex()) {
                val made = withContext(Dispatchers.IO) { Collect.image(context, uri, pictures.size + i) }
                val path = made.getOrElse { note = it.message ?: "That picture could not be added."; null } ?: continue
                val current = pictures.mapNotNull { Collect.sizeOf(it) }
                when (val v = ReportForm.imageVerdict(current, Collect.sizeOf(path) ?: 0L)) {
                    is ReportForm.ImageVerdict.Ok -> pictures = pictures + path
                    is ReportForm.ImageVerdict.No -> { note = v.why; Collect.delete(path) }
                }
            }
            pictureNote = note
        }
    }

    // Filet's own picker, through the very door other apps use (`PickActivity`), so there is
    // one picker rather than an internal twin. The system one is kept only in the crash
    // process: Filet's picker runs in the main process, and that may be the thing that crashed.
    val picker = rememberLauncherForActivityResult(FiletPictures) { takePictures(it) }
    val systemPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ReportForm.IMAGES_MAX),
    ) { takePictures(it) }

    fun draftNow(images: List<ReportForm.Image>): ReportForm.Draft {
        val log = if (!logOn) null else logName?.let { name ->
            val text = if (name == CRASH_LOG) crash else FiletLog.readRun(name)
            text?.let {
                val (fitted, _) = ReportForm.fitLog(it)
                ReportForm.Log(name, fitted, it.toByteArray().size.toLong())
            }
        }
        return ReportForm.Draft(
            kind = ReportForm.Kind.entries[kind],
            title = title,
            description = description,
            steps = steps,
            device = if (deviceOn) deviceText else null,
            log = log,
            images = images,
            snapshot = snapshot.ifBlank { null },
        )
    }

    // What Send would do, decided from what is on screen. Picture sizes are read from the files,
    // which is cheap; their bytes are only loaded at the moment of sending.
    val preview = ReportForm.Draft(
        kind = ReportForm.Kind.entries[kind],
        title = title,
        description = description,
        steps = steps,
        // Everything the limits are checked against, so Send is never offered for a report the
        // server would refuse.
        device = if (deviceOn) deviceText else null,
        snapshot = snapshot.ifBlank { null },
        images = pictures.filter { Collect.sizeOf(it) != null }.map {
            ReportForm.Image(Collect.nameOf(it), "image/jpeg", ByteArray(0))
        },
    )
    val blocker = ReportForm.blocker(preview)

    fun send() {
        phase = Phase.SENDING
        scope.launch {
            // Every picture on the form, read now. One that cannot be read any more stops the
            // send and is named. A report that quietly arrived without it is issue #22.
            val held = pictures
            val images = withContext(Dispatchers.IO) {
                held.map { path -> Collect.read(path)?.let { ReportForm.Image(Collect.nameOf(path), "image/jpeg", it) } }
            }
            val gone = images.mapIndexedNotNull { i, image -> if (image == null) i + 1 else null }
            if (gone.isNotEmpty()) {
                pictures = held.filterIndexed { i, _ -> images[i] != null }
                pictureNote = ReportForm.picturesGone(gone)
                phase = Phase.EDITING
                return@launch
            }
            val draft = withContext(Dispatchers.IO) { draftNow(images.filterNotNull()) }
            when (val r = ReportClient.send(context, draft, submissionId)) {
                is ReportForm.Outcome.Filed -> {
                    resultText = r.url
                    phase = Phase.FILED
                    withContext(Dispatchers.IO) { Collect.clearPictures(context) }
                }
                is ReportForm.Outcome.Failed -> {
                    // Back to a form that still holds everything. Losing a typed report on a
                    // failed send is the one thing that guarantees nobody reports twice.
                    resultText = r.message
                    retryable = r.retryable
                    phase = Phase.FAILED
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text("Report a problem", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        // Said plainly, because it is the fact somebody needs before typing anything: this goes
        // on a public page. Redaction in the logger is by pattern, and a secret with no shape it
        // recognises can get through, so the form does not promise that anything is cleaned.
        // PROSE OK: the one fact somebody needs before typing anything, and the form's consent.
        Text(
            "This becomes a public issue on Filet's GitHub page. No account is needed. Read " +
                "anything you attach before sending - it is published as it is shown here.",
            fontSize = 12.5.sp, lineHeight = 18.sp, color = colors.fg2,
        )

        if (phase == Phase.FILED) {
            Spacer(Modifier.height(18.dp))
            Panel {
                Text("Filed. Thank you.", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(resultText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.accent,
                    modifier = Modifier.clickable { onOpen(resultText) })
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormButton("Open the issue", primary = true) { onOpen(resultText) }
                    FormButton("Close", primary = false, onClick = onClose)
                }
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        Label("What kind")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReportForm.Kind.entries.forEachIndexed { i, k ->
                Chip(k.label, on = kind == i) { kind = i }
            }
        }

        Spacer(Modifier.height(14.dp))
        Field("Title", title, single = true) { title = it.take(ReportForm.TITLE_MAX) }
        Field("What happened", description, minLines = 4) { description = it }
        Field("Steps to make it happen again (optional)", steps, minLines = 3) { steps = it }

        // ── device ──
        Spacer(Modifier.height(10.dp))
        Tick(
            "Include details about this phone",
            "Shown in full below, and you can change it before sending.",
            deviceOn,
        ) { on ->
            deviceOn = on
            if (on && deviceText.isBlank()) deviceText = ReportForm.deviceText(Collect.device(context))
        }
        if (deviceOn) {
            Field("Exactly what will be published", deviceText, minLines = 4, mono = true) { deviceText = it }
            // Only where the logger runs. The snapshot is what a heat or lag report needs most:
            // temperatures, the thermal state and what the threads are doing.
            if (!inCrashProcess) {
                if (snapshot.isBlank()) {
                    FormButton("Add a heat and lag snapshot", primary = false) {
                        scope.launch { snapshot = FiletLog.dump(context, "issue report").trim() }
                    }
                } else {
                    Field("Heat and lag snapshot - sent with the log", snapshot, minLines = 4, mono = true) { snapshot = it }
                    FormButton("Remove the snapshot", primary = false) { snapshot = "" }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── log ──
        if (logChoices.isNotEmpty()) {
            Tick(
                if (crash != null) "Attach the crash report or a log" else "Attach a log",
                "Filet's own record of what it was doing. Detailed logging in Settings makes it more useful.",
                logOn,
            ) { logOn = it }
            if (logOn) {
                Column(Modifier.padding(start = 12.dp)) {
                    for ((name, bytes) in logChoices.take(6)) {
                        val label = if (name == CRASH_LOG) "The crash report" else name
                        val current = runs.firstOrNull { it.name == name }?.current == true
                        Chip(
                            label + "  ·  " + ReportForm.size(bytes) + if (current) "  ·  this run" else "",
                            on = logName == name,
                        ) { logName = name }
                        Spacer(Modifier.height(4.dp))
                    }
                    val chosen = logChoices.firstOrNull { it.first == logName }
                    if (chosen != null && chosen.second > ReportForm.LOG_MAX_BYTES) {
                        Text(
                            "Large: only the last " + ReportForm.mb(ReportForm.LOG_MAX_BYTES) + " of " +
                                ReportForm.mb(chosen.second) + " is attached.",
                            fontSize = 11.sp, color = colors.fg3,
                        )
                    }
                    if (logName == CRASH_LOG && crash != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            crash, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.fg2,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp)).background(colors.sunken).padding(8.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── pictures ──
        Spacer(Modifier.height(6.dp))
        Label("Pictures (optional)  ·  ${pictures.size} of ${ReportForm.IMAGES_MAX}")
        Text(
            ReportForm.mb(ReportForm.IMAGE_MAX_BYTES) + " each, " +
                ReportForm.mb(ReportForm.IMAGES_TOTAL_MAX_BYTES) + " in all. Location data is removed.",
            fontSize = 11.sp, lineHeight = 15.sp, color = colors.fg3,
        )
        Spacer(Modifier.height(8.dp))
        if (pictures.isNotEmpty()) {
            dev.niccc2007.filet.ui.HScroll(ground = MaterialTheme.colorScheme.background, spacing = 8.dp) {
                for (path in pictures) {
                    Thumb(path) {
                        Collect.delete(path)
                        pictures = pictures - path
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (pictures.size < ReportForm.IMAGES_MAX) {
            FormButton("Add pictures", primary = false) { picker.launch(Unit) }
            if (inCrashProcess) {
                Text(
                    "Filet's picker not opening? Use the phone's own",
                    fontSize = 11.sp,
                    color = colors.accent,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable {
                            systemPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                )
            }
        }
        pictureNote?.let { Text(it, fontSize = 11.sp, color = colors.bad, modifier = Modifier.padding(top = 6.dp)) }

        // ── send ──
        Spacer(Modifier.height(20.dp))
        if (phase == Phase.FAILED) {
            Panel {
                Text(resultText, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                // The fallback, and labelled as one: a browser, a GitHub account, and the text
                // carried across. It is offered when the server cannot take the report, never as
                // the way reports are sent.
                FormButton("Open on GitHub instead", primary = false) {
                    onOpen(fallbackUrl(title, ReportForm.issueBody(preview)))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val sending = phase == Phase.SENDING
            FormButton(
                when {
                    sending -> "Sending…"
                    phase == Phase.FAILED && retryable -> "Try again"
                    else -> "Send"
                },
                primary = true,
                enabled = blocker == null && !sending,
            ) { send() }
            Spacer(Modifier.width(10.dp))
            if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else FormButton("Cancel", primary = false, onClick = onClose)
        }
        blocker?.let { Text(it, fontSize = 11.sp, color = colors.fg3, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The repository's own new-issue page with the title and body carried over.
 *
 * The fallback only - it needs a browser and a GitHub account, which are exactly what the form
 * exists to make unnecessary. Pictures cannot travel in a URL; they are the one thing this loses.
 */
private fun fallbackUrl(title: String, body: String): String =
    "https://github.com/uukjtisa/filet/issues/new?title=" + Uri.encode(title) +
        // The label applies when the person filing can triage, which the maintainer can; for
        // anyone else GitHub ignores it rather than refusing the page.
        "&labels=app-report" +
        "&body=" + Uri.encode(body.take(6000))

private const val CRASH_LOG = "crash report"

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Filet.colors.fg2,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    single: Boolean = false,
    minLines: Int = 1,
    mono: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = single,
        minLines = if (single) 1 else minLines,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontSize = if (mono) 11.sp else 14.sp,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

@Composable
private fun Tick(title: String, sub: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, fontSize = 11.sp, lineHeight = 15.sp, color = Filet.colors.fg3)
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 12.sp,
        color = if (on) MaterialTheme.colorScheme.onPrimary else colors.fg2,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) colors.accent else colors.sunken)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val colors = Filet.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.raised)
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp)).padding(12.dp),
    ) { content() }
}

@Composable
private fun FormButton(label: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 12.5.sp,
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        color = when {
            !enabled -> colors.fg3
            primary -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary && enabled) colors.accent else colors.raised)
            .border(1.dp, if (primary && enabled) colors.accent else colors.lineSoft, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** A picture's preview with the cross that removes it. */
@Composable
private fun Thumb(path: String, onRemove: () -> Unit) {
    val colors = Filet.colors
    val bitmap = remember(path) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
                ?.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).background(colors.sunken)) {
        if (bitmap != null) {
            Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            "✕",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                .clip(RoundedCornerShape(50)).background(colors.bad)
                .clickable(onClick = onRemove).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            ReportForm.size(Collect.sizeOf(path) ?: 0L),
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                .clip(RoundedCornerShape(4.dp)).background(colors.fg2.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
