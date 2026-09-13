package dev.niccc2007.filet.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Filet's glyph set, built from the SVG path data in the approved UI mock.
 *
 * Declared here rather than pulled from `material-icons-extended`, which adds several
 * thousand vectors and a few MB to the APK to use about forty of them. It also keeps the
 * mock authoritative: each `d` string below is the one the mock renders, so the app and the
 * design reference cannot drift.
 *
 * Two builders because the mock uses two idioms: most icons are 1.7px strokes on a 24-unit
 * grid, a few are solid fills.
 */
private fun stroke(name: String, vararg d: String, width: Float = 1.7f): ImageVector {
    val b = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    for (spec in d) {
        b.addPath(
            pathData = PathParser().parsePathString(spec).toNodes(),
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

private fun filled(name: String, vararg d: String): ImageVector {
    val b = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    for (spec in d) {
        b.addPath(pathData = PathParser().parsePathString(spec).toNodes(), fill = SolidColor(Color.White))
    }
    return b.build()
}

/**
 * The launcher icon, as an in-app vector.
 *
 * Two tones, drawn on the launcher's own 108-unit grid with the same two path strings as
 * `res/drawable/ic_launcher_foreground.xml` - so the mark in the sidebar is the mark on the
 * home screen, and they cannot drift apart.
 *
 * @param body the darker folder body; @param flap the accent front flap. Passed in rather
 *   than hard-coded so the icon takes the active palette instead of staying Slate-blue when
 *   the app is Ember.
 */
fun appIcon(body: Color, flap: Color): ImageVector {
    val b = ImageVector.Builder(
        name = "filet", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 108f, viewportHeight = 108f,
    )
    b.addPath(
        pathData = PathParser()
            .parsePathString("M26,24h16l6,8h30a6,6 0 0 1 6,6v8H16V30a6,6 0 0 1 6,-6z")
            .toNodes(),
        fill = SolidColor(body),
    )
    b.addPath(
        pathData = PathParser()
            .parsePathString(
                "M24,53h64a4,4 0 0 1 3.9,4.9l-5.6,24A6,6 0 0 1 80.4,86H21a4,4 0 0 1 " +
                    "-3.9,-4.9l5.6,-24A6,6 0 0 1 24,53z"
            )
            .toNodes(),
        fill = SolidColor(flap),
    )
    return b.build()
}

object FiletIcons {

    // ── file types ──
    val Folder = stroke("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val FolderOpen = stroke(
        "folderopen",
        "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v1",
        "M3 9h18l-2.2 8.4a2 2 0 0 1-1.9 1.6H5.1a2 2 0 0 1-1.9-1.6z",
    )
    val File = stroke("file", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")
    val Apk = stroke("apk", "M4 4h16a3 3 0 0 1 0 0v16H4z", "M9 15V9l3 4 3-4v6")
    val Zip = stroke(
        "zip",
        "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z",
        "M11 6h2M11 9h2M11 12h2M11 15h2",
    )
    val Audio = stroke("audio", "M9 18V6l10-2v12", "M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0", "M19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0")
    val Image = stroke("image", "M3 4h18v16H3z", "M10 9.5a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0", "m4 17 5-5 4 4 3-3 4 4")
    val Video = stroke("video", "M3 5h18v14H3z", "m10 9 5 3-5 3z")
    val Code = stroke("code", "m9 8-4 4 4 4M15 8l4 4-4 4")
    val Dex = stroke("dex", "M12 3 4 7v10l8 4 8-4V7z", "M4 7l8 4 8-4M12 21V11")
    val Doc = stroke("doc", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M8.5 13h7M8.5 16.5h5")

    // ── chrome ──
    val Search = stroke("search", "M18 11a7 7 0 1 1-14 0 7 7 0 0 1 14 0", "m20 20-3.5-3.5", width = 1.9f)
    val Menu = stroke("menu", "M4 7h16M4 12h16M4 17h16", width = 1.9f)
    val More = filled(
        "more",
        "M13.7 5a1.7 1.7 0 1 1-3.4 0 1.7 1.7 0 0 1 3.4 0",
        "M13.7 12a1.7 1.7 0 1 1-3.4 0 1.7 1.7 0 0 1 3.4 0",
        "M13.7 19a1.7 1.7 0 1 1-3.4 0 1.7 1.7 0 0 1 3.4 0",
    )
    val SplitH = stroke("panes", "M3 5h18v14H3z", "M12 5v14")
    val SplitV = stroke("panesv", "M3 5h18v14H3z", "M3 12h18")
    val Storage = stroke("storage", "M3 4h18v7H3z", "M3 13h18v7H3z", "M7 7.5h.01M7 16.5h.01")
    val Sd = stroke("sd", "M6 3h9l4 4v14H6z", "M10 3v4M13 3v4M16 4v3")
    val Clock = stroke("clock", "M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0", "M12 7.5V12l3 2")
    val Star = stroke("star", "m12 4 2.4 5 5.6.8-4 3.9 1 5.5-5-2.7-5 2.7 1-5.5-4-3.9 5.6-.8z")
    val StarFilled = filled("starfilled", "m12 4 2.4 5 5.6.8-4 3.9 1 5.5-5-2.7-5 2.7 1-5.5-4-3.9 5.6-.8z")
    val Jobs = stroke("jobs", "M4 18V9M9.5 18V5M15 18v-6M20.5 18v-9")
    val Script = stroke("script", "M6 4h9l4 4v12H6z", "m10 12 2 2-2 2M13.5 16h2.5")
    val Cog = stroke("cog", "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0", "M12 3v2.5M12 18.5V21M4.2 7.5l2.2 1.3M17.6 15.2l2.2 1.3M4.2 16.5l2.2-1.3M17.6 8.8l2.2-1.3")
    val Pin = stroke("pin", "M9 4h6l-1 6 3 3H7l3-3z", "M12 13v7")
    val Copy = stroke("copy", "M9 9h11v11H9z", "M5 15V6a2 2 0 0 1 2-2h8")
    /** "Hand this to something else" — an arrow leaving a box. */
    val Open = stroke("openwith", "M14 4h6v6", "M20 4 11 13", "M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4")
    val Cut = stroke("move", "M12 4v16M4 12h16M12 4 9 7M12 4l3 3M12 20l-3-3M12 20l3-3M4 12l3-3M4 12l3 3M20 12l-3-3M20 12l-3 3")
    val Delete = stroke("trash", "M5 7h14M10 7V5h4v2M6.5 7l1 13h9l1-13")
    val Download = stroke("dl", "M12 4v11M8 11l4 4 4-4M5 20h14")
    val Rename = stroke("edit", "M4 20h4l10-10-4-4L4 16z", "m14 6 4 4")
    val Check = stroke("check", "m5 12.5 4.5 4.5L19 7", width = 2.2f)
    val Home = stroke("home", "m4 11 8-7 8 7v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z")
    val Grid = stroke("grid", "M4 4h6.5v6.5H4z", "M13.5 4H20v6.5h-6.5z", "M4 13.5h6.5V20H4z", "M13.5 13.5H20V20h-6.5z", width = 1.8f)
    val Rows = stroke("rows", "M4 7h16M4 12h16M4 17h16", width = 1.9f)
    val Info = stroke("info", "M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0", "M12 11v5.5M12 7.8v.01")
    val Mark = stroke("mark", "M6 18V6l12 12V6", "M4.5 20.4c5-1.6 11-2.2 15 .2", width = 1.6f)
    val Play = stroke("play", "m7 4 13 8-13 8z")
    val Up = stroke("up", "M12 20V5", "m5.5 11.5 6.5-6.5 6.5 6.5", width = 1.9f)
    val Back = stroke("back", "M20 12H4", "m10.5 5.5-6.5 6.5 6.5 6.5", width = 1.9f)

    /** Back's mirror. Drawn rather than rotated so the two read as a matched pair. */
    val Forward = stroke("forward", "M4 12h16", "m13.5 5.5 6.5 6.5-6.5 6.5", width = 1.9f)
    val Close = stroke("close", "M6 6l12 12M18 6 6 18", width = 1.9f)
    val Plus = stroke("plus", "M12 5v14M5 12h14", width = 1.9f)
    val NewFolder = stroke("newfolder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M12 10v6M9 13h6")
    val Paste = stroke("paste", "M8 4h8v3H8z", "M8 5.5H6a2 2 0 0 0-2 2V19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5a2 2 0 0 0-2-2h-2")
    val Refresh = stroke("refresh", "M20 12a8 8 0 1 1-2.6-5.9", "M20 4v4h-4")
    val Sort = stroke("sort", "M7 4v16M7 20l-3-3M7 20l3-3", "M17 20V4M17 4l-3 3M17 4l3 3")
    val Share = stroke("share", "M12 15V4", "m8 8 4-4 4 4", "M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5")
    val Eye = stroke("eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12", "M14.5 12a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0")
    val Hex = stroke("hex", "M5 5h14v14H5z", "M8 9h2M8 12h2M8 15h2M13 9h3M13 12h3M13 15h3")
    val Device = stroke("device", "M7 3h10v18H7z", "M10.5 18.5h3")
    val Wifi = stroke("wifi", "M3.5 9.5a13 13 0 0 1 17 0", "M6.5 13a9 9 0 0 1 11 0", "M9.5 16.4a5 5 0 0 1 5 0", "M12 20h.01")
    val Link = stroke("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 1 0-5.7-5.7L11.6 6.8", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 1 0 5.7 5.7l1.4-1.4")
    val Lock = stroke("lock", "M5 11h14v9H5z", "M8 11V8a4 4 0 0 1 8 0v3")
    val Key = stroke("key", "M14.5 12a4.5 4.5 0 1 1-9 0 4.5 4.5 0 0 1 9 0", "M13.5 10.5H21v3M18 10.5v3")
    val Sign = stroke("sign", "M12 3 4 6v6c0 4.4 3.4 7.9 8 9 4.6-1.1 8-4.6 8-9V6z", "m9 12 2 2 4-4")
    val Terminal = stroke("terminal", "M4 5h16v14H4z", "m8 10 2 2-2 2M12.5 14h3.5")
    val Archive = stroke("archive", "M4 6h16v4H4z", "M5.5 10h13v9h-13z", "M10 13.5h4")
    val Trawl = stroke("trawl", "M4 12s3-5 8-5 8 5 8 5-3 5-8 5-8-5-8-5", "M15 12h.01", "m20 8 1.5 4-1.5 4")

    // -- transport --
    // Solid, unlike the outlined Play above: these sit inside a filled button where an
    // outline reads as a hole, and a transport row is the one place a phone user expects
    // the same shapes every other player uses.
    val PlaySolid = filled("playsolid", "M7.5 4.4v15.2L20 12z")
    val Pause = filled("pause", "M7.5 5h3.2v14H7.5z", "M13.3 5h3.2v14h-3.2z")
    val Prev = filled("prev", "M6 5h2.4v14H6z", "M20 5v14l-10-7z")
    val Next = filled("next", "M15.6 5H18v14h-2.4z", "M4 5v14l10-7z")
    val Shuffle = stroke("shuffle", "M16 4h4v4", "M4 20 20 4", "M16 20h4v-4", "m4 4 6 6", "m15 15 5 5")
    val Repeat = stroke("repeat", "M4 11V9a3 3 0 0 1 3-3h10", "m14 3 3 3-3 3", "M20 13v2a3 3 0 0 1-3 3H7", "m10 21-3-3 3-3")
    val Expand = stroke("expand", "M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5")

    // -- image editing --
    val Crop = stroke("crop", "M6 2v16h16", "M2 6h16v16")
    val Draw = stroke("draw", "m3 21 1.3-4.3L14.6 6.4l3 3L7.3 19.7z", "m13 8 3 3", "M16.8 4.2 19.8 7.2l-2.2 2.2-3-3z")
    val RotateRight = stroke("rotr", "M4 12a8 8 0 1 0 2.6-5.9", "M4 4v4h4")
    val RotateLeft = stroke("rotl", "M20 12a8 8 0 1 1-2.6-5.9", "M20 4v4h-4")
    val FlipH = stroke("fliph", "M12 3v18", "M9 7 4 12l5 5z", "m15 7 5 5-5 5z")
    val FlipV = stroke("flipv", "M3 12h18", "M7 9 12 4l5 5z", "m7 15 5 5 5-5z")
    val Invert = stroke("invert", "M20.5 12a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0", "M12 3.5v17", "M15 7h3.2M14 10h5.2M14 14h5.2M15 17h3.2")
    val Grey = stroke("grey", "M4 6h16v12H4z", "M9 6v12M14 6v12")
    val Stretch = stroke("stretch", "M4 9V4h5", "M20 15v5h-5", "m4 4 6 6", "m20 20-6-6")
    val Save = stroke("save", "M5 4h11l3 3v13H5z", "M8.5 4v5h6.5V4", "M8.5 13h7v7h-7z")
    val Undo = stroke("undo", "M4.5 10h10a5 5 0 0 1 0 10H8.5", "m4.5 10 4-4M4.5 10l4 4")
}
