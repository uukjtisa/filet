package dev.niccc2007.filet.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.niccc2007.filet.FiletApp
import dev.niccc2007.filet.icons.FolderLook
import dev.niccc2007.filet.icons.FolderStyles
import dev.niccc2007.filet.icons.Inside
import dev.niccc2007.filet.icons.Thing
import dev.niccc2007.filet.icons.drawFolder
import dev.niccc2007.filet.icons.folderPicturePx
import dev.niccc2007.filet.icons.lookFor
import dev.niccc2007.filet.media.Thumbnails
import dev.niccc2007.filet.vfs.VNode
import dev.niccc2007.filet.vfs.VPath
import dev.niccc2007.filet.vfs.types.FileTypes
import kotlinx.coroutines.delay

/**
 * A folder, drawn as a thing with things in it.
 *
 * The drawing is the approved folder art (see `icons/FolderArt.kt`); this decides what goes in
 * it. Three questions, each answered from something already cheap to ask:
 *
 *  1. **How it is dressed.** The look chosen for it with Change icon, else the standard folder's
 *     (Download, Music and the rest, at the top of a drive only), else plain. See [lookFor].
 *  2. **Whether anything is in it.** An empty folder is drawn empty. That is the one thing the
 *     icon says at every size, so the folder is read even where nothing inside will be drawn.
 *  3. **What stands in it.** Up to three things, picked by [pickPeek]: pictures as themselves,
 *     files as their type, folders as folders. While that is being read, and below the size
 *     where three things can be told apart, the folder holds drawn pages instead.
 *
 * A picture that turns out to have no preview (a song with no cover art, say) stands in as its
 * file type rather than as a blank card.
 */
@Composable
fun FolderIcon(
    node: VNode,
    size: Dp,
    modifier: Modifier = Modifier,
    /** Off where thumbnails are off: what is inside is then drawn as pages, never as pictures. */
    previews: Boolean = true,
    /** Drawn as this instead of its own look: the Change icon sheet's preview. */
    look: FolderLook? = null,
) {
    // A folder that exists only to be shown - the Row details preview's sample folders - says
    // what it holds through [LocalSampleInside] and skips the peek, the cover and the thumbnail
    // cache entirely: none of them can answer for a path that is not on any drive, and asking
    // would put a failed lookup per sample row on the settings screen.
    LocalSampleInside.current?.invoke(node)?.let { sample ->
        val inside = when {
            sample == Inside.Empty -> Inside.Empty
            previews && size >= PREVIEW_FROM -> sample
            else -> Inside.Pages
        }
        val drawn = look ?: FolderLook.PLAIN
        Canvas(modifier.size(size)) { drawFolder(inside, drawn) }
        return
    }
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val graph = remember(context) { if (inspecting) null else runCatching { FiletApp.graphOf(context) }.getOrNull() }
    val styles = remember(graph) { graph?.let { runCatching { FolderStyles.of(context) }.getOrNull() } }
    val chosen by (styles?.byPath ?: NO_STYLES).collectAsState()
    val shown = look ?: lookFor(chosen, node.path)

    val peek = graph?.folderPeek
    val found = if (peek == null) null else rememberFolderPeek(peek, node.path, node.mtime, true).value
    val detailed = previews && size >= PREVIEW_FROM && found != null && found.items.isNotEmpty()

    val px = with(LocalDensity.current) { folderPicturePx(size.toPx()) }
    val media = if (detailed) found!!.items.filter { it.kind == PeekKind.MEDIA }.map { it.node } else emptyList()
    val pictures = rememberPictures(media, px, enabled = graph != null)
    val cover = rememberCover(node.path, shown, px, enabled = previews && graph != null && size >= PREVIEW_FROM)

    val inside: Inside = when {
        found?.empty == true -> Inside.Empty
        cover != null -> Inside.Things(listOf(Thing.Picture(cover)), lone = false)
        !detailed -> Inside.Pages
        else -> Inside.Things(
            found!!.items.map { item ->
                when (item.kind) {
                    PeekKind.MEDIA -> when (val p = pictures[item.node.path]) {
                        Pic.Failed -> Thing.File(FileTypes.icon(item.node.extension))
                        is Pic.Got -> Thing.Picture(p.image, video = FileKind.of(item.node) == FileKind.VIDEO)
                        else -> Thing.Picture(null, video = FileKind.of(item.node) == FileKind.VIDEO)
                    }
                    PeekKind.FILE -> Thing.File(FileTypes.icon(item.node.extension))
                    PeekKind.FOLDER -> Thing.Folder(item.full ?: true)
                }
            },
        )
    }

    Canvas(modifier.size(size)) { drawFolder(inside, shown) }
}

