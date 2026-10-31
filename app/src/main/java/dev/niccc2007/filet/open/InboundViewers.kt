package dev.niccc2007.filet.open

import dev.niccc2007.filet.handlers.HandlerId

/**
 * Filet's viewers, offered to other apps by name.
 *
 * ## What this is for
 *
 * Every Filet viewer used to be reachable from outside under one name, "Filet", and only by a
 * catch-all: a video shared from a gallery offered "Filet" next to the real players, and
 * choosing it opened whatever the routing table said, which for a user who had set mp4 to VLC
 * was VLC again. A phone's chooser lists ACTIVITIES, so a viewer that is not an activity of its
 * own cannot be picked, cannot be named, and cannot be made the default for its type.
 *
 * So each viewer is an `<activity-alias>` of MainActivity with its own label and its own type
 * filters - "Filet Video Player" for video, "Filet PDF Reader" for PDFs - and the alias that
 * was chosen decides the viewer. One activity underneath, so there is still one way a file
 * from outside is staged, stat-ed and opened (`BrowserViewModel.openIncoming`); the alias only
 * adds which viewer.
 *
 * ## One table
 *
 * The manifest's aliases and this enum describe the same set, and `tools/check-viewers.mjs`
 * fails the build when they disagree: an alias with no entry here would open the routing
 * table's choice under a name that promised a specific viewer, and an entry with no alias is a
 * viewer nobody outside can reach.
 *
 * @param alias the alias's class name relative to the package, as the manifest writes it.
 * @param label what the chooser shows. "Filet" first, so they sort together and read as one app.
 */
enum class InboundViewer(val alias: String, val label: String, val handler: HandlerId) {
    VIDEO(".open.VideoPlayer", "Filet Video Player", HandlerId.MEDIA),
    MUSIC(".open.MusicPlayer", "Filet Music Player", HandlerId.MEDIA),
    IMAGE(".open.ImageViewer", "Filet Image Viewer", HandlerId.IMAGE),
    PDF(".open.PdfReader", "Filet PDF Reader", HandlerId.PDF),
    DOCUMENT(".open.DocumentReader", "Filet Document Reader", HandlerId.DOC),
    WEB(".open.WebPageViewer", "Filet Web Page Viewer", HandlerId.WEB),
    ARCHIVE(".open.ArchiveViewer", "Filet Archive Viewer", HandlerId.ARCHIVE),
    APK(".open.ApkInspector", "Filet APK Inspector", HandlerId.APK),
    TEXT(".open.CodeEditor", "Filet Code Editor", HandlerId.TEXT),
    ;

    companion object {
        /**
         * Which viewer an incoming intent was addressed to, from the component it arrived on.
         * Null for MainActivity itself - the plain "Filet" entry, which opens with whatever the
         * routing table says, exactly as before.
         *
         * Against the code NAMESPACE, not the package: a debug build's package is
         * `dev.niccc2007.filet.debug`, while an alias's class name resolves against the
         * namespace and stays `dev.niccc2007.filet.open.VideoPlayer` in every build.
         */
        fun of(className: String?, namespace: String = NAMESPACE): InboundViewer? {
            if (className.isNullOrEmpty()) return null
            return entries.firstOrNull { className == namespace + it.alias }
        }

        const val NAMESPACE = "dev.niccc2007.filet"
    }
}
