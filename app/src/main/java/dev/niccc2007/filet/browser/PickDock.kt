package dev.niccc2007.filet.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.pick.PickCard
import dev.niccc2007.filet.pick.PickSentence
import dev.niccc2007.filet.pick.PickSpec
import dev.niccc2007.filet.ui.theme.Filet

/**
 * What a pick is, said as a floating card inside the folder it is asking about.
 *
 * ## Why this is not a band across the top
 *
 * It was, twice. A strip under the toolbar, then a taller block with the path and a button -
 * and both were a LAYER OF CHROME over the browser rather than part of it. That is wrong twice
 * over.
 *
 * It reads wrong: a pick opens a tab that looks exactly like every other tab, so the only thing
 * saying "this is an operation" was a bar sitting with the app's own permanent bars, where the
 * eye has already learned there is nothing to read.
 *
 * And it stacks wrong. Several callers are themselves full-screen - the metadata writer - or
 * sheets, so the real screen was a band, under a sheet, over a browser, and nothing could be
 * reached. Being a floating card INSIDE the pane means it belongs to the listing it describes,
 * the way the dock's paste pill does, and there is no second layer of chrome to get buried
 * under.
 *
 * ## One card for both directions
 *
 * Filet asking itself and another app asking Filet are the same question with a different
 * subject, so they get the same card. The sentence is built once, in [PickSentence], from
 * whichever is live.
 *
 * ## Which pane it appears in
 *
 * A file pick follows the eye; a folder pick stays with its folder. Bug identified here: the
 * card was pinned to the pane the pick STARTED in for both, so walking off to the Photos tab to
 * find a picture left the question behind - there was nothing on screen saying a pick was
 * running, nothing to confirm it with, and nothing to cancel it with either.
 *
 * The asymmetry is in the answers, not in the card. A file pick's answer is a set of files,
 * which can be added to from any listing in the app, so the card belongs wherever the listing
 * is. A folder pick's answer IS a pane's location, so the card has to be in the pane whose
 * location it is about - put it anywhere else and it would be describing one folder while
 * confirming another.
 */