/**
 * What a folder holds when it is a sample rather than a place on a drive, or null for a real one.
 *
 * The Row details preview draws its rows with the list's own row, so its sample folders arrive
 * here like any other folder. Without this they would be peeked, find nothing at a path that does
 * not exist, and draw empty - a preview of a list where every folder is empty, which is not the
 * list anyone has. The size gate and the previews switch still apply, so a sample folder in the
 * compact step draws pages exactly as a real one does there.
 */
val LocalSampleInside = androidx.compose.runtime.staticCompositionLocalOf<((VNode) -> Inside?)?> { null }

/** A picture standing in a folder: not looked at yet, decoded, or with no preview to give. */
private sealed interface Pic {
    data object Loading : Pic
    data object Failed : Pic
    class Got(val image: ImageBitmap) : Pic
}

private fun Thumbnails.Answer?.toPic(): Pic = when {
    this == null -> Pic.Loading
    bitmap == null -> Pic.Failed
    else -> Pic.Got(bitmap.asImageBitmap())
}

/**
 * The pictures for [media], from the thumbnail cache where they are already there and decoded
 * once the row has stayed on screen where they are not: the same settle the file rows use, so a
 * fling through a list of folders decodes nothing it passes.
 */
@Composable
private fun rememberPictures(media: List<VNode>, px: Int, enabled: Boolean): Map<VPath, Pic> {
    val context = LocalContext.current
    val key = media.map { "${it.path}|${it.size}|${it.mtime}" }
    val start = remember(key, px) { media.associate { it.path to Thumbnails.peek(it.path, it.size, it.mtime, px).toPic() } }
    return produceState(start, key, px, enabled) {
        value = start
        if (!enabled) return@produceState
        val todo = media.filter { start[it.path] == Pic.Loading }
        if (todo.isEmpty()) return@produceState
        delay(SETTLE_MS)
        val graph = runCatching { FiletApp.graphOf(context) }.getOrNull() ?: return@produceState
        for (n in todo) {
            val bitmap = runCatching { Thumbnails.load(context, graph.vfs, n.path, n.size, n.mtime, px) }.getOrNull()
            value = value + (n.path to if (bitmap == null) Pic.Failed else Pic.Got(bitmap.asImageBitmap()))
        }
    }.value
}

/** The picture chosen to stand in front, when one was and it can still be found. */
@Composable
private fun rememberCover(folder: VPath, look: FolderLook, px: Int, enabled: Boolean): ImageBitmap? {
    val name = look.cover?.takeIf { enabled } ?: return null
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, folder, name, px) {
        delay(SETTLE_MS)
        val graph = runCatching { FiletApp.graphOf(context) }.getOrNull() ?: return@produceState
        val child = folder.child(name)
        val node = runCatching { graph.vfs.stat(child) }.getOrNull() ?: return@produceState
        value = runCatching { Thumbnails.load(context, graph.vfs, child, node.size, node.mtime, px) }.getOrNull()?.asImageBitmap()
    }.value
}

private val NO_STYLES = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, FolderLook>())

/** As the file rows: long enough that a fling pays for nothing, short enough not to be seen. */
private const val SETTLE_MS = 90L

/**
 * Below this a picture inside is a few pixels and says nothing; drawn pages are better.
 *
 * Twenty-two. The list steps give the icon slot 18 to 34dp - the file thumbnails beside it
 * live in that same slot - so a floor above that turns previews off in views people actually
 * use, and a folder of three hundred photographs would show none. It was eighteen, which let
 * them through at the compact step where three pictures inside a folder are nine pixels each
 * and read as dirt on the glyph.
 */
private val PREVIEW_FROM = 22.dp
