// `FontVariation` is still marked experimental. Opted in at the file level rather than at the
// call site, because the whole purpose of this file is that one API - a narrower annotation
// would just be the same opt-in written in more places.
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.niccc2007.filet.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.niccc2007.filet.R

/**
 * The one display face, and the only font this app bundles.
 *
 * ## Why a font at all, when everything else is the system's
 *
 * Filet draws its body text in whatever the phone uses, deliberately - a file manager full of
 * filenames should look like the phone it is on. The signature card is the one surface where
 * that is wrong: it is a maker's mark, it says who built this, and a maker's mark set in the
 * system UI font reads as a label rather than as a signature.
 *
 * Fraunces is 130 KB against a 28.7 MB APK, which is 0.45%, and it is the same face the
 * companion app signs with - so the two cards read as one hand. SIL Open Font License 1.1,
 * which GPL-3 may ship; the licence travels with it in `licenses/Fraunces-OFL.txt` and is
 * recorded in `tools/check-licences.mjs` with the vendored sources, because a dependency that
 * is not in the Gradle catalogue is exactly the kind that escapes a dependency check.
 *
 * **Used for the signature card and nothing else.** A display serif in a file list is a
 * legibility problem at 12sp, and spreading it is how an app stops looking like it made a
 * choice and starts looking like it has a theme.
 */
private fun variableFont(weight: FontWeight) =
    Font(
        resId = R.font.fraunces,
        weight = weight,
        // A variable font needs the axis set as well as the weight declared. Without the
        // variation setting the renderer picks the file's default instance and every weight
        // comes out identical - the failure is silent and looks like the font not loading.
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

val FrauncesFamily = FontFamily(
    variableFont(FontWeight.W600),
    variableFont(FontWeight.W700),
)