@Composable
fun BoxScope.PickDock(vm: BrowserViewModel, pane: PaneController, onHeight: (Int) -> Unit = {}) {
    val live by vm.inAppPick.collectAsState()
    val preview by vm.pickPreview.collectAsState()
    val busy by vm.pickPreviewBusy.collectAsState()
    val picked by vm.pickChosen.collectAsState()
    val minimised by vm.pickMinimised.collectAsState()
    val app by vm.state.collectAsState()
    val tabs by vm.tabs.collectAsState()
    val external = vm.pickRequest

    val want = live?.spec?.want
        ?: if (external?.allowMultiple == true) PickSpec.Want.FILES else PickSpec.Want.FILE
    // The pick's own tab, while it still exists. Closing it does not end the pick, so a folder
    // pick with no anchor left follows the eye instead - see `PickCard.shownIn`, which holds
    // the rule and the reasoning.
    val anchor = live?.pane?.takeIf { it in tabs }
    val mine = PickCard.shownIn(
        want = want,
        anchor = anchor,
        focused = vm.paneFor(app.focused),
        pane = pane,
        inApp = live != null,
        external = external != null,
    )
    if (!mine) {
        // Report zero so the dock drops back when the pick ends, rather than staying lifted
        // for the life of the pane.
        androidx.compose.runtime.DisposableEffect(Unit) { onHeight(0); onDispose { } }
        return
    }
    val s by pane.state.collectAsState()

    val folder = if (want.isPlace) {
        live?.spec?.folderOf(s.cwd, s.active) ?: s.target
    } else {
        null
    }
    // The PICK's list, not this pane's selection. They were the same thing until a pick had to
    // be answerable from a surface that has no selection - see `BrowserViewModel.pickChosen`.
    val chosen = picked.size
    val sentence = PickSentence.of(
        want = want,
        // An external request can carry its own words now, and one of those requests comes from
        // Filet itself: the report form asks its own picker for attachments rather than handing
        // the job to the system one. Without this the card said "Choose files to submit - Filet",
        // naming the app as the stranger that asked - which is both wrong and faintly absurd.
        //
        // `PickActivity` only reads those words when the calling package is this one, so an
        // outside app cannot put its own sentence in Filet's mouth.
        subject = live?.spec?.subject ?: external?.subject.orEmpty(),
        verb = live?.spec?.verb ?: external?.verb.orEmpty(),
        // The asker is named only when nothing else said what the pick is for. A request that
        // brought a verb has already described itself, and appending "- Filet" to Filet's own
        // sentence would be the same fault in a different place.
        asker = if (live == null && external?.verb.isNullOrBlank()) vm.pickCaller else null,
        whatQualifies = live?.spec?.accept?.what ?: external?.what.orEmpty(),
    )
    // A save is answered by a place AND a name, and both can be wrong before anything is
    // written: a folder that cannot be written to, a name that cannot be a file's, a name that
    // is already there. Each is said on the card as it happens rather than after a confirm.
    val liveSpec = live?.spec
    val save = if (want == PickSpec.Want.SAVE && liveSpec != null) {
        val typed by vm.pickName.collectAsState()
        SaveState.of(vm, liveSpec, folder, typed)
    } else {
        null
    }
    val ready = when {
        save != null -> save.ready
        want.isPlace -> folder != null
        else -> chosen > 0
    }
    val label = when {
        busy -> "Looking…"
        preview?.blocked != null -> "Cannot"
        save?.replaces == true -> "Replace"
        else -> sentence.confirm
    }

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            // Measured and reported, so the dock lifts by exactly this and no magic number has
            // to be kept in step with the card's padding.
            .onSizeChanged { onHeight(it.height) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (minimised) {
            // Rolled up, the way the player minimises into its strip: out of the way of the
            // listing, and still carrying the two things that must never need the card back -
            // the count, so there is proof the taps are landing, and the confirm.
            //
            // The card covers the bottom of the listing, which is where the file being looked
            // for usually is in a folder sorted newest-first. Walking somewhere else is not an
            // answer to that: in the gallery the card sits over a row of thumbnails whatever
            // folder it is.
            PickPill(
                count = chosen,
                folder = folder?.name,
                label = label,
                enabled = !busy && preview?.blocked == null && ready,
                onExpand = { vm.setPickMinimised(false) },
                onCancel = { vm.cancelPicking() },
                onConfirm = { vm.confirmPicking() },
            )
            return@Column
        }
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Filet.colors.high)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (want) {
                        PickSpec.Want.FOLDER -> FiletIcons.FolderOpen
                        PickSpec.Want.SAVE -> FiletIcons.Save
                        PickSpec.Want.FILE, PickSpec.Want.FILES -> FiletIcons.File
                    },
                    null,
                    tint = Filet.colors.accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        // The preview's own title once there is one. It is live now, so this
                        // is the ordinary state rather than a second phase.
                        preview?.title ?: sentence.headline,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            preview != null -> preview!!.blocked ?: preview!!.detail
                            // The whole path, because a folder pick is a question about a
                            // PLACE and two folders called "Audio" are told apart by nothing
                            // else. The name alone is also blank at a volume root.
                            folder != null -> folder.path
                            chosen > 0 -> "$chosen chosen"
                            else -> sentence.hint
                        },
                        fontSize = 10.sp,
                        fontFamily = if (preview == null && folder != null) FontFamily.Monospace else FontFamily.Default,
                        color = if (preview?.blocked != null) Filet.colors.warn else Filet.colors.fg3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                // Minimise before cancel, and in that order: these are the two ways out of the
                // card and the harmless one goes first, so the thumb reaching for "get this out
                // of my way" does not land on the one that throws the pick away.
                Icon(
                    FiletIcons.Down,
                    "Minimise",
                    tint = Filet.colors.fg2,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { vm.setPickMinimised(true) }
                        .padding(7.dp),
                )
                Icon(
                    FiletIcons.Close,
                    "Cancel",
                    tint = Filet.colors.fg2,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { vm.cancelPicking() }
                        .padding(8.dp),
                )
            }
            if (save != null) {
                Spacer(Modifier.height(8.dp))
                SaveNameField(vm, onDone = { if (ready && !busy) vm.confirmPicking() })
                save.note?.let { (text, warn) ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text,
                        fontSize = 10.sp,
                        color = if (warn) Filet.colors.warn else Filet.colors.fg3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ONE button, because there is one decision.
                //
                // There were two, and a "Choose elsewhere" beside them, because the preview
                // used to be something the first press went and fetched - so the first press
                // meant "show me" and the second meant "do it". The ghosts are live now and
                // follow the folder, so the answer is already on screen before anything is
                // pressed: "choose elsewhere" is just walking somewhere else, and needs no
                // button to say so.
                PickDockButton(
                    label = label,
                    primary = true,
                    enabled = !busy && preview?.blocked == null && ready,
                    modifier = Modifier.weight(1f),
                ) { vm.confirmPicking() }
            }
        }
    }
}

