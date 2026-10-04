package dev.niccc2007.filet.metadata

/**
 * One container's metadata, declared once.
 *
 * ## The fault this shape exists to stop
 *
 * The support table and the writer used to be two separate lists of formats, and they drifted
 * three times over: MP3 was advertised with an attached picture and had no code at all, EXIF
 * advertised nine fields with no reader, WebP advertised a comment slot that was never written.
 * Each one was a claim a reader checks by trying it, and each one was found by trying it.
 *
 * The cause was not carelessness about any one row - it was the shape. Routing lived in three
 * places (`read`, `put` and `verify`, each with its own chain of conditions) and the table lived
 * in a fourth, so adding a format meant four edits and forgetting one was silent.
 *
 * So a format is declared **once**, here, as an object that can say whether it recognises a file
 * and then do all four jobs. [MetadataSupport] points at it, [MetadataStore] routes through it,
 * and a test asserts that no row can claim a tier its container does not implement. Adding a
 * format is now one declaration and one table row that has to reference it.
 */
interface MetadataContainer {

    /** Whether this container recognises the file. Both the name and the bytes get a say. */
    fun matches(ext: String, bytes: ByteArray): Boolean

    /** Everything readable, as label to value. */
    fun read(bytes: ByteArray): List<Pair<String, String>>

    /**
     * The rebuilt file with one field set, or null if this container cannot set that field.
     *
     * Null means *refused*, and the refusal is the feature: a container with a fixed set of
     * fields must not write "secret note" into whichever slot happens to be nearest.
     */
    fun put(bytes: ByteArray, label: String, value: String): ByteArray? = null

    /**
     * Does the rebuilt file read back with the value that was just written?
     *
     * Asked of the result before the original is replaced, and the single check standing between
     * a misunderstood variant and a destroyed file. A container that cannot check its own output
     * has no business writing.
     */
    fun verify(built: ByteArray, label: String, value: String): Boolean = false

    /** The attached picture, if the container has one and carries one. */
    fun cover(bytes: ByteArray): Cover? = null

    /** The rebuilt file with the picture replaced, or removed when given null. */
    fun putCover(bytes: ByteArray, cover: Cover?): ByteArray? = null

    /** Whether this container can write at all, which the support table is checked against. */
    val writes: Boolean get() = false

    /** Whether it can carry a picture, likewise. */
    val pictures: Boolean get() = false

    /** Why a write is impossible on this *particular* file, or null if it is possible. */
    fun refusal(bytes: ByteArray): String? = null
}

/**
 * A shared `verify` for the containers whose read is a plain list of labelled fields.
 *
 * Written once because every one of them wants exactly the same thing: after a write, the label
 * reads back with the new value, and after a clear, the label is not there with a value at all.
 */
private fun verifyByRead(
    read: (ByteArray) -> List<Pair<String, String>>,
    built: ByteArray,
    label: String,
    value: String,
): Boolean {
    val fields = read(built)
    return if (value.isBlank()) {
        fields.none { it.first.equals(label, ignoreCase = true) && it.second.isNotBlank() }
    } else {
        fields.any { it.first.equals(label, ignoreCase = true) && it.second == value }
    }
}

// ── images ────────────────────────────────────────────────────────────────────────────────

object PngContainer : MetadataContainer {
    override val writes = true
    override fun matches(ext: String, bytes: ByteArray) = PngText.isPng(bytes)
    override fun read(bytes: ByteArray) = PngText.read(bytes).map { it.key to it.value }
    override fun put(bytes: ByteArray, label: String, value: String): ByteArray? {
        if (!PngText.isValidKeyword(label)) return null
        return PngText.put(bytes, label, value)
    }
    override fun verify(built: ByteArray, label: String, value: String) =
        verifyByRead(this::read, built, label, value)
    override fun refusal(bytes: ByteArray): String? = null
}

