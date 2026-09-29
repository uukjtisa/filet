package dev.niccc2007.filet.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.MainActivity
import dev.niccc2007.filet.shortcuts.ShortcutRouterActivity
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.ui.theme.FiletTheme

/**
 * What the user sees instead of "Filet has stopped".
 *
 * Three things it must do, in this order: say plainly that it crashed and that nothing was
 * sent anywhere, put the trace where it can be copied, and get them back into the app in one
 * tap. The stack trace is shown rather than hidden behind "details" because the person most
 * likely to read this is the one who can fix it.
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val report = intent.getStringExtra(CrashReport.EXTRA_REPORT)
            ?: "No report was captured."
        // Null when the public write failed - a revoked storage permission, a full volume. The
        // screen then says the report is on screen only, which is true, instead of naming a
        // path that does not exist.
        val reportFile = intent.getStringExtra(CrashReport.EXTRA_REPORT_FILE)

        setContent {
            // Prefs directly, never the graph. This runs in its own `:crash` process, so
            // asking for the graph would build a second SQLite index and a second server just
            // to read two colours - and a crash screen that crashes is worse than useless.
            val prefs = remember { dev.niccc2007.filet.data.Prefs(this) }
            val theme by prefs.theme.collectAsState()
            val accent by prefs.accent.collectAsState()

            FiletTheme(theme = theme, accent = accent) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CrashScreen(
                        report = report,
                        reportFile = reportFile,
                        onCopy = { copy(this, report) },
                        onCopyPath = { reportFile?.let { copy(this, it) } },
                        onOpenFile = { reportFile?.let { openInFilet(it) } },
                        onRestart = {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            )
                            finishAffinity()
                        },
                        onClose = { finishAffinity() },
                    )
                }
            }
        }
    }

    private fun copy(context: Context, text: String) {
        val clip = context.getSystemService(ClipboardManager::class.java) ?: return
        clip.setPrimaryClip(ClipData.newPlainText("Filet crash report", text))
    }

    /**
     * Open the report in Filet's own text viewer.
     *
     * Filet is a file manager, so the natural reader for its own log is itself - and a
     * `file://` intent to anything else is blocked on every modern Android anyway.
     *
     * `CLEAR_TASK` is deliberately NOT set: this is a fresh start of the app that just died,
     * and if the crash happens during startup the person needs the crash screen to still be
     * behind them rather than replaced.
     */
    private fun openInFilet(path: String) {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ShortcutRouterActivity.EXTRA_TARGET, "local://" + path)
                    .putExtra(ShortcutRouterActivity.EXTRA_HANDLER, "TEXT"),
            )
        }
    }
}

@Composable
private fun CrashScreen(
    report: String,
    reportFile: String?,
    onCopy: () -> Unit,
    onCopyPath: () -> Unit,
    onOpenFile: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = Filet.colors
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Filet crashed",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (reportFile != null) {
                "Nothing was sent anywhere. Saved on this device."
            } else {
                // Still says which case it is. "Saved" when the write failed is the kind of
                // reassurance that costs somebody the report they were told they had.
                "Nothing was sent anywhere. Saving to storage failed - copy it to keep it."
            },
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = colors.fg2,
        )

        // The file, named. The item asked for the screen to point at the report it wrote, and a
        // path is only a pointer if you can read it, copy it and open it.
        if (reportFile != null) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.sunken)
                    .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
                    .padding(11.dp),
            ) {
                Text(
                    "SAVED TO",
                    fontSize = 9.5.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.fg3,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    reportFile,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.fg2,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CrashButton("Open it in Filet", primary = false, onClick = onOpenFile)
                    CrashButton("Copy the path", primary = false, onClick = onCopyPath)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CrashButton("Copy report", primary = true, onClick = onCopy)
            CrashButton("Restart Filet", primary = false, onClick = onRestart)
            CrashButton("Close", primary = false, onClick = onClose)
        }

        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.raised)
                .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                report,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.fg2,
                // Horizontal scroll as well: wrapping a stack trace makes it unreadable.
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CrashButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        color = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) colors.accent else colors.raised)
            .border(1.dp, if (primary) colors.accent else colors.lineSoft, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
