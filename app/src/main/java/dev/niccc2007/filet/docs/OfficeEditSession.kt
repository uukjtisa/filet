package dev.niccc2007.filet.docs

/**
 * One DOCX or PPTX open for text editing: the original bytes, and the paragraphs that can change.
 *
 * Pure, so the whole save - splice, re-zip, check - is tested on real archives off the device.
 * The screen only collects edits and hands them here; the filesystem half is [SafeReplace].
 */
internal class OfficeEditSession private constructor(
    val original: ByteArray,
    val presentation: Boolean,
    val sections: List<Section>,
) {

    /** One XML part's editable paragraphs: the body of a DOCX, or one slide. */
    class Section(val entry: String, val label: String, val xml: String, val paragraphs: List<OfficeTextEdit.Paragraph>)

    private val flavor get() = if (presentation) OfficeTextEdit.Flavor.SLIDES else OfficeTextEdit.Flavor.WORD

    /**
     * Apply [edits] (entry name to paragraph index to new text) and return the new archive with
     * the parts that changed. Checked before it is returned - see [ZipPatch.check].
     *
     * @throws ZipPatch.Refused naming the paragraph that could not take its edit.
     */
    fun save(edits: Map<String, Map<Int, String>>): Pair<ByteArray, Map<String, ByteArray>> {
        val replacements = LinkedHashMap<String, ByteArray>()
        for (section in sections) {
            val mine = edits[section.entry]?.takeIf { it.isNotEmpty() } ?: continue
            when (val r = OfficeTextEdit.apply(section.xml, flavor, mine)) {
                is OfficeTextEdit.Result.Ok -> if (r.xml != section.xml) replacements[section.entry] = r.xml.toByteArray(Charsets.UTF_8)
                is OfficeTextEdit.Result.Refused -> throw ZipPatch.Refused("${section.label}: ${r.why}")
            }
        }
        if (replacements.isEmpty()) return original to replacements
        val patched = ZipPatch.replace(original, replacements)
        ZipPatch.check(original, patched, replacements)
        return patched to replacements
    }

    companion object {
        /**
         * The most a document may weigh to be edited here. Saving holds the original, the edited
         * copy and the copy read back for checking, all at once.
         */
        const val MAX_BYTES = 32 * 1024 * 1024

        fun load(bytes: ByteArray, presentation: Boolean): OfficeEditSession {
            if (bytes.size > MAX_BYTES) throw ZipPatch.Refused("This file is too large to edit here.")
            val archive = ZipPatch.open(bytes)
            val names = archive.entries.map { it.name }
            fun text(name: String): String? = archive.entries.firstOrNull { it.name == name }?.let {
                val xml = String(ZipPatch.read(bytes, it), Charsets.UTF_8)
                if (!isUtf8(xml)) throw ZipPatch.Refused("$name is not UTF-8, which this can't edit.")
                xml
            }
            val sections = ArrayList<Section>()
            if (!presentation) {
                val xml = text(WordReader.BODY) ?: throw ZipPatch.Refused("No document text was found inside this file.")
                sections += Section(WordReader.BODY, "Document", xml, editable(xml, OfficeTextEdit.Flavor.WORD))
            } else {
                val slides = names.filter { it.startsWith(SlideReader.SLIDE_PREFIX) && it.endsWith(".xml") }
                val deckRels = Ooxml.rels(text(SlideReader.PRESENTATION_RELS), SlideReader.PRESENTATION)
                val listed = runCatching { SlideReader.slideIds(text(SlideReader.PRESENTATION)) }.getOrDefault(emptyList())
                    .mapNotNull { deckRels[it]?.target }
                for ((i, name) in SlideReader.order(slides, listed).withIndex()) {
                    val xml = text(name) ?: continue
                    sections += Section(name, "Slide ${i + 1}", xml, editable(xml, OfficeTextEdit.Flavor.SLIDES))
                }
                if (sections.isEmpty()) throw ZipPatch.Refused("No slides were found inside this file.")
            }
            return OfficeEditSession(bytes, presentation, sections)
        }

        private fun editable(xml: String, flavor: OfficeTextEdit.Flavor) =
            OfficeTextEdit.scan(xml, flavor).filter { it.hasText }

        /** An XML declaration naming anything but UTF-8 is a part this would re-encode wrongly. */
        private fun isUtf8(xml: String): Boolean {
            val decl = Regex("""^﻿?<\?xml[^>]*encoding=["']([^"']+)["']""").find(xml) ?: return true
            return decl.groupValues[1].replace("-", "").equals("utf8", ignoreCase = true)
        }
    }
}
