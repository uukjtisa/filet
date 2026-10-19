package dev.niccc2007.filet.docs

import java.io.InputStream

/**
 * DOCX and PPTX, read without a dependency.
 *
 * ## Why this is not Apache POI
 *
 * POI is the obvious answer and it is the wrong one on Android. It is around twelve megabytes
 * of jars, it reaches for `javax.xml` classes Android does not ship, it needs desugaring to get
 * past Java 8 APIs, and the community forks that paper over all of that are years stale. For an
 * app whose entire release is twenty-eight megabytes, adding half again to read two formats is
 * not a trade worth making.
 *
 * And it is not necessary, because of what these files are. A DOCX is a ZIP holding
 * `word/document.xml`; a PPTX is a ZIP holding `ppt/slides/slide1.xml` and friends. Filet reads
 * ZIPs already - it is a file manager - and both Android and the JVM ship a SAX parser. So the
 * whole reader is a stream walk and a tag match: a few hundred lines, no dependency, no NDK and
 * no size cost.
 *
 * ## Why SAX and not Android's pull parser
 *
 * `XmlPullParser` is the idiomatic Android choice and it cannot be unit-tested: the class in the
 * unit-test android.jar is a stub that throws. A format reader is the single thing here most
 * likely to be subtly wrong - a tag nested one level deeper than expected, a producer that
 * writes `w:b` with no value - and a reader that cannot be tested without a phone is a reader
 * whose bugs are found by the person reading the document. `javax.xml.parsers.SAXParserFactory`
 * is real on both, so every one of these cases is a test.
 *
 * ## What it reads, and what it does not pretend to
 *
 * Read: headings (by style name and outline level), bold, italic, underline, strikethrough,
 * colour, highlight, relative size, superscript and subscript; alignment and indentation;
 * bulleted and numbered lists with the markers the file defines; tables with merged cells;
 * hyperlinks; pictures, resolved through the relationship files into the package's media
 * folder; and for a deck, slides in presentation order, shapes in reading order, and speaker
 * notes.
 *
 * **Not** page layout, columns, floating positions, headers and footers, footnotes, charts,
 * SmartArt, revision marks or content controls. That is a line rather than a to-do: reflowing
 * to a phone's width is the only way a document is readable on one, so the original geometry is
 * not wanted even where it is present - and a reader that half-reproduces a layout reads as
 * broken, where one that plainly reflows reads as a reader.
 *
 * Anything found and not drawn is counted and said, because a document that is quietly missing
 * a third of itself is the one failure mode that must not be silent.
 */
object OfficeReader {

    /**
     * Read whichever of the two this is.
     *
     * @param open a fresh stream over the file. A function rather than a stream, because the
     *   pictures need a second pass over the zip once the relationship files say which ones are
     *   wanted, and handing this an already-open stream makes the caller responsible for a
     *   detail it cannot know about.
     */
    fun read(open: () -> InputStream, presentation: Boolean): Doc = runCatching {
        if (presentation) slides(open) else document(open)
    }.getOrElse { e ->
        Doc(
            emptyList(),
            note = "This file could not be read: " + (e.message ?: e::class.java.simpleName),
        )
    }

    // ── Word ──────────────────────────────────────────────────────────────────────

    private val WORD_PARTS = setOf(WordReader.BODY, WordReader.STYLES, WordReader.NUMBERING, WordReader.BODY_RELS)

    private fun document(open: () -> InputStream): Doc {
        val found = open().use { Ooxml.textEntries(it) { name -> name in WORD_PARTS } }
        val xml = found[WordReader.BODY]
            ?: return Doc(emptyList(), note = "No document text was found inside this file.")
        val rels = Ooxml.rels(found[WordReader.BODY_RELS], WordReader.BODY)
        val (media, refused) = open().use { Ooxml.mediaEntries(it, Ooxml.imageTargets(rels)) }
        val (styles, base) = WordReader.styles(found[WordReader.STYLES])
        val parts = WordParts(
            styles = styles,
            numbering = WordReader.numbering(found[WordReader.NUMBERING]),
            rels = rels,
            media = media,
            baseHalfPoints = base.takeIf { it > 0 } ?: 22,
        )
        return Doc(WordReader.blocks(xml, parts), note = tooLarge(refused))
    }

