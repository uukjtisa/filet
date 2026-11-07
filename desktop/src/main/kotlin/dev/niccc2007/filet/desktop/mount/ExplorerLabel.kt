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
}
