package dev.niccc2007.filet.desktop.mount

/**
 * The name Explorer shows for a mapped WebDAV drive.
 *
 * There is no API call for it. Explorer reads the string value `_LabelFromReg` under
 * `HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2\<key>`, where the key is
 * the mapping's UNC path with every backslash turned into `#`. Without it a phone's drive shows
 * as its code and server address, which nobody chose as a name.
 */
object ExplorerLabel {
    const val PARENT = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\MountPoints2"
    const val VALUE = "_LabelFromReg"

    /** The MountPoints2 key for [unc]: `\\host@8321\DavWWWRoot\a\code` becomes `##host@8321#DavWWWRoot#a#code`. */
    fun keyFor(unc: String): String = unc.replace('\\', '#')

    /**
     * Both keys for one WebDAV drive, with and without `DavWWWRoot`, whichever spelling [unc] is.
     *
     * Explorer keeps one of each for every WebDAV mapping on this PC, and names typed into
     * Explorer itself landed under either, depending on the drive. A name written under one
     * alone can be the one Explorer does not read, so both get it.
     */
    fun keysFor(unc: String): List<String> {
        val parts = unc.removePrefix("\\\\").split('\\')
        val rest = parts.drop(1).filterNot { it.equals("DavWWWRoot", ignoreCase = true) }
        val short = (listOf(parts[0]) + rest).joinToString("\\")
        val long = (listOf(parts[0], "DavWWWRoot") + rest).joinToString("\\")
        return listOf("\\\\" + short, "\\\\" + long).map(::keyFor)
    }
}