    /** `word/document.xml` into blocks, with nothing beside it. For tests and fallbacks. */
    fun wordBlocks(xml: String): List<Block> = WordReader.blocks(xml)

    /** `Heading3`, `heading 3` and `Title` all mean something. Anything else is body text. */
    fun headingLevel(style: String): Int = WordReader.headingLevel(style)

    // ── PowerPoint ────────────────────────────────────────────────────────────────

    private fun wantedForSlides(name: String): Boolean =
        name == SlideReader.PRESENTATION || name == SlideReader.PRESENTATION_RELS ||
            (name.startsWith("ppt/slides/") && (name.endsWith(".xml") || name.endsWith(".xml.rels"))) ||
            (name.startsWith("ppt/notesSlides/notesSlide") && name.endsWith(".xml"))

    private fun slides(open: () -> InputStream): Doc {
        val found = open().use { Ooxml.textEntries(it, ::wantedForSlides) }
        val slideNames = found.keys.filter { it.startsWith(SlideReader.SLIDE_PREFIX) && it.endsWith(".xml") }
        if (slideNames.isEmpty()) return Doc(emptyList(), note = "No slides were found inside this file.")

        val deckRels = Ooxml.rels(found[SlideReader.PRESENTATION_RELS], SlideReader.PRESENTATION)
        val listed = runCatching { SlideReader.slideIds(found[SlideReader.PRESENTATION]) }.getOrDefault(emptyList())
            .mapNotNull { deckRels[it]?.target }
        val ordered = SlideReader.order(slideNames, listed)

        val relsBySlide = ordered.associateWith { Ooxml.rels(found[Ooxml.relsPathOf(it)], it) }
        val wanted = relsBySlide.values.flatMapTo(HashSet()) { Ooxml.imageTargets(it) }
        val (media, refused) = open().use { Ooxml.mediaEntries(it, wanted) }

        val out = ArrayList<Block>()
        for ((index, name) in ordered.withIndex()) {
            val xml = found.getValue(name)
            val rels = relsBySlide.getValue(name)
            val blocks = runCatching { SlideReader.blocks(xml, SlideParts(rels, media)) }
                .getOrElse { listOf(Block.Note("This slide could not be read.")) }
            // Numbered by position in the deck, which is the number PowerPoint shows - the file
            // name is only the order the slides were created in.
            out.add(
                Block.Slide(
                    index + 1,
                    (blocks.firstOrNull() as? Block.Para)?.text.orEmpty(),
                    hidden = SlideReader.isHidden(xml),
                ),
            )
            out.addAll(blocks)
            val notesPart = rels.values.firstOrNull { it.type == "notesSlide" && !it.external }?.target
            val notes = notesPart?.let { found[it] }?.let { runCatching { SlideReader.notes(it) }.getOrNull() }
            if (!notes.isNullOrBlank()) out.add(Block.SlideNotes(notes))
        }
        return Doc(out, note = tooLarge(refused))
    }

    /**
     * Slide entries in the order a human would read them, when the deck's own list is missing.
     *
     * BY NUMBER, not by zip order. A zip makes no ordering guarantee at all, and PowerPoint
     * writes `slide10.xml` before `slide2.xml` often enough that this is not theoretical - a
     * deck that reads 1, 10, 11, 2 is the kind of wrong that looks like data loss.
     */
    fun slideOrder(names: Collection<String>): List<String> = SlideReader.order(names)

    /** One slide's XML into blocks, with nothing beside it. For tests and fallbacks. */
    fun slideBlocks(xml: String): List<Block> = SlideReader.blocks(xml)

    private fun tooLarge(refused: Int): String? = when (refused) {
        0 -> null
        1 -> "One picture is too large to show here."
        else -> "$refused pictures are too large to show here."
    }
}
