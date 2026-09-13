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
                        onCopy = { copy(this, report) },
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
}

@Composable
private fun CrashScreen(
    report: String,
    onCopy: () -> Unit,
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
            "Nothing was sent anywhere. The report below is on this device only, and it is " +
                "also saved so you can find it again from About.",
            fontSize = 13.sp,
            color = colors.fg2,
        )
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
