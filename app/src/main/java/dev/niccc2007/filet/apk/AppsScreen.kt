package dev.niccc2007.filet.apk

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.PaneInsets
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Installed apps, and getting their APK back out.
 *
 * ## Why this is a tool rather than a curiosity
 *
 * The APK of an installed app is on the device and world-readable; what is missing is a way to
 * reach it. Backing up an app before an update breaks it, keeping a copy of something that has
 * left the store, moving an app to a phone with no network - all of them need the file, and
 * none of them need root.
 *
 * ## What it refuses to pretend
 *
 * A split install is extracted as a FOLDER of parts, not as one file. The base APK of a split
 * app deliberately lacks the density and ABI resources that live in its splits, so writing only
 * the base produces something that looks like a complete package and fails at install time -
 * later, elsewhere, and with nothing pointing back here. The row says how many parts before it
 * is tapped, rather than after.
 */
@Composable
fun AppsScreen(vm: BrowserViewModel, pane: dev.niccc2007.filet.browser.PaneController) {
    val colors = Filet.colors
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var showSystem by remember { mutableStateOf(false) }

    // Keyed on the revision the refresh bumps, so pulling to refresh re-reads the list rather
    // than redrawing the same one.
    val revision = vm.state.collectAsState().value.revision
    LaunchedEffect(revision) { apps = vm.installedApps() }

    // The query lives on the pane state and each special screen narrows its own rows from it -
    // see PaneController. A list that can run to four hundred entries is where that matters
    // most, and a search box that did nothing here would be a dead switch.
    val query by pane.state.collectAsState()
    val needle = query.search.query.trim()
    val shown = apps
        ?.filter { showSystem || !it.system }
        ?.filter {
            needle.isEmpty() ||
                it.label.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
        }
        .orEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Installed apps",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        apps == null -> "reading…"
                        needle.isNotEmpty() -> "${shown.size} matching · saved to ${InstalledApps.FOLDER}"
                        else -> "${shown.size} shown · saved to ${InstalledApps.FOLDER}"
                    },
                    fontSize = 10.5.sp,
                    color = colors.fg3,
                )
            }
            // The answer to "where did it go", one tap from the thing that put it there.
            Pill("Open folder", FiletIcons.FolderOpen) { vm.openExtractedApksFolder() }
            Spacer(Modifier.width(6.dp))
            Pill(
                if (showSystem) "Hide system" else "System",
                FiletIcons.Cog,
                on = showSystem,
            ) { showSystem = !showSystem }
        }

        when {
            apps == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
            }

            shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Text(
                    if (needle.isNotEmpty()) "Nothing matches \"$needle\"."
                    else "No apps are visible to Filet.",
                    fontSize = 13.sp,
                    color = colors.fg2,
                )
            }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = PaneInsets.bottom(false).dp),
            ) {
                items(shown, key = { it.packageName }) { app ->
                    AppRow(app, context) { vm.extractInstalled(app) }
                }
            }
        }
    }
}

@Composable
private fun Pill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) colors.accent.copy(alpha = 0.18f) else colors.high)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) colors.accent else colors.fg2, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = if (on) colors.accent else colors.fg2)
    }
}

@Composable
private fun AppRow(app: InstalledApp, context: android.content.Context, onExtract: () -> Unit) {
    val colors = Filet.colors
    // Loaded per row and only once: the icon is the fastest way to find an app in a list of
    // four hundred, and a list of identical glyphs is a list you have to read word by word.
    // Seeded from the cache so an already-decoded icon is there on the first frame, then
    // loaded off the main thread if it is not. The previous version decoded inside
    // `remember`, which runs during composition - so every row scrolling into view parsed an
    // APK's resource table before the frame could be drawn. See AppIcons.
    var icon by remember(app.packageName) { mutableStateOf(AppIcons.cached(app.packageName)) }
    LaunchedEffect(app.packageName) {
        if (icon == null) icon = AppIcons.of(context, app.packageName, 96)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onExtract)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            val bmp = icon
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, modifier = Modifier.size(32.dp))
            } else {
                Icon(FiletIcons.Apk, null, tint = colors.fg3, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                app.packageName,
                fontSize = 10.sp,
                color = colors.fg3,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(humanSize(app.sizeBytes), fontSize = 11.sp, color = colors.fg2)
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (app.split) Tag("${app.parts} parts", colors.warn)
                if (app.system) Tag("system", colors.fg3)
                if (app.versionName.isNotBlank()) Tag(app.versionName, colors.fg3)
            }
        }
    }
}

@Composable
private fun Tag(text: String, ink: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 9.sp,
        color = ink,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Filet.colors.sunken)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
