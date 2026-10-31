package dev.niccc2007.filet.vfs.provider

import java.io.File
import java.io.IOException

/**
 * Putting a rewritten archive in place of the old one, so that no moment of it can cost the
 * original.
 *
 * The new archive is written beside the old one as `<name>.editing`. Only once it is whole is
 * the original moved aside to `<name>.previous`, the new one renamed into its place, and the
 * aside copy deleted. A process killed anywhere in that leaves one of three states, and
 * [recover] turns each back into one whole archive under the right name:
 *
 * - killed while writing: a part-written `.editing` and the original untouched - the part goes;
 * - killed after the original went aside: `.previous` and no archive - the original comes back;
 * - killed after the new one went in: both - the new one stays and `.previous` goes.
 *
 * Over [Fs] rather than files, so every one of those can be produced in a test.
 */
internal object ArchiveSwap {

    interface Fs {
        fun exists(name: String): Boolean
        fun rename(from: String, to: String): Boolean
        fun delete(name: String): Boolean
    }

    enum class Recovery { NOTHING, DROPPED_PART, RESTORED_ORIGINAL, KEPT_NEW }

    fun editing(target: String) = "$target.editing"
    fun previous(target: String) = "$target.previous"

    /** Put the finished [editing] file in place of [target]. On failure the original is back where it was. */
    fun swap(fs: Fs, target: String) {
        val temp = editing(target)
        val aside = previous(target)
        if (!fs.exists(temp)) throw IOException("the rewritten archive is missing")
        if (fs.exists(aside)) fs.delete(aside)
        // Aside rather than deleted, so the window in which neither exists is as near nothing as
        // a filesystem allows, and is recoverable if the process dies inside it.
        if (fs.exists(target) && !fs.rename(target, aside)) throw IOException("could not move the original aside")
        if (!fs.rename(temp, target)) {
            fs.rename(aside, target)
            throw IOException("could not put the rewritten archive in place")
        }
        fs.delete(aside)
    }

    /** Undo whatever a swap killed part way left behind. Run before reading or rewriting [target]. */
    fun recover(fs: Fs, target: String): Recovery {
        val temp = editing(target)
        val aside = previous(target)
        val hasTarget = fs.exists(target)
        val hasAside = fs.exists(aside)
        val hasTemp = fs.exists(temp)
        return when {
            hasAside && !hasTarget -> {
                // The original is the one known whole: the new one may be whole too, but nothing
                // recorded that it was, and the rule is that a killed edit leaves the original.
                fs.rename(aside, target)
                if (hasTemp) fs.delete(temp)
                Recovery.RESTORED_ORIGINAL
            }
            hasAside -> {
                fs.delete(aside)
                if (hasTemp) fs.delete(temp)
                Recovery.KEPT_NEW
            }
            hasTemp -> {
                fs.delete(temp)
                Recovery.DROPPED_PART
            }
            else -> Recovery.NOTHING
        }
    }

    /** The real thing: names in [dir]. */
    fun filesIn(dir: File): Fs = object : Fs {
        override fun exists(name: String) = File(dir, name).exists()
        override fun rename(from: String, to: String) = File(dir, from).renameTo(File(dir, to))
        override fun delete(name: String) = File(dir, name).delete()
    }
}
