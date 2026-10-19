package dev.niccc2007.filet.docs

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * What DOCX and PPTX have in common: a zip of XML parts tied together by relationship files.
 *
 * Shared by the Word and slide readers so the two cannot disagree about how a relationship
 * target resolves, how big a part may be, or how XML is parsed safely.
 */
internal object Ooxml {

    /** Sixteen megabytes of XML, which is a document of several hundred pages. */
    const val MAX_PART_BYTES = 16 * 1024 * 1024

    /**
     * The most a single picture may weigh before it is named instead of drawn. A phone photo
     * pasted into a document is 3-6 MB; past this it is a print master, and decoding it to show
     * at 400 dp is memory spent on nothing.
     */
    const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

    /** And all of them together, so a hundred-photo deck cannot take the heap with it. */
    const val MAX_MEDIA_TOTAL = 48 * 1024 * 1024

    /** A relationship: where an `r:id` in a part points. */
    data class Rel(val target: String, val type: String, val external: Boolean)

    /**
     * Read the entries whose names match, as strings.
     *
     * One pass over a `ZipInputStream` rather than a random-access read of the central
     * directory: the parts wanted are named, and a stream means a 200 MB presentation does not
     * have to fit anywhere.
     */
    fun textEntries(stream: InputStream, want: (String) -> Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val next = zip.nextEntry ?: break
                if (next.isDirectory || !want(next.name)) continue
                // Capped here rather than in the caller, where it would be a comment: a malformed
                // or hostile file can declare an entry far larger than anything a reader should
                // hold, and this is read into a String.
                out[next.name] = String(zip.bounded(MAX_PART_BYTES) ?: continue, Charsets.UTF_8)
            }
        }
        return out
    }

    /**
     * Read the named binary parts - the pictures - within the size budget.
     *
     * A second pass, because which pictures are wanted is only known once the relationship
     * files have been read, and a zip makes no promise that those come first.
     *
     * @return the parts that fitted, plus how many were refused for size.
     */
    fun mediaEntries(stream: InputStream, wanted: Set<String>): Pair<Map<String, ByteArray>, Int> {
        if (wanted.isEmpty()) return emptyMap<String, ByteArray>() to 0
        val out = HashMap<String, ByteArray>()
        var total = 0L
        var refused = 0
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val next = zip.nextEntry ?: break
                if (next.isDirectory || next.name !in wanted) continue
                val bytes = zip.bounded(MAX_IMAGE_BYTES)
                if (bytes == null || total + bytes.size > MAX_MEDIA_TOTAL) {
                    refused++
                    continue
                }
                total += bytes.size
                out[next.name] = bytes
            }
        }
        return out to refused
    }

    /** The whole of the current entry, or null when it is larger than [limit]. */
    private fun InputStream.bounded(limit: Int): ByteArray? {
        val buffer = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Where the relationships of [part] live: `word/document.xml` -> `word/_rels/document.xml.rels`. */
    fun relsPathOf(part: String): String {
        val slash = part.lastIndexOf('/')
        return if (slash < 0) "_rels/$part.rels" else part.substring(0, slash) + "/_rels/" + part.substring(slash + 1) + ".rels"
    }

    /**
     * A relationship target, resolved against the folder of the part that declares it.
     *
     * `../media/image1.png` from `ppt/slides/slide1.xml` is `ppt/media/image1.png`, and a target
     * starting with `/` is from the root of the package. Producers use all three spellings, and
     * getting one wrong is a picture that silently never appears.
     */
    fun resolve(fromPart: String, target: String): String {
        val raw = target.replace('\\', '/')
        val base = if (raw.startsWith("/")) {
            emptyList()
        } else {
            fromPart.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        }
        val out = ArrayList(base)
        for (seg in raw.trimStart('/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return out.joinToString("/")
    }

    /** A `.rels` file into `rId -> Rel`, with internal targets already resolved for [ofPart]. */
    fun rels(xml: String?, ofPart: String): Map<String, Rel> {
        if (xml.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, Rel>()
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
                if (local(qName, localName) != "Relationship") return
                val id = attr(a, "Id") ?: return
                val target = attr(a, "Target") ?: return
                val external = attr(a, "TargetMode").equals("External", ignoreCase = true)
                val type = attr(a, "Type").orEmpty().substringAfterLast('/')
                out[id] = Rel(if (external) target else resolve(ofPart, target), type, external)
            }
        })
        return out
    }

    /** The internal picture parts a relationship map points at. */
    fun imageTargets(rels: Map<String, Rel>): Set<String> =
        rels.values.filter { !it.external && it.type == "image" }.mapTo(HashSet()) { it.target }

    fun parse(xml: String, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        // Namespaces OFF on purpose. These files come from Word, LibreOffice and Google Docs
        // and their prefixes are not stable between them; a namespace-aware parse then fails
        // on a document a person can plainly read. Matching the local name is what every
        // lightweight reader of these formats does.
        factory.isNamespaceAware = false
        // External entities are refused. A document is untrusted input, and an XML parser that
        // resolves entities for one is the XXE hole - it would let a .docx read a file off the
        // phone or make a network request by being opened.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newSAXParser().parse(xml.byteInputStream(Charsets.UTF_8), handler)
    }

    /**
     * `w:pStyle` to `pStyle`.
     *
     * With namespaces off SAX reports the whole `w:pStyle` as the qualified name and leaves the
     * local name empty, so both are consulted and the prefix is stripped.
     */
    fun local(qName: String?, localName: String?): String {
        val n = qName?.takeIf { it.isNotEmpty() } ?: localName ?: return ""
        val colon = n.indexOf(':')
        return if (colon < 0) n else n.substring(colon + 1)
    }

    /** An attribute by local name, whatever prefix it happens to carry. */
    fun attr(a: Attributes?, name: String): String? {
        val attrs = a ?: return null
        for (i in 0 until attrs.length) {
            if (local(attrs.getQName(i), attrs.getLocalName(i)) == name) return attrs.getValue(i)
        }
        return null
    }

    /**
     * The relationship id an element points through: its PREFIXED `r:id`.
     *
     * Bug identified here: [attr] matches by local name, and `p:sldId` carries both `id="256"`
     * (the slide's own number) and `r:id="rId2"` (the relationship). Both are `id` once the
     * prefix is stripped, the plain one comes first, so every slide id resolved to nothing and
     * deck order silently fell back to file names. A relationship id is always namespaced, so
     * the prefixed one is the one asked for; an unprefixed `id` is never it.
     */
    fun relId(a: Attributes?): String? {
        val attrs = a ?: return null
        for (i in 0 until attrs.length) {
            val q = attrs.getQName(i).orEmpty()
            if (q.contains(':') && q.substringAfter(':') == "id") return attrs.getValue(i)
        }
        return null
    }

    /**
     * An OOXML on/off property. `w:val` absent means ON - that is how the format works, and
     * reading an absent attribute as false loses the bold in every document Word writes.
     */
    fun onOff(a: Attributes?): Boolean = when (attr(a, "val")) {
        null, "1", "true", "on" -> true
        else -> false
    }

    /** `RRGGBB` into 0xFFRRGGBB, or 0 for `auto`, a theme reference or anything malformed. */
    fun hexColor(value: String?): Long {
        val v = value?.trim() ?: return 0L
        if (v.length != 6) return 0L
        val rgb = v.toLongOrNull(16) ?: return 0L
        return 0xFF000000L or rgb
    }

    /** The file extension of a part, lower case, for naming a picture format. */
    fun extensionOf(part: String): String = part.substringAfterLast('.', "").lowercase()
}
