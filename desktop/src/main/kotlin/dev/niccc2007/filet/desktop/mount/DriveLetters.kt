package dev.niccc2007.filet.desktop.mount

/**
 * Which drive letter a new mount takes on Windows.
 *
 * From `Z:` downwards, the convention for network drives, so a mapped phone never takes the
 * letter the next USB stick would have been given. `A:`-`C:` are never offered: the first two are
 * still reserved for floppy drives by parts of Windows, and `C:` is the system.
 *
 * [taken] has to include remembered mappings as well as live drives. A persistent mapping whose
 * server is offline is absent from `File.listRoots()` and still owns its letter - choosing it
 * fails with `ERROR_ALREADY_ASSIGNED` or, worse, `ERROR_DEVICE_ALREADY_REMEMBERED`, which reads
 * as the phone being at fault.
 *
 * Null when every letter is used. The window disables Mount and says why, rather than offering a
 * button that can only fail.
 */
object DriveLetters {
    val CANDIDATES: List<Char> = ('Z' downTo 'D').toList()

    fun pick(taken: Set<Char>): Char? {
        val up = taken.map { it.uppercaseChar() }.toSet()
        return CANDIDATES.firstOrNull { it !in up }
    }

    /** The letter in a local name like `Z:` or `z:\`, or null. */
    fun letterOf(local: String?): Char? =
        local?.trim()?.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' && local.trim().getOrNull(1) == ':' }
}