object JpegCommentContainer : MetadataContainer {
    override val writes = true
    override fun matches(ext: String, bytes: ByteArray) = ContainerComments.isJpeg(bytes)
    override fun read(bytes: ByteArray) =
        ContainerComments.readJpegComment(bytes)?.let { listOf("Comment" to it) } ?: emptyList()
    override fun put(bytes: ByteArray, label: String, value: String): ByteArray? {
        if (!label.equals("Comment", ignoreCase = true)) return null
        return ContainerComments.writeJpegComment(bytes, value)
    }
    override fun verify(built: ByteArray, label: String, value: String) =
        if (value.isBlank()) ContainerComments.readJpegComment(built).isNullOrEmpty()
        else ContainerComments.readJpegComment(built) == value
}

/** The camera's own record, which sits in the same file as the comment and is read separately. */
object JpegExifContainer : MetadataContainer {
    override fun matches(ext: String, bytes: ByteArray) = Exif.hasJpeg(bytes)
    override fun read(bytes: ByteArray) = Exif.readJpeg(bytes)
}

object WebpExifContainer : MetadataContainer {
    override fun matches(ext: String, bytes: ByteArray) = Exif.hasRiff(bytes)
    override fun read(bytes: ByteArray) = Exif.readRiff(bytes)
}

object GifContainer : MetadataContainer {
    override val writes = true
    override fun matches(ext: String, bytes: ByteArray) = ContainerComments.isGif(bytes)
    override fun read(bytes: ByteArray) = emptyList<Pair<String, String>>()
    override fun put(bytes: ByteArray, label: String, value: String): ByteArray? {
        if (!label.equals("Comment", ignoreCase = true)) return null
        return ContainerComments.writeGifComment(bytes, value)
    }
    // There is no GIF comment reader, so there is nothing to check the write against. The writer
    // is append-only and the segment it appends is a fixed shape, which is the only reason this
    // is allowed to say yes - and it is the one container where that is true.
    override fun verify(built: ByteArray, label: String, value: String) = ContainerComments.isGif(built)
}

// ── archives and documents ────────────────────────────────────────────────────────────────

object ZipContainer : MetadataContainer {
    override val writes = true
    override fun matches(ext: String, bytes: ByteArray) =
        ContainerComments.readZipComment(bytes) != null && !Ooxml.isOoxml(bytes)
    override fun read(bytes: ByteArray) =
        ContainerComments.readZipComment(bytes)?.let { listOf("Comment" to it) } ?: emptyList()
    override fun put(bytes: ByteArray, label: String, value: String): ByteArray? {
        if (!label.equals("Comment", ignoreCase = true)) return null
        return ContainerComments.writeZipComment(bytes, value)
    }
    override fun verify(built: ByteArray, label: String, value: String) =
        ContainerComments.readZipComment(built) == value
}

object OoxmlContainer : MetadataContainer {
    override val writes = true
    override fun matches(ext: String, bytes: ByteArray) = Ooxml.isOoxml(bytes)
    override fun read(bytes: ByteArray) = Ooxml.read(bytes)
    override fun put(bytes: ByteArray, label: String, value: String) = Ooxml.put(bytes, label, value)
    override fun verify(built: ByteArray, label: String, value: String) =
        verifyByRead(this::read, built, label, value)
    override fun refusal(bytes: ByteArray): String? =
        if (Ooxml.core(bytes) == null) {
            "This package has no docProps/core.xml. Adding one means also declaring it in " +
                "[Content_Types].xml and in the relationships, and a document with two of those " +
                "three is one Word offers to repair."
        } else {
            null
        }
}

object PdfContainer : MetadataContainer {
    override val writes = true
    override fun matches(ext: String, bytes: ByteArray) = PdfInfo.isPdf(bytes)
    override fun read(bytes: ByteArray) = PdfInfo.read(bytes)
    override fun put(bytes: ByteArray, label: String, value: String) = PdfInfo.put(bytes, label, value)
    override fun verify(built: ByteArray, label: String, value: String) =
        verifyByRead(this::read, built, label, value)
    override fun refusal(bytes: ByteArray) = PdfInfo.refusal(bytes)
}

