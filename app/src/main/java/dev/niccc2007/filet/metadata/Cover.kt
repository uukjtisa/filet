package dev.niccc2007.filet.metadata

/**
 * An attached picture, in the one shape every format here speaks.
 *
 * ## Why this is its own type
 *
 * Four containers can carry cover art and all four describe it differently: ID3 writes an APIC
 * frame, FLAC a PICTURE block, Ogg the same block base64'd into a comment field, MP4 a `covr`
 * atom. Three of the four were written before this type existed, each with its own picture class,
 * and the screen above them would then have needed a branch per format to show one image.
 *
 * So the picture is declared once, here, and each format converts at its own edge. Adding a fifth
 * container is then a reader and a writer, not another case in the UI.
 *
 * @param kind the ID3 picture type, which FLAC and Ogg reuse verbatim. 3 is the front cover and
 *   is what every player shows; 0 is "other", which is what a format with no slot for the idea
 *   gets mapped to.
 */
data class Cover(
    val mime: String,
    val kind: Int = FRONT,
    val description: String = "",
    val bytes: ByteArray,
) {
    // The generated equals on a data class holding an array compares identity, which for an image
    // is never what a caller means - two reads of the same cover would compare unequal.
    override fun equals(other: Any?): Boolean =
        other is Cover && mime == other.mime && kind == other.kind &&
            description == other.description && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        ((mime.hashCode() * 31 + kind) * 31 + description.hashCode()) * 31 + bytes.contentHashCode()

    /** A sensible filename extension for an extracted copy, including the dot. */
    val extension: String get() = extensionFor(mime, bytes)

    companion object {
        const val FRONT = 3

        /**
         * What the bytes actually are, which is not always what the container claims.
         *
         * Files in the wild carry `image/jpg`, `JPG`, an empty string, or a mime that disagrees
         * with the data. The bytes are the authority and the recorded mime is a hint, so a picture
         * extracted from a tag gets the extension that will actually open.
         */
        fun sniff(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
            bytes.size >= 6 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            else -> null
        }

        fun extensionFor(mime: String, bytes: ByteArray = ByteArray(0)): String {
            val real = sniff(bytes) ?: mime.lowercase().trim()
            return when {
                real.contains("png") -> ".png"
                real.contains("gif") -> ".gif"
                real.contains("webp") -> ".webp"
                real.contains("bmp") -> ".bmp"
                // Covers image/jpeg, image/jpg, the bare "JPG" some taggers write, and the
                // unknown case - a cover with no usable type is overwhelmingly a JPEG.
                else -> ".jpg"
            }
        }

        /** The mime to record when writing, corrected against the bytes. */
        fun mimeFor(claimed: String, bytes: ByteArray): String =
            sniff(bytes) ?: claimed.ifBlank { "image/jpeg" }

        /** A picture built from raw bytes, with its type read off the data. */
        fun of(bytes: ByteArray, description: String = ""): Cover =
            Cover(sniff(bytes) ?: "image/jpeg", FRONT, description, bytes)
    }
}
