package dev.niccc2007.filet.handlers

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BrowserViewModel
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.humanSize
import dev.niccc2007.filet.ui.dialogs.BtnKind
import dev.niccc2007.filet.ui.dialogs.Dlg
import dev.niccc2007.filet.ui.dialogs.DlgBody
import dev.niccc2007.filet.ui.dialogs.DlgBtn
import dev.niccc2007.filet.ui.dialogs.DlgFooter
import dev.niccc2007.filet.ui.dialogs.DlgHeader
import dev.niccc2007.filet.ui.dialogs.DlgPick
import dev.niccc2007.filet.ui.dialogs.DlgSection
import dev.niccc2007.filet.ui.dialogs.DlgSpacer
import dev.niccc2007.filet.ui.dialogs.DlgTick
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.VNode

/**
 * "Open with" - one dialogue, two jobs.
 *
 * ## The two it replaces
 *
 * There were two Open With dialogues and they did not look related:
 *
 *  - `OpenWithSheet`, a bottom sheet reached by tapping a file. Handler rows **with glyph
 *    icons**, a two-tier "likely / every other viewer" split, and two footer buttons.
 *  - `OpenerPicker`, an `AlertDialog` reached from Settings for one extension. The same handler
 *    list with **no icons**, a different row height, a different type scale, and a "Close".
 *
 * They are the same question. "Which of these should read this file" and "which of these should
 * read files like this one" differ in one thing - whether the answer is remembered - and that is
 * a tick, not a second dialogue. So: one composable, one layout, and [file] decides which job it
 * is doing. The icons come from the sheet, which was the half that had them.
 *
 * ## The "Just once" button is gone
 *
 * The sheet's footer was **Always** and **Just once**, side by side, which is a redundancy: the
 * pair asks the routing question twice and makes the reader work out that "Just once" means "and
 * do not remember". Now there is one action - Open - and a tick above it that says what will be
 * remembered if it is on. Unticked IS just once, which is also the default, so the common case
 * is one tap rather than a choice between two buttons that both open the file.
 *
 * That tick is also the specific complaint about the "always open with this" checkbox: it used
 * to be a box floating beside a label with the two in different places. [DlgTick] makes the
 * whole row the target and the whole row show the state.
 *
 * @param file the file to open, or **null** when this is configuring a type from Settings. With
 *   null there is nothing to open, so the footer sets the default instead and the tick is not
 *   shown - ticking "always" in a dialogue whose only purpose is to set "always" would be a
 *   control with no off state.
 * @param extension the type being routed. Taken separately rather than read off [file] because
 *   the Settings route has no file to read it from.
 */