// ── audio ─────────────────────────────────────────────────────────────────────────────────

object Id3Container : MetadataContainer {
    override val writes = true
    override val pictures = true
    override fun matches(ext: String, bytes: ByteArray) =
        ext.lowercase().removePrefix(".") == "mp3" && Id3.isMp3(bytes)
    override fun read(bytes: ByteArray) = Id3.read(bytes)
    override fun put(bytes: ByteArray, label: String, value: String) = Id3.put(bytes, label, value)
    override fun verify(built: ByteArray, label: String, value: String) =
        verifyByRead(this::read, built, label, value)
    override fun cover(bytes: ByteArray) = Id3.picture(bytes)
    override fun putCover(bytes: ByteArray, cover: Cover?) = Id3.putPicture(bytes, cover)
}

object FlacContainer : MetadataContainer {
    override val writes = true
    override val pictures = true
    override fun matches(ext: String, bytes: ByteArray) = VorbisComment.isFlac(bytes)
    override fun read(bytes: ByteArray) = VorbisComment.readFlac(bytes)
    override fun put(bytes: ByteArray, label: String, value: String) =
        VorbisComment.putFlac(bytes, label, value)
    override fun verify(built: ByteArray, label: String, value: String) =
        verifyByRead(this::read, built, label, value)
    override fun cover(bytes: ByteArray) = VorbisComment.flacPicture(bytes)
    override fun putCover(bytes: ByteArray, cover: Cover?) = VorbisComment.putFlacPicture(bytes, cover)
}

object OggContainer : MetadataContainer {
    override val writes = true
    override val pictures = true
    override fun matches(ext: String, bytes: ByteArray) = VorbisComment.isOgg(bytes)
    override fun read(bytes: ByteArray) = VorbisComment.readOgg(bytes)
    override fun put(bytes: ByteArray, label: String, value: String) =
        VorbisComment.putOgg(bytes, label, value)
    override fun verify(built: ByteArray, label: String, value: String) =
        verifyByRead(this::read, built, label, value)
    override fun cover(bytes: ByteArray) = VorbisComment.oggPicture(bytes)
    override fun putCover(bytes: ByteArray, cover: Cover?) = VorbisComment.putOggPicture(bytes, cover)
    override fun refusal(bytes: ByteArray): String? {
        if (VorbisComment.withOggTag(bytes, VorbisComment.oggTag(bytes)) != null) return null
        return "This Ogg stream is one Filet will not rewrite: either it carries more than one " +
            "stream multiplexed together, or its last header packet shares a page with the start " +
            "of the audio. Repaginating it would risk the audio to save a tag."
    }
}

object Mp4Container : MetadataContainer {
    override val writes = true
    override val pictures = true
    override fun matches(ext: String, bytes: ByteArray) = Mp4Tags.isMp4(bytes)
    override fun read(bytes: ByteArray) = Mp4Tags.read(bytes)
    override fun put(bytes: ByteArray, label: String, value: String) = Mp4Tags.put(bytes, label, value)
    override fun verify(built: ByteArray, label: String, value: String) =
        verifyByRead(this::read, built, label, value)
    override fun cover(bytes: ByteArray) = Mp4Tags.picture(bytes)
    override fun putCover(bytes: ByteArray, cover: Cover?) = Mp4Tags.putPicture(bytes, cover)
    override fun refusal(bytes: ByteArray): String? =
        if (Mp4Tags.isFragmented(bytes)) {
            "This is a fragmented MP4. Its offsets are spread across moof and sidx boxes that a " +
                "streaming player seeks by, and a file that plays from the start and then stops " +
                "at the first seek is worse than one Filet declines to change."
        } else {
            null
        }
}
