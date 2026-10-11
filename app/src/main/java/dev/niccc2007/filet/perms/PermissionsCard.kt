package dev.niccc2007.filet.perms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.ui.theme.Filet

/**
 * The permissions section: what Filet holds, what it does not, and what each one costs.
 *
 * ## What was wrong with the one this replaces
 *
 * It was two buttons under a sentence, and the first of them said **Grant all files** whether
 * or not all-files access was already granted. Nothing on the screen read the actual state,
 * so the one question somebody opens this section to answer - what do I currently allow? -
 * was the one thing it could not tell them.
 *
 * ## Three rules it follows now
 *
 *  1. **Every entry says what it is for, and says it as a loss.** "Without it Filet can only
 *     see folders you grant one at a time" is checkable. "Allows Filet to access storage" is
 *     the system's wording and tells nobody anything.
 *  2. **A granted entry has no button.** It has a state and, where the system allows it, a
 *     way to the page that can turn it off. Offering to grant something already held is the
 *     original fault and must not come back in a new shape.
 *  3. **Nothing here nags.** Filet works without every one of these; the list says what is
 *     missing and what that costs, and then stops.
 */
@Composable
fun PermissionsCard(onIntents: (List<android.content.Intent>) -> Unit) {
    val colors = Filet.colors
    val context = LocalContext.current
    val granted = rememberGrantedPermissions()
    val sdk = android.os.Build.VERSION.SDK_INT
    val shown = Perms.shownOn(sdk)
    val queue = Perms.requestQueue(sdk, granted)

    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(
            Perms.summary(sdk, granted),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = colors.fg2,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(10.dp))

        for (entry in shown) {
            PermissionRow(
                entry = entry,
                granted = entry.id in granted,
                onGrant = { onIntents(grantIntentFor(context, entry.id)) },
                onManage = { onIntents(listOf(appSettingsIntent(context))) },
            )
            Spacer(Modifier.height(6.dp))
        }

        if (queue.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                // The honest version of "ask for them all". Android has no API to request
                // several SPECIAL permissions, and each one backgrounds the app, so there is
                // no batch at any price - only a list of pages to visit, one at a time.
                "${queue.size} not granted. Android asks one page at a time.",
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = colors.fg3,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun PermissionRow(
    entry: Perms.Entry,
    granted: Boolean,
    onGrant: () -> Unit,
    onManage: () -> Unit,
) {
    val colors = Filet.colors
    val fixed = entry.kind == Perms.Kind.INSTALL
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.lineSoft, RoundedCornerShape(10.dp))
            .background(colors.raised)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A dot, not a tick and a cross. A row of red crosses down a list of OPTIONAL
        // permissions reads as a list of faults, and none of these is one.
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        granted && fixed -> colors.fg3
                        granted -> colors.accent
                        else -> Color.Transparent
                    },
                )
                .then(
                    if (granted) Modifier
                    else Modifier.border(1.5.dp, colors.fg3, RoundedCornerShape(4.dp)),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (fixed) entry.needed else if (granted) "Granted" else entry.needed,
                fontSize = 10.sp,
                lineHeight = 13.5.sp,
                color = colors.fg3,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            // Nothing to press. An install-time permission cannot be turned off, and a button
            // that opens a page with no switch on it is a dead end with extra steps.
            fixed -> Text("Always on", fontSize = 10.sp, color = colors.fg3)

            granted && entry.revocable -> Chip("Manage", quiet = true, onClick = onManage)

            granted -> Text("On", fontSize = 10.5.sp, color = colors.accent)

            else -> Chip("Grant", quiet = false, onClick = onGrant)
        }
    }
}

@Composable
private fun Chip(label: String, quiet: Boolean, onClick: () -> Unit) {
    val colors = Filet.colors
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = if (quiet) FontWeight.Normal else FontWeight.SemiBold,
        color = if (quiet) colors.fg2 else MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (quiet) colors.high else colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}
