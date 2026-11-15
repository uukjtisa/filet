package dev.niccc2007.filet.remotes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.niccc2007.filet.browser.BarGlyph
import dev.niccc2007.filet.browser.FiletIcons
import dev.niccc2007.filet.browser.FootButton
import dev.niccc2007.filet.browser.FootDivider
import dev.niccc2007.filet.browser.ListNameText
import dev.niccc2007.filet.browser.PILL_MAX_WIDTH
import dev.niccc2007.filet.browser.PillShape
import dev.niccc2007.filet.browser.SelectMark
import dev.niccc2007.filet.ui.dialogs.PanelPopover
import dev.niccc2007.filet.ui.dialogs.PanelRow
import dev.niccc2007.filet.ui.dialogs.PanelRule
import dev.niccc2007.filet.ui.dialogs.PanelSide
import dev.niccc2007.filet.ui.theme.Filet
import dev.niccc2007.filet.vfs.provider.net.NetConnection
import dev.niccc2007.filet.vfs.provider.net.NetProtocol

/**
 * What a saved share's meta line says, piece by piece: what kind of server it is, a warning when
 * it talks in plain text, the last thing known about reaching it, and its address.
 *
 * The state is the strongest fact there is. A test run this session outranks the open stamp,
 * because the stamp is written when the share is opened, not when the open worked, so it can only
 * honestly be called "opened". The address goes last because it is the piece an ellipsis can take.
 */
internal object RemoteMeta {
    enum class Tone { PLAIN, GOOD, BAD, WARN }

    data class Piece(val text: String, val tone: Tone)

    fun kind(p: NetProtocol): String = when (p) {
        NetProtocol.SMB -> "SMB"
        NetProtocol.SFTP -> "SFTP"
        NetProtocol.FTP -> "FTP"
        NetProtocol.WEBDAV -> "WebDAV"
    }

    fun address(c: NetConnection): String = c.host + if (c.share.isNotEmpty()) "/" + c.share else ""

    fun pieces(c: NetConnection, verdict: RemoteProbe.Verdict?, busy: Boolean, now: Long): List<Piece> {
        val plain = !c.useTls && (c.protocol == NetProtocol.FTP || c.protocol == NetProtocol.WEBDAV)
        val ago = agoWords(c.lastUsedAt, now)
        // Nothing, and not "never opened", when nothing is known: a share not used yet is the
        // ordinary state of one just added, and labelling it is a reproach, not information.
        val state = when {
            busy -> Piece("dialling", Tone.WARN)
            verdict != null && verdict.ok -> Piece(verdict.label, Tone.GOOD)
            verdict != null -> Piece(verdict.label, Tone.BAD)
            ago.isNotEmpty() -> Piece("opened $ago", Tone.PLAIN)
            else -> null
        }
        return listOfNotNull(
            Piece(kind(c.protocol), Tone.PLAIN),
            if (plain) Piece("unencrypted", Tone.WARN) else null,
            state,
            Piece(address(c), Tone.PLAIN),
        )
    }
}

/** Between the pieces of a meta line, as the file list spaces its own. */
private const val META_GAP = "  ·  "

/**
 * One saved share, drawn as a folder is in a file list at the Comfortable step: a 34dp tile, the
 * name, and one meta line. The tile is its own target and toggles the selection, as a file's icon
 * does; [menu] is composed inside the row's box so the hold menu opens at the row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RemoteRow(
    c: NetConnection,
    verdict: RemoteProbe.Verdict?,
    busy: Boolean,
    selected: Boolean,
    held: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIconClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    val colors = Filet.colors
    val now = remember(c.lastUsedAt, verdict, busy) { System.currentTimeMillis() }
    val pieces = remember(c, verdict, busy, now) { RemoteMeta.pieces(c, verdict, busy, now) }
    val query = dev.niccc2007.filet.ui.LocalPaneQuery.current
    Column {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        when {
                            selected -> colors.sel
                            held -> colors.high
                            else -> Color.Transparent
                        },
                    )
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .defaultMinSize(minHeight = ROW_MIN_HEIGHT)
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onIconClick)) {
                    Box(
                        Modifier
                            .size(TILE)
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.accent.copy(alpha = TILE_ALPHA)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(iconFor(c.protocol), null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                    SelectMark(selected, Modifier.align(Alignment.BottomEnd), side = 17.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    ListNameText(
                        text = dev.niccc2007.filet.ui.highlighted(c.label.ifEmpty { c.host }, query, colors.accent),
                        color = MaterialTheme.colorScheme.onSurface,
                        startSp = 14f,
                        lines = 1,
                        isDir = true,
                        fontWeight = FontWeight.Medium,
                    )
                    val line = buildAnnotatedString {
                        pieces.forEachIndexed { i, p ->
                            if (i > 0) append(META_GAP)
                            val ink = when (p.tone) {
                                RemoteMeta.Tone.GOOD -> colors.good
                                RemoteMeta.Tone.BAD -> colors.bad
                                RemoteMeta.Tone.WARN -> colors.warn
                                RemoteMeta.Tone.PLAIN -> null
                            }
                            when {
                                ink != null -> withStyle(SpanStyle(color = ink)) { append(p.text) }
                                i == pieces.lastIndex -> append(dev.niccc2007.filet.ui.highlighted(p.text, query, colors.accent))
                                else -> append(p.text)
                            }
                        }
                    }
                    Text(
                        line,
                        fontSize = 11.sp,
                        color = colors.fg3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            menu()
        }
        // The sentence, under the row, and only when there is one: a verdict's value is naming
        // what to change, and that does not fit in the meta line.
        if (verdict != null && verdict.detail.isNotEmpty()) {
            Text(
                verdict.detail,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = colors.fg3,
                modifier = Modifier.padding(start = 54.dp, end = 15.dp, bottom = 7.dp),
            )
        }
    }
}

/**
 * The hold menu: the same panel as the mini player's glow and every viewer's More, opened at the
 * held row. Forget is last and in the warning colour because it is the one that cannot be undone.
 */