@Composable
fun OpenWithDialog(
    vm: BrowserViewModel,
    file: VNode?,
    extension: String,
    onDismiss: () -> Unit,
) {
    val colors = Filet.colors
    val context = LocalContext.current
    val overrides by vm.handlers.overrides.collectAsState()
    val externals by vm.handlers.externals.collectAsState()
    val builtIn = remember(extension) { vm.handlers.builtInFor(extension) }
    val current = overrides[extension] ?: builtIn

    // The offer. For a real file it comes from the registry, which looks at the bytes as well as
    // the name; for a bare extension there is nothing to look at, so it is the name's list.
    //
    // Bug this preserved from the sheet: the list used to be built from the extension alone, so
    // a file the tables did not recognise was offered the code editor, the hex viewer and
    // "another app" and nothing else - a `.mcaddon`, which is a zip, could not be opened with
    // the archive viewer at all. Every viewer is reachable; the guess just goes first.
    val offer = remember(file?.path, extension) {
        if (file != null) vm.registry.offerFor(file) else null
    }
    val plain = remember(extension) { vm.handlers.candidatesForExtension(extension) }

    var chosen by remember(file?.path, extension) { mutableStateOf<HandlerId?>(null) }
    var showAll by remember(file?.path, extension) { mutableStateOf(false) }
    var always by remember(file?.path, extension) { mutableStateOf(false) }
    var pickingApp by remember(extension) { mutableStateOf(false) }

    val likely = offer?.likely ?: plain
    val rest = offer?.rest ?: emptyList()
    val candidates = if (showAll && offer != null) offer.all else likely

    // Second stage: "Another app" is not an answer until it names one.
    if (pickingApp) {
        val apps = remember(extension) {
            ExternalApps.candidates(context, mimeForExtension(extension))
        }
        // No dead end here. An extension nothing on the device DECLARES - `.mcaddon`, say -
        // resolves to the wildcard mime, which the candidate query deliberately answers with
        // nothing. Showing that as "No app for .mcaddon" states a fact Filet does not have:
        // Minecraft opens `.mcaddon` perfectly well, it simply never registered the type. So the
        // sheet is shown, it says the first tier is empty, and every launchable app is under it.
        AppPickerForExtension(extension, apps) { app ->
            pickingApp = false
            when {
                app == null -> Unit
                // A file in hand: open it in that app, and remember the app only if the tick
                // said so. This used to set the default unconditionally and never open the
                // file - "open this once in X" silently became a permanent rule that did not
                // even do the once.
                file != null -> {
                    // "Always this way" with Another app means the type now goes to an app,
                    // and to this one; openWithApp only re-routes a type already set to apps.
                    if (always) vm.handlers.setDefault(extension, HandlerId.EXTERNAL)
                    vm.openWithApp(file, app, remember = always)
                    onDismiss()
                }
                else -> {
                    vm.handlers.setExternal(extension, app, alsoRoute = true)
                    onDismiss()
                }
            }
        }
        return
    }

    Dlg(onDismiss = onDismiss) {
        DlgHeader(
            icon = FiletIcons.Open,
            title = if (file != null) "Open with" else "Open .$extension with",
            sub = if (file != null) "${file.name}  ·  ${humanSize(file.size)}" else null,
            onClose = onDismiss,
        )
        DlgBody {
            for (id in candidates) {
                DlgPick(
                    icon = iconOf(id),
                    label = id.label,
                    detail = openWithDetail(id, builtIn, current, externals[extension]?.label),
                    tag = if (id == HandlerId.EXTERNAL) "App" else null,
                    ours = false,
                    // Opening a file tracks what was tapped in this dialogue; configuring a
                    // type shows what the type is already set to, because there is no
                    // in-progress choice there - the tap IS the commit.
                    selected = if (file != null) id == chosen else id == current,
                ) {
                    if (file != null) {
                        chosen = id
                        if (id == HandlerId.EXTERNAL) pickingApp = true
                    } else {
                        // One tested function decides this; see OpenerChoice.kt. The order of
                        // its branches is the fix, and the .docx case in OpenerChoiceTest is
                        // what holds that order in place.
                        when (val choice = openerChoice(id, builtIn)) {
                            is OpenerChoice.PickApp -> pickingApp = true
                            is OpenerChoice.ClearOverride -> {
                                vm.handlers.clearDefault(extension); onDismiss()
                            }
                            is OpenerChoice.SetHandler -> {
                                vm.handlers.setDefault(extension, choice.id); onDismiss()
                            }
                        }
                    }
                }
            }

            if (!showAll && rest.isNotEmpty()) {
                // One more row, not a section with a preamble. The tier split is real - these
                // do not match the name - and a reader who wants to know that can read the
                // count; it does not need a sentence above it.
                DlgPick(
                    icon = FiletIcons.More,
                    label = "Show every viewer",
                    detail = "${rest.size} more",
                ) { showAll = true }
            }

            if (file != null) {
                Spacer(Modifier.height(10.dp))
                // Tickable before a row is picked. It used to wait for a choice, and choosing
                // "Another app" leaves this dialogue at once - so for that row there was never
                // a moment the tick could be set. Ticked first, it applies to whatever is picked.
                DlgTick(
                    on = always,
                    label = "Always open .$extension this way",
                ) { always = !always }
            }
        }
        DlgFooter {
            DlgBtn("Cancel", onClick = onDismiss)
            if (file != null) {
                DlgBtn("Open", kind = BtnKind.PRIMARY, enabled = chosen != null) {
                    chosen?.let { vm.openWith(file, it, remember = always) }
                }
            } else {
                // Nothing to press: choosing a row IS the commit when there is no file, so a
                // second button here would be a control with nothing left to do.
                DlgSpacer()
            }
        }
    }
}

/**
 * The second line on a handler row. Pure; see `OpenWithDetailTest`.
 *
 * Null rather than an empty string when there is nothing true to say - a row with a blank
 * second line is taller for no reason, and a row saying "default" when it is not is worse.
 *
 * Bug identified here: "Default" was written on the type's BUILT-IN viewer whatever the type was
 * actually set to. With `.xlsx` sent to an office app in Default openers, the sheet read
 * "Document reader - Default" while a tap on the file opened the other app - the label
 * contradicted the very behaviour it was there to explain. "Default" now goes on the row a tap
 * uses: [current], which is the override when there is one and the built-in otherwise.
 *
 * @param current what a plain tap on a file of this type opens.
 */
internal fun openWithDetail(id: HandlerId, builtIn: HandlerId?, current: HandlerId?, externalLabel: String?): String? = when {
    id == HandlerId.EXTERNAL && externalLabel == null -> "Pick an app"
    id == HandlerId.EXTERNAL && current == HandlerId.EXTERNAL -> "Default  ·  $externalLabel"
    id == HandlerId.EXTERNAL -> externalLabel
    id == current -> "Default"
    else -> null
}

/** A glyph per handler. Kept from the sheet, which was the half of the pair that had them. */
internal fun iconOf(h: HandlerId) = when (h) {
    HandlerId.TEXT -> FiletIcons.Code
    HandlerId.IMAGE -> FiletIcons.Image
    HandlerId.MEDIA -> FiletIcons.Play
    HandlerId.HEX -> FiletIcons.Hex
    HandlerId.ARCHIVE -> FiletIcons.Zip
    HandlerId.APK -> FiletIcons.Apk
    HandlerId.MANIFEST -> FiletIcons.Code
    HandlerId.PDF -> FiletIcons.Doc
    HandlerId.DOC -> FiletIcons.Doc
    HandlerId.WEB -> FiletIcons.Eye
    HandlerId.METADATA -> FiletIcons.Info
    HandlerId.EXTERNAL -> FiletIcons.Share
}
