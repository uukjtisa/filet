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

    /** `https://ko-fi.com/<handle>`. Empty until the account exists. */
    const val URL = ""

    val configured: Boolean get() = URL.isNotBlank()
}

/**
 * The support panel, directly under the signature banner.
 *
 * ## Prominent, and why that is not a contradiction
 *
 * The first version of this was one quiet row among Source, Licence and Trawl. The brief
 * changed to prominent and noticeable, and the two are reconcilable because of WHERE it is:
 * About is a screen somebody opens deliberately, usually having already decided they like the
 * app. Being unmissable on a page nobody visits by accident is not the same as being pushy.
 *
 * What makes it quiet is everything it does NOT do, and that list is the real design:
 *
 *  - no popup, ever, on any launch count
 *  - no reminder, no "maybe later", no second ask
 *  - nothing is locked, limited, delayed, watermarked or counted behind it
 *  - no badge on the menu, no dot on the About entry
 *
 * ## The reason is on the card, in the app, not only in the README
 *
 * A bare Donate button asks for money without saying what for, which is the thing that reads
 * as begging. Two short lines carry it: the app is free and stays free, and the money pays for
 * the hardware and the time rather than for a feature somebody is waiting on. That framing is
 * also the honest one - there is no roadmap item gated on this.
 */
@Composable
fun SupportCard(onOpen: (String) -> Unit) {
    if (!SupportLink.configured) return
    val colors = Filet.colors

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            // The accent border is the whole of the prominence. A filled accent panel competes
            // with the signature banner immediately above it and makes the page read as two
            // headers; an outline at full accent is noticeable without becoming the subject.
            .border(1.dp, colors.accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .background(colors.raised)
            .clickable { onOpen(SupportLink.URL) }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                FiletIcons.Heart, null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(9.dp))
            Text(
                "Support Filet",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                FiletIcons.Link, null,
                tint = colors.fg3,
                modifier = Modifier.size(14.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "Filet is free, open source, and always will be. Nothing here is paid for, " +
                "nothing is locked, and this is the only place it is ever mentioned.",
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = colors.fg2,
        )

        Spacer(Modifier.height(6.dp))

        Text(
            "If it is useful to you, this covers the test devices and the hours. " +
                "If it is not, the app is exactly the same.",
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = colors.fg3,
        )

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accent.copy(alpha = 0.14f))
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                SupportLink.URL.removePrefix("https://"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
            )
        }
    }
}
