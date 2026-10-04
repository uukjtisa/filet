package dev.niccc2007.filet.metadata

/**
 * Document properties in an Office Open XML package: `.docx`, `.xlsx`, `.pptx` and their macro
 * and template variants.
 *
 * ## Why this one is easy and the others were not
 *
 * A `.docx` is a zip, and its properties are a small XML file inside it at `docProps/core.xml`.
 * There are no internal offsets, no checksums over the whole thing and no sample tables - the only
 * machinery is the zip, and [Zip] already handles replacing a single entry without re-compressing
 * the rest. The names are Dublin Core, which is why a Word title and a PDF title are spelled the
 * same way.
 *
 * ## The edit is textual, deliberately
 *
 * `core.xml` is rewritten by replacing one element's text rather than by parsing the document into
 * a tree and serialising it back. A tree round-trip rewrites the whole file: attribute order, the
 * declaration, self-closing style, whitespace. None of that is wrong by the specification, and all
 * of it shows up as a large diff in a file somebody may have under version control. One element
 * changed is one element changed.
 *
 * ## What is refused
 *
 * A package with no `docProps/core.xml` at all. Creating one means also adding an override to
 * `[Content_Types].xml` and a relationship to `_rels/.rels`, and a package with two of those three
 * is a document Word offers to repair. Every file Office writes has all three already.
 */
object Ooxml {

    const val CORE = "docProps/core.xml"

    /** Display label to the qualified element name, in the order the properties pane shows them. */
    val FIELDS: List<Pair<String, String>> = listOf(
        "Title" to "dc:title",
        "Subject" to "dc:subject",
        "Author" to "dc:creator",
        "Keywords" to "cp:keywords",
        "Description" to "dc:description",
        "Last modified by" to "cp:lastModifiedBy",
        "Revision" to "cp:revision",
        "Category" to "cp:category",
        "Created" to "dcterms:created",
        "Modified" to "dcterms:modified",
    )

    private val BY_LABEL = FIELDS.associate { (label, tag) -> label.lowercase() to tag }
    private val BY_TAG = FIELDS.associate { (label, tag) -> tag to label }

    /** The two that are timestamps, and carry a type attribute saying so. */
    private val DATED = setOf("dcterms:created", "dcterms:modified")

    private val EXTENSIONS = setOf(
        "docx", "docm", "dotx", "dotm",
        "xlsx", "xlsm", "xltx", "xltm",
        "pptx", "pptm", "potx", "ppsx",
    )

    fun handles(ext: String): Boolean = ext.lowercase().removePrefix(".") in EXTENSIONS

    fun tagFor(label: String): String? = BY_LABEL[label.trim().lowercase()]

    fun labelFor(tag: String): String? = BY_TAG[tag]

    fun isOoxml(b: ByteArray): Boolean =
        Zip.isZip(b) && Zip.entries(b)?.any { it.name == "[Content_Types].xml" } == true

    fun core(b: ByteArray): String? = Zip.read(b, CORE)?.toString(Charsets.UTF_8)

    fun read(b: ByteArray): List<Pair<String, String>> {
        val xml = core(b) ?: return emptyList()
        return FIELDS.mapNotNull { (label, tag) ->
            val value = textOf(xml, tag) ?: return@mapNotNull null
            if (value.isBlank()) null else label to value
        }
    }

    /**
     * Where the element called [tag] opens, or null if the document has not got one.
     *
     * The loop is the whole point. `<dc:title` is a prefix of `<dc:titleAlternative`, so a plain
     * `indexOf` can land on the wrong element - and stopping there reports the property as absent
     * while it is sitting further down the file. Found by the test that asks for exactly that.
     */
    private fun openingOf(xml: String, tag: String): Int? {
        var from = 0
        while (true) {
            val at = xml.indexOf("<$tag", from)
            if (at < 0) return null
            from = at + 1
            val afterName = at + tag.length + 1
            // The character after the name has to end it, or this is a longer name that merely
            // begins the same way.
            if (afterName >= xml.length || xml[afterName] in " \t\r\n>/") return at
        }
    }

    /**
     * The text content of one element.
     *
     * Matched on the qualified name as written, because that is what the file contains: Office
     * always writes `dc:title`, and a package that used a different prefix for the same namespace
     * would be legal XML that this does not claim to read.
     */
    fun textOf(xml: String, tag: String): String? {
        val open = openingOf(xml, tag) ?: return null
        val gt = xml.indexOf('>', open)
        if (gt < 0) return null
        if (xml[gt - 1] == '/') return "" // an empty element, which is a property that is not set
        val close = xml.indexOf("</$tag>", gt)
        if (close < 0) return null
        return unescape(xml.substring(gt + 1, close))
    }

    /** Set one element's text, creating the element if the document has not got it. */
    fun withText(xml: String, tag: String, value: String): String? {
        val body = escape(value)
        val open = openingOf(xml, tag)
        if (open != null) {
            val gt = xml.indexOf('>', open)
            if (gt < 0) return null
            return if (xml[gt - 1] == '/') {
                // An empty element has to be expanded into a pair before it can hold text.
                val attrs = xml.substring(open + tag.length + 1, gt - 1).trim()
                val head = if (attrs.isEmpty()) "<$tag>" else "<$tag $attrs>"
                xml.substring(0, open) + head + body + "</$tag>" + xml.substring(gt + 1)
            } else {
                val close = xml.indexOf("</$tag>", gt)
                if (close < 0) return null
                xml.substring(0, gt + 1) + body + xml.substring(close)
            }
        }
        // Appended just before the end of the root element. The order of these properties is not
        // significant to any reader, and inserting at a guessed position is how a valid document
        // becomes an invalid one.
        val end = xml.lastIndexOf("</cp:coreProperties>")
        if (end < 0) return null
        val attrs = if (tag in DATED) " xsi:type=\"dcterms:W3CDTF\"" else ""
        return xml.substring(0, end) + "<$tag$attrs>" + body + "</$tag>" + xml.substring(end)
    }

    fun put(original: ByteArray, label: String, value: String): ByteArray? {
        val tag = tagFor(label) ?: return null
        val xml = core(original) ?: return null
        val next = withText(xml, tag, value) ?: return null
        return Zip.replace(original, CORE, next.toByteArray(Charsets.UTF_8))
    }

    /**
     * Only the five that matter inside element text.
     *
     * The apostrophe and the quote are left alone on purpose: they are only special inside an
     * attribute value, and escaping them in text produces a title that reads `someone&apos;s` in
     * every editor that shows the XML.
     */
    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // Last, so an escaped `&amp;lt;` comes back as the text `&lt;` rather than as a `<`.
        .replace("&amp;", "&")
}