/**
 * What a save pick's card has to say about the name and the folder, worked out as they change.
 *
 * Whether the name is taken is ASKED of the folder rather than read off the pane's rows: in the
 * tree the folder being stood in is an open branch, not the listing, and a clash check against
 * the wrong folder's rows would say "Replace" over a file that is not there, or say nothing
 * over one that is.
 */
private class SaveState(
    val ready: Boolean,
    val replaces: Boolean,
    /** A line under the field and whether it is a warning. */
    val note: Pair<String, Boolean>?,
) {
    companion object {
        @Composable
        fun of(
            vm: BrowserViewModel,
            spec: PickSpec,
            folder: dev.niccc2007.filet.vfs.VPath?,
            typed: String,
        ): SaveState {
            val problem = dev.niccc2007.filet.pick.PickName.problem(typed)
            val name = dev.niccc2007.filet.pick.PickName.fitted(typed, spec.saveExtension)
            val blocked = androidx.compose.runtime.remember(folder) { vm.writeBlockReason(folder) }
            val taken by androidx.compose.runtime.produceState(false, folder, name, problem) {
                value = false
                if (folder == null || problem != null) return@produceState
                // A breath, so a name being typed is not looked up once per letter.
                kotlinx.coroutines.delay(150)
                value = vm.exists(folder.child(name))
            }
            val note = when {
                blocked != null -> blocked to true
                problem != null -> problem to (typed.isNotEmpty())
                taken -> "$name is here already. Saving replaces it." to true
                name != typed.trim() -> "Saved as $name" to false
                else -> null
            }
            return SaveState(
                ready = folder != null && blocked == null && problem == null,
                replaces = taken && blocked == null && problem == null,
                note = note,
            )
        }
    }
}

/**
 * The name a save will write, editable on the card.
 *
 * Its stem is selected on arrival, as the rename dialogue does, so typing replaces the name and
 * keeps the extension. Not focused on arrival: the first thing a save asks is WHERE, and a
 * keyboard covering the folders would be in the way of answering it.
 */
@Composable
private fun SaveNameField(vm: BrowserViewModel, onDone: () -> Unit) {
    val typed by vm.pickName.collectAsState()
    var field by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                typed,
                androidx.compose.ui.text.TextRange(0, stemLength(typed)),
            ),
        )
    }
    // A file tapped in the folder hands its name over; the field follows, stem selected again.
    androidx.compose.runtime.LaunchedEffect(typed) {
        if (typed != field.text) {
            field = androidx.compose.ui.text.input.TextFieldValue(
                typed,
                androidx.compose.ui.text.TextRange(0, stemLength(typed)),
            )
        }
    }
    androidx.compose.foundation.text.BasicTextField(
        value = field,
        onValueChange = {
            field = it
            vm.setPickName(it.text)
        },
        singleLine = true,
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Filet.colors.accent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(Filet.colors.sunken)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    )
}

/** Where a name's extension starts, so selecting up to it keeps the extension. */
private fun stemLength(name: String): Int {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) dot else name.length
}

/**
 * The pick, rolled up to one row.
 *
 * Wrap-width rather than full width, so most of the bottom of the listing comes back - which is
 * the entire point of minimising and would be lost by a card that only got shorter.
 *
 * It still confirms. A minimised thing that has to be opened again before it can be used is a
 * fold, not a minimise, and it would make the gesture cost two taps to undo.
 */
