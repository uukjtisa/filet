package dev.niccc2007.filet.docs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Real OOXML packages, built in memory.
 *
 * Real zips rather than XML strings handed to a parser, because the defects this round fixed
 * were in how the parts of a package find each other - a style named in one part and defined in
 * another, a picture referenced by id and stored three folders away, a slide order kept in a
 * part that is not a slide. A test that skips the zip skips exactly the code that was wrong.
 */
object OfficeFixtures {

    /** A genuine 1x1 PNG, so a picture test carries bytes a decoder would accept. */
    val PNG: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    )

    const val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" " +
        "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\""

    const val P = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val REL_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"

    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun rels(vararg rels: Triple<String, String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"$REL_NS\">")
        for ((id, type, target) in rels) {
            val external = if (target.startsWith("http")) " TargetMode=\"External\"" else ""
            append("<Relationship Id=\"$id\" Type=\"$REL_TYPE$type\" Target=\"$target\"$external/>")
        }
        append("</Relationships>")
    }

    /** A DOCX: a body, and whichever side parts the test needs. */
    fun docx(
        body: String,
        styles: String? = null,
        numbering: String? = null,
        rels: String? = null,
        media: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "[Content_Types].xml" to "<Types/>".toByteArray()
        // Media BEFORE the relationships on purpose: a zip promises no order, and a reader that
        // only finds pictures listed by parts it has already seen would pass a friendlier fixture.
        for ((name, bytes) in media) entries += name to bytes
        rels?.let { entries += "word/_rels/document.xml.rels" to it.toByteArray() }
        entries += "word/document.xml" to
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document $W><w:body>$body</w:body></w:document>".toByteArray()
        styles?.let { entries += "word/styles.xml" to "<w:styles $W>$it</w:styles>".toByteArray() }
        numbering?.let { entries += "word/numbering.xml" to "<w:numbering $W>$it</w:numbering>".toByteArray() }
        return zip(entries)
    }

    /** One slide part. */
    fun slide(tree: String, hidden: Boolean = false): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld $P" + (if (hidden) " show=\"0\"" else "") +
            "><p:cSld><p:spTree>$tree</p:spTree></p:cSld></p:sld>"

    /**
     * A PPTX.
     *
     * @param slides file name (`slide1.xml`) to its XML.
     * @param order the deck's own order, as file names, written into `presentation.xml`.
     * @param slideRels file name to that slide's relationships.
     */
    fun pptx(
        slides: Map<String, String>,
        order: List<String> = slides.keys.toList(),
        slideRels: Map<String, String> = emptyMap(),
        notes: Map<String, String> = emptyMap(),
        media: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "[Content_Types].xml" to "<Types/>".toByteArray()
        for ((name, xml) in slides) entries += "ppt/slides/$name" to xml.toByteArray()
        for ((name, xml) in slideRels) entries += "ppt/slides/_rels/$name.rels" to xml.toByteArray()
        for ((name, xml) in notes) entries += "ppt/notesSlides/$name" to xml.toByteArray()
        for ((name, bytes) in media) entries += name to bytes
        val ids = order.mapIndexed { i, _ -> "<p:sldId id=\"${256 + i}\" r:id=\"rId${100 + i}\"/>" }.joinToString("")
        entries += "ppt/presentation.xml" to
            "<p:presentation $P><p:sldIdLst>$ids</p:sldIdLst></p:presentation>".toByteArray()
        entries += "ppt/_rels/presentation.xml.rels" to rels(
            *order.mapIndexed { i, name -> Triple("rId${100 + i}", "slide", "slides/$name") }.toTypedArray(),
        ).toByteArray()
        return zip(entries)
    }

    fun readDocx(bytes: ByteArray): Doc = OfficeReader.read({ ByteArrayInputStream(bytes) }, presentation = false)

    fun readPptx(bytes: ByteArray): Doc = OfficeReader.read({ ByteArrayInputStream(bytes) }, presentation = true)

    /** A paragraph of one run. */
    fun para(text: String, pPr: String = "", rPr: String = ""): String =
        "<w:p>" + (if (pPr.isEmpty()) "" else "<w:pPr>$pPr</w:pPr>") +
            "<w:r>" + (if (rPr.isEmpty()) "" else "<w:rPr>$rPr</w:rPr>") +
            "<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"

    /** A text shape on a slide. */
    fun shape(paragraphs: String, ph: String? = null, y: Long? = null): String =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"s\"/><p:cNvSpPr/><p:nvPr>" +
            (if (ph != null) "<p:ph type=\"$ph\"/>" else "") + "</p:nvPr></p:nvSpPr>" +
            "<p:spPr>" + (if (y != null) "<a:xfrm><a:off x=\"0\" y=\"$y\"/><a:ext cx=\"100\" cy=\"100\"/></a:xfrm>" else "") +
            "</p:spPr><p:txBody><a:bodyPr/>$paragraphs</p:txBody></p:sp>"

    fun aPara(text: String, pPr: String = "", rPr: String = ""): String =
        "<a:p>" + (if (pPr.isEmpty()) "" else pPr) +
            "<a:r>" + (if (rPr.isEmpty()) "<a:rPr lang=\"en-US\"/>" else rPr) + "<a:t>$text</a:t></a:r></a:p>"
}
