package dev.niccc2007.filet.metadata

/**
 * A small fragmented MP4 in the shape a streaming download has: `ftyp` (brand dash), `moov`
 * with an empty sample table and `mvex`, a `sidx`, then `moof` and `mdat` pairs.
 *
 * Synthetic on purpose - the real files this was found on are somebody's music. Each `mdat`
 * starts with a marker byte, and each `trun` points at it by an offset counted from its `moof`,
 * so a tag edit that moved one without the other shows up as the wrong byte.
 */
internal object FragmentedMp4 {

    fun be(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    fun atom(type: String, payload: ByteArray): ByteArray =
        be(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    /** A full box's version and flags. */
    private fun vf(flags: Int): ByteArray = be(flags and 0xFFFFFF)

    /** Default-base-is-moof, sample description index, default duration, default flags. */
    const val DASH_TFHD = 0x2002a

    /** The same with base-data-offset-present: an absolute position in the file. */
    const val ABSOLUTE_TFHD = 0x2002a or 0x000001

    private val ftyp = atom("ftyp", "dash".toByteArray(Charsets.ISO_8859_1) + be(0) + "iso6mp41".toByteArray(Charsets.ISO_8859_1))

    private val moov = atom(
        "moov",
        atom("mvhd", ByteArray(100)) +
            atom("mvex", atom("trex", vf(0) + be(1) + be(1) + be(0) + be(0) + be(0))) +
            atom("trak", atom("mdia", atom("minf", atom("stbl", atom("stco", vf(0) + be(0)))))),
    )

    private val sidx = atom("sidx", vf(0) + be(1) + be(44100) + be(0) + be(0) + byteArrayOf(0, 0) + be(0).copyOfRange(2, 4))

    /** One fragment: its `moof`, whose `trun` points at the first byte of the `mdat` after it. */
    fun fragment(marker: Int, tfhdFlags: Int = DASH_TFHD): ByteArray {
        val samples = 4
        val media = ByteArray(32) { if (it == 0) marker.toByte() else (it + marker).toByte() }
        val tfhd = atom(
            "tfhd",
            vf(tfhdFlags) + be(1) + (if (tfhdFlags and 1 != 0) be(0) + be(0) else ByteArray(0)) +
                be(1) + be(1024) + be(0),
        )
        fun trun(offset: Int) = atom("trun", vf(0x201) + be(samples) + be(offset) + (1..samples).fold(ByteArray(0)) { a, _ -> a + be(8) })
        val mfhd = atom("mfhd", vf(0) + be(marker))
        // The data offset counts from the moof's first byte to the mdat's first media byte.
        val probe = atom("moof", mfhd + atom("traf", tfhd + trun(0)))
        val moof = atom("moof", mfhd + atom("traf", tfhd + trun(probe.size + 8)))
        return moof + atom("mdat", media)
    }

    /** The whole file. [before] goes ahead of `moov`; [after] at the end. */
    fun file(
        fragments: List<ByteArray> = listOf(fragment(0x51), fragment(0x52), fragment(0x53)),
        before: ByteArray = ByteArray(0),
        after: ByteArray = ByteArray(0),
        withSidx: Boolean = true,
    ): ByteArray = ftyp + before + moov + (if (withSidx) sidx else ByteArray(0)) +
        fragments.fold(ByteArray(0)) { a, f -> a + f } + after

    val sidxBox: ByteArray get() = sidx

    /**
     * Every `moof` in [b], with the byte its `trun` points at: the fragment's marker when the
     * offsets are right.
     */
    fun pointedAt(b: ByteArray): List<Byte> {
        val out = ArrayList<Byte>()
        for (moof in Mp4Tags.atoms(b, 0, b.size).filter { it.type == "moof" }) {
            val traf = Mp4Tags.atoms(b, moof.contentAt, moof.end).first { it.type == "traf" }
            val trun = Mp4Tags.atoms(b, traf.contentAt, traf.end).first { it.type == "trun" }
            val offset = ((b[trun.contentAt + 8].toInt() and 0xFF) shl 24) or ((b[trun.contentAt + 9].toInt() and 0xFF) shl 16) or
                ((b[trun.contentAt + 10].toInt() and 0xFF) shl 8) or (b[trun.contentAt + 11].toInt() and 0xFF)
            out.add(b[moof.at + offset])
        }
        return out
    }
}