@Composable
private fun PickPill(
    count: Int,
    folder: String?,
    label: String,
    enabled: Boolean,
    onExpand: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = Filet.colors
    Row(
        Modifier
            .shadow(8.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(colors.high)
            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FiletIcons.Up,
            "Expand",
            tint = colors.fg2,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onExpand)
                .padding(7.dp),
        )
        Text(
            // The count is the proof that the taps are landing, which is the one thing the
            // minimised form cannot afford to drop.
            folder ?: if (count == 1) "1 chosen" else "$count chosen",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.width(4.dp))
        PickDockButton(
            label = label,
            primary = true,
            enabled = enabled,
            onClick = onConfirm,
        )
        Icon(
            FiletIcons.Close,
            "Cancel",
            tint = colors.fg2,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel)
                .padding(8.dp),
        )
    }
}

@Composable
private fun PickDockButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Filet.colors
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !primary -> colors.sunken
                    enabled -> colors.accent
                    // Refused and SAID so rather than hidden: a control that disappears leaves
                    // you wondering whether the app understood the tap.
                    else -> colors.fg3.copy(alpha = 0.35f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            // Horizontal as well as vertical, so the same button works at its natural width in
            // the minimised pill and inset inside the full card.
            .padding(vertical = 9.dp, horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = when {
                !primary -> colors.fg2
                enabled -> MaterialTheme.colorScheme.onPrimary
                else -> colors.fg3
            },
        )
    }
}

/**
 * How much of the bottom of a pane the pick card is covering.
 *
 * The card floats over the listing rather than pushing it up, which is what keeps the folder
 * visible while choosing a destination - and it meant the rows underneath it could not be
 * tapped at all. Published here so the listing can add it to its bottom padding and the dock
 * can lift clear of it, both from ONE measurement rather than each estimating the other.
 *
 * Zero whenever no pick is running, so nothing pays for this the rest of the time.
 */
val LocalPickInset = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

private val NOTHING_CHOSEN: androidx.compose.runtime.State<Set<dev.niccc2007.filet.vfs.VPath>> =
    androidx.compose.runtime.mutableStateOf(emptySet())

/**
 * What the live pick has chosen, for any surface that draws a file.
 *
 * Bug identified here: the pick's chosen set used to BE a pane's selection, so the pane's row
 * highlight came for free and nothing else had one. Moving the set onto the pick fixed which
 * files the confirm could see and took the highlight away from the rows - so a tap in the
 * Photos tab registered and looked exactly like a tap that had not.
 *
 * A [State] rather than the set itself, so providing this costs nothing: the object's identity
 * never changes, so no subtree is recomposed when the selection moves, and each reader
 * re-evaluates only its own answer through [isChosenForPick].
 *
 * Provided once, around the whole browser, because the surfaces that can show a pickable file
 * are not a list anybody can keep: the folder listing, search results, Home, a category, the
 * gallery, the history screen and a dialogue's own file list.
 */
val LocalPickChosen = androidx.compose.runtime.staticCompositionLocalOf { NOTHING_CHOSEN }

/**
 * Whether this exact file is chosen for the live pick.
 *
 * `derivedStateOf` per caller for the same reason `rowSelected` uses it: the set changes on
 * every tap and each row's answer changes only for the row that was tapped, so one tap
 * recomposes one row rather than a screenful.
 */
/**
 * The ground a row stands on while the live pick holds its file.
 *
 * Bug identified here: the only mark was a tick on the thumbnail, and on a 30dp avatar holding
 * a photograph of a white receipt a small badge is not a highlight - the tap registered, the
 * count went up, and the row looked untouched. The pane's own listing had never had this
 * problem because a selected row there is tinted end to end, so the fix is to say the same
 * thing the same way on the rows that are not in a pane.
 *
 * The badge stays. It is what works on a gallery tile, where a tint behind a full-bleed
 * thumbnail is not visible at all.
 */
@Composable
fun Modifier.chosenForPick(path: dev.niccc2007.filet.vfs.VPath?): Modifier =
    if (isChosenForPick(path)) background(Filet.colors.sel) else this

@Composable
fun isChosenForPick(path: dev.niccc2007.filet.vfs.VPath?): Boolean {
    val chosen = LocalPickChosen.current
    val mine by androidx.compose.runtime.remember(path, chosen) {
        androidx.compose.runtime.derivedStateOf { path != null && path in chosen.value }
    }
    return mine
}
