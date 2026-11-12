package dev.niccc2007.filet.remotes

/**
 * The Command Prompt line that maps a share as a Windows drive.
 *
 * The UNC spelling is the part that is easy to get wrong: Windows only routes a path to its WebDAV
 * client when the port rides on the host as `@port` and the share sits under `DavWWWRoot`. An
 * `http://` address in the same place is treated as a web fetch and maps nothing.
 *
 * `*` lets Windows take the next free letter instead of failing on one that is in use.
 * `/persistent:yes` is only added for a pinned code: an unpinned code changes on every start, so a
 * drive that came back after a restart would come back pointing at an address that no longer
 * exists.
 */
object WindowsMount {
    fun command(host: String, port: Int, code: String, pinned: Boolean): String =
        "net use * \\\\$host@$port\\DavWWWRoot\\a\\$code" + if (pinned) " /persistent:yes" else ""
}
