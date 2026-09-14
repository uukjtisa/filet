package dev.niccc2007.filet.vfs.provider

import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * An output stream that rolls over into numbered parts.
 *
 * This is the [SplitStyle.NUMBERED_STREAM] half of Nic's *"a multi part archive.. like others
 * part1 part 2 etc"*. It is a plain byte split of the finished archive: `backup.7z.001`,
 * `.002`, and so on, where **concatenating the parts in order reproduces the original file
 * exactly**. That is also how 7-Zip's own `.7z.001` volumes work, so a set written this way
 * opens in other tools rather than only in Filet.
 *
 * Zip is the exception and does not come through here - a zip volume set is not a byte split,
 * because the final `.zip` carries the central directory. zip4j writes those itself.
 *
 * ## Two things it refuses rather than gets wrong
 *
 * **Running past the numbering.** The width is fixed when the first part opens, because
 * renaming `.001` after `.999` rolled over would mean rewriting the whole set. If the archive
 * turns out bigger than the estimate it was sized for, it throws at the boundary instead of
 * writing a `.1000` that sorts before `.002` and that some tools will not see at all.
 *
 * **A half-written set left behind.** [abandon] deletes every part it has opened. An
 * interrupted split leaves nothing rather than a folder of numbered files that look like an
 * archive and are not one.
 */
class SplitSink(
    private val dir: File,
    private val setName: String,
    private val partBytes: Long,
    /** Digits in the suffix, from the expected part count. At least three - every tool expects that. */
    private val padding: Int = 3,
) : OutputStream() {

    init {
        require(partBytes > 0) { "a part has to have a size" }
        require(padding >= 3) { "every tool expects at least three digits" }
    }

    private val written = ArrayList<File>()
    private var current: OutputStream? = null
    private var inPart = 0L
    private var index = 0

    /** The parts written so far, in order. */
    val parts: List<File> get() = written.toList()

    private val limit: Int = (Math.pow(10.0, padding.toDouble()) - 1).toInt()

    private fun roll() {
        current?.flush()
        current?.close()
        index++
        if (index > limit) {
            throw IOException(
                "This archive needs more than $limit parts at ${partBytes / 1024} KB each. " +
                    "Use a bigger part size.",
            )
        }
        val name = PartSets.nameFor(setName, PartStyle.NUMBERED, index, padding)
        val file = File(dir, name)
        written.add(file)
        current = file.outputStream().buffered()
        inPart = 0
    }

    private fun sink(): OutputStream = current ?: run { roll(); current!! }

    override fun write(b: Int) {
        if (inPart >= partBytes) roll()
        sink().write(b)
        inPart++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var start = off
        var left = len
        while (left > 0) {
            val out = sink()
            // Fill the current part exactly before opening the next, so every part but the last
            // is precisely partBytes. A set with ragged parts still concatenates, but it fails
            // the equality check any other tool makes against the size it was told.
            val room = (partBytes - inPart).coerceAtLeast(0L)
            if (room == 0L) { roll(); continue }
            val take = minOf(left.toLong(), room).toInt()
            out.write(b, start, take)
            inPart += take
            start += take
            left -= take
        }
    }

    override fun flush() {
        current?.flush()
    }

    override fun close() {
        current?.flush()
        current?.close()
        current = null
    }

    /** Delete every part. For a cancelled or failed write, so nothing half-made is left. */
    fun abandon() {
        runCatching { close() }
        for (f in written) runCatching { f.delete() }
        written.clear()
    }

    companion object {
        /**
         * How many digits a set of this size needs.
         *
         * Three unless the count demands more, because three is what every other tool writes
         * and a set that looks unusual is one somebody distrusts.
         */
        fun paddingFor(totalBytes: Long, partBytes: Long): Int {
            if (partBytes <= 0) return 3
            val count = maxOf(1L, (totalBytes + partBytes - 1) / partBytes)
            return maxOf(3, count.toString().length)
        }
    }
}
