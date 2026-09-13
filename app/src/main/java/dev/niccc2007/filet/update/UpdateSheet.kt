package dev.niccc2007.filet.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.UpdateState
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The update sheet: what is new, how far the download has got, and Install.
 *
 * Every state of the check lands here, including "you are up to date". Pressing a button
 * called Check for updates and getting nothing back reads as a broken button, and the notes
 * for the build you are already on are worth reading anyway.
 *
 * Nothing here installs by itself. Install hands the APK to the system package installer,
 * which asks in its own UI, and the sheet stays up behind it so a cancelled install returns
 * to a screen that still remembers what it was doing.
 */
@Composable
fun UpdateSheet(vm: BrowserViewModel, state: UpdateState) {
    val colors = Filet.colors

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable { vm.dismissUpdate() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {}
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
        ) {
            when (state) {
                is UpdateState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = colors.accent)
                        Spacer(Modifier.width(11.dp))
                        Text("Looking for a newer build", fontSize = 14.sp)
                    }
                }

                is UpdateState.UpToDate -> {
                    Head("You are on the newest build", "Filet ${Updater.installed}")
                    Notes(state.release)
                    Buttons(
                        primary = null,
                        secondary = "Release page" to { vm.openReleasePage(state.release.url) },
                        close = { vm.dismissUpdate() },
                    )
                }

                is UpdateState.Available -> {
                    Head(
                        "Filet ${state.release.version} is out",
                        "You are on ${Updater.installed}" +
                            if (state.release.apkBytes > 0) "  ·  ${humanSize(state.release.apkBytes)} download" else "",
                    )
                    Notes(state.release)
                    Buttons(
                        primary = (if (state.release.apkUrl != null) "Download" else null)
                            to { vm.downloadUpdate(state.release) },
                        secondary = "Release page" to { vm.openReleasePage(state.release.url) },
                        close = { vm.dismissUpdate() },
                    )
                    if (state.release.apkUrl == null) {
                        Text(
                            "That release has no APK attached, so there is nothing to install " +
                                "from here. The release page has the files.",
                            fontSize = 10.5.sp, color = colors.warn, lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                is UpdateState.Downloading -> {
                    Head("Downloading ${state.release.version}", progressLine(state))
                    Spacer(Modifier.height(12.dp))
                    if (state.percent >= 0) {
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = colors.accent,
                        )
                    } else {
                        // The server did not send a length. An indeterminate bar is honest;
                        // a percentage invented from nothing is not.
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = colors.accent,
                        )
                    }
                    Buttons(primary = null, secondary = null, close = { vm.dismissUpdate() }, closeLabel = "Cancel")
                }

                is UpdateState.Ready -> {
                    Head(
                        "Ready to install",
                        "Filet ${state.release.version}  ·  ${humanSize(state.bytes)}",
                    )
                    Text(
                        "Android will ask you to confirm. Filet cannot install it for you.",
                        fontSize = 10.5.sp, color = colors.fg3, lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Buttons(
                        primary = "Install" to { vm.installUpdate() },
                        secondary = null,
                        close = { vm.dismissUpdate() },
                    )
                }

                is UpdateState.NoReleases -> {
                    Head(
                        "No releases published yet",
                        "You are running Filet ${Updater.installed}, built from source.",
                    )
                    Text(
                        "Nothing is tagged on GitHub for this app to offer. Build from the " +
                            "repository until the first release lands.",
                        fontSize = 11.sp, color = colors.fg3, lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Buttons(
                        primary = null,
                        secondary = "Open the repository" to {
                            vm.openReleasePage("https://github.com/uukjtisa/filet")
                        },
                        close = { vm.dismissUpdate() },
                    )
                }

                is UpdateState.Failed -> {
                    Head("Update check failed", state.reason)
                    Buttons(
                        primary = "Try again" to { vm.checkForUpdates() },
                        secondary = null,
                        close = { vm.dismissUpdate() },
                    )
                }
            }
        }
    }
}

private fun progressLine(state: UpdateState.Downloading): String = when {
    state.total > 0 -> "${humanSize(state.bytes)} of ${humanSize(state.total)}"
    state.bytes > 0 -> humanSize(state.bytes)
    else -> "starting"
}

@Composable
private fun Head(title: String, sub: String) {
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    if (sub.isNotEmpty()) {
        Text(sub, fontSize = 11.sp, color = Filet.colors.fg3, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * The release notes, scrollable and capped.
 *
 * Rendered as plain text rather than markdown. Release notes are short and a markdown renderer
 * is a dependency and a rendering bug surface; the raw text of a changelog reads fine.
 */
@Composable
private fun Notes(release: Release) {
    val notes = release.notes.trim()
    if (notes.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Filet.colors.raised)
            .padding(12.dp),
    ) {
        Text(
            notes,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = Filet.colors.fg2,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun Buttons(
    primary: Pair<String?, () -> Unit>?,
    secondary: Pair<String, () -> Unit>?,
    close: () -> Unit,
    closeLabel: String = "Close",
) {
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Sheet(closeLabel, filled = false, onClick = close)
        secondary?.let {
            Spacer(Modifier.width(8.dp))
            Sheet(it.first, filled = false, onClick = it.second)
        }
        primary?.first?.let { label ->
            Spacer(Modifier.width(8.dp))
            Sheet(label, filled = true, onClick = primary.second)
        }
    }
}

@Composable
private fun Sheet(text: String, filled: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        text,
        fontSize = 12.5.sp,
        color = if (filled) MaterialTheme.colorScheme.onPrimary else colors.fg2,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