@Composable
internal fun RemoteMenu(
    c: NetConnection,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onSelect: () -> Unit,
    onIndex: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = Filet.colors
    PanelPopover(side = PanelSide.BELOW, onDismiss = onDismiss, heading = c.label.ifEmpty { c.host }, gap = 4.dp) {
        PanelRow("Open", colors.accent, null, FiletIcons.Open, onClick = onOpen)
        PanelRow("Test", colors.accent, null, FiletIcons.Wifi, onClick = onTest)
        PanelRow("Edit", colors.accent, null, FiletIcons.Code, onClick = onEdit)
        PanelRow("Duplicate", colors.accent, null, FiletIcons.Copy, onClick = onDuplicate)
        PanelRow("Select", colors.accent, null, FiletIcons.Check, onClick = onSelect)
        PanelRule()
        // Off for every share unless turned on here. It does not promise the share is watched:
        // an index is as fresh as its last pass.
        PanelRow("Keep a search index", colors.accent, c.indexed, onClick = onIndex)
        PanelRule()
        PanelRow("Forget", colors.bad, null, FiletIcons.Delete, ink = colors.bad, onClick = onForget)
    }
}

/** What a one-share action says when more than one is picked. */
internal const val ONE_SHARE = "Pick one share for this"

/**
 * The file list's selection pill, holding this screen's actions: the count, Clear, a divider,
 * the captioned buttons, and All. Built from the same parts as the file list's, so the two cannot
 * drift apart.
 */
@Composable
internal fun RemotesPill(
    count: Int,
    total: Int,
    onClear: () -> Unit,
    onAll: () -> Unit,
    onOpen: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onForget: () -> Unit,
    onBlocked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Filet.colors
    val one = if (count == 1) null else ONE_SHARE
    Column(
        modifier
            .widthIn(max = PILL_MAX_WIDTH)
            .shadow(6.dp, PillShape)
            .clip(PillShape)
            .background(colors.raised)
            .border(1.dp, colors.lineSoft, PillShape),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                count.toString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .semantics { contentDescription = "$count selected" },
            )
            BarGlyph(FiletIcons.Close, "Clear selection", onClear)
            FootDivider()
            Box(Modifier.weight(1f, fill = false)) {
                dev.niccc2007.filet.ui.HScroll(ground = colors.raised, spacing = 6.dp) {
                    FootButton(FiletIcons.Open, "Open", blocked = one, onBlocked = onBlocked, onClick = onOpen)
                    FootButton(FiletIcons.Wifi, "Test", onClick = onTest)
                    FootButton(FiletIcons.Code, "Edit", blocked = one, onBlocked = onBlocked, onClick = onEdit)
                    FootButton(FiletIcons.Copy, "Duplicate", blocked = one, onBlocked = onBlocked, onClick = onDuplicate)
                    FootButton(FiletIcons.Delete, "Forget", tint = colors.bad, onClick = onForget)
                }
            }
            if (count < total) {
                Text(
                    "All",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.fg2,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(onClick = onAll)
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private val TILE = 34.dp
private val ROW_MIN_HEIGHT = 60.dp

/** The tile's wash: the accent over the ground, as the dark palette's accent-dim mixes it. */
private const val TILE_ALPHA = 0.26f
