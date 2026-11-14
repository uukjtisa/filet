package dev.niccc2007.filet.vfs.provider

import dev.niccc2007.filet.vfs.RangedBlocks
import java.io.File

/**
 * Zips on another device opened where they lie, rather than copied over first. Step 2 of
 * `REMOTE-FILES.md`.
 *
 * An archive's address is a file path, and everything in [ArchiveProvider] reads it as one. So a
 * zip on a share is mounted through a stand-in: a small file in Filet's cache, named as the zip
 * is, that says where the real one is. Being a file, it still opens from a tab or a recent entry
 * after a restart. This answers which files are stand-ins, and hands over the bytes behind one.
 */
interface RangedMounts {

    /** Whether [host] is a stand-in for a zip on another device. From its path alone, no read. */
    fun holds(host: File): Boolean

    /**
     * The bytes behind the stand-in [host], read from the other device as they are asked for.
     * Null when it stands in for nothing this process can reach.
     */
    fun blocksOf(host: File): RangedBlocks?

    companion object {
        /** No zip is read in place: every remote archive is staged as a copy. */
        val NONE: RangedMounts = object : RangedMounts {
            override fun holds(host: File): Boolean = false
            override fun blocksOf(host: File): RangedBlocks? = null
        }
    }
}
