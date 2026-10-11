package dev.niccc2007.filet.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.ui.theme.Filet

/**
 * Where to send somebody who wants to pay for Filet, and nothing else about it.
 *
 * ## One constant, and blank means the card does not exist
 *
 * The address is an account detail rather than a thing to derive, so it is written here once
 * and read nowhere else. Blank is the default and blank hides the card completely - there is
 * no placeholder, no "coming soon" and no button that opens a 404.
 *
 * That is not caution for its own sake: a support link that goes nowhere is worse than no
 * support link, because the one person generous enough to tap it is the one who gets the
 * error. R1 in `CLAUDE.md` - no dead switches - and this is the shape it takes here.
 *
 * ## Ko-fi, not PayPal
 *
 * A PayPal donation can show the recipient's legal name to the person paying. Every public
 * surface of this project carries the handle, and a payment page is a public surface with the
 * worst possible failure mode - it is seen by exactly the people being invited to look.
 */
object SupportLink {

    /** `https://ko-fi.com/<handle>`. Empty hides the card; see the note above. */
    const val URL = "https://ko-fi.com/thethirdy2007"

    val configured: Boolean get() = URL.isNotBlank()
}

/**
 * The support panel: first row of the Project section, and the second loudest thing on the page.
 *
 * ## The hierarchy, which is the whole of the layout decision
 *
 * The signature card is the biggest thing on About and stays that way - it is the maker's mark
 * and the reason the screen exists. This sits below it, inside Project, and is deliberately
 * the only row there with a ground of its own: everything else in that list TELLS you
 * something, and this is the one that ASKS for something.
 *
 * It was first drawn directly under the banner as a full-width outlined block with two
 * paragraphs and a chip. That was too big and read as a second header arguing with the name
 * above it. A tinted ground with no border at row height says the same thing without
 * competing.
 *
 * **First in the section, above Source.** Buried under three links it has nothing to do with,
 * it reads as a footnote.
 *
 * ## What keeps it quiet is everything it does NOT do
 *
 *  - no popup, ever, on any launch count
 *  - no reminder, no "maybe later", no second ask
 *  - nothing is locked, limited, delayed, watermarked or counted behind it
 *  - no badge on the menu, no dot on the About entry
 *
 * ## The reason is on the card, not only in the README
 *
 * A bare Donate button asks for money without saying what for, which is the thing that reads
 * as begging. One line carries it: the app is free and stays free, and this pays for the
 * hardware and the time rather than for a feature somebody is waiting on. That framing is also
 * the honest one - there is no roadmap item gated on this.
 */
@Composable
fun SupportCard(onOpen: (String) -> Unit) {
    if (!SupportLink.configured) return
    val colors = Filet.colors

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            // A tint of the accent rather than a border. At 11% it is visible as a different
            // KIND of row without drawing a box around itself, which is what made the previous
            // version read as a panel bolted onto the page.
            .background(colors.accent.copy(alpha = 0.11f))
            .clickable { onOpen(SupportLink.URL) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Icon(
            FiletIcons.Heart, null,
            tint = colors.accent,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.size(11.dp))
        Column {
            Text(
                "Support Filet",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Free and open source, always. Nothing is locked — this covers the test " +
                    "devices and the hours.",
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = colors.fg2,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                SupportLink.URL.removePrefix("https://"),
                fontSize = 10.5.sp,
                color = colors.accent,
            )
        }
    }
}
