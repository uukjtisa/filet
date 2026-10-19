package dev.niccc2007.filet.docs

import java.io.FilterInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/**
 * XLSX and ODS, read without a dependency.
 *
 * ## Why not Apache POI
 *
 * The same reasons `OfficeReader` gives, and one more: POI's spreadsheet model builds every cell
 * of every sheet as an object graph before anything can be shown, which on a 100,000-row export
 * is the app being killed rather than slow. A spreadsheet is a ZIP of XML parts, both platforms
 * ship a SAX parser, and the parts that matter are four. This file is those four parts.
 *
 * ## The parts, and the trap in each
 *
 * - `xl/workbook.xml` lists the sheets by NAME in tab order, each with a relationship id. The
 *   sheet's actual part is found through `xl/_rels/workbook.xml.rels`, never by guessing
 *   `sheet1.xml`: the numbering in part names is the order sheets were CREATED, so a workbook
 *   whose tabs were reordered or deleted has `sheet3.xml` as its first tab.
 * - `xl/sharedStrings.xml` holds the text. A cell with `t="s"` contains an INDEX into it, and a
 *   reader that renders `<v>` shows every text cell as a number.
 * - `xl/styles.xml` holds the number formats. A date is a number with a format applied, so a
 *   date cell is `46095` until `cellXfs` and `numFmts` are resolved. See [CellFormat].
 * - `xl/worksheets/sheetN.xml` holds the cells, SPARSE and addressed. A row may jump from A to
 *   F, and only the `r` attribute says so; laying cells out in document order shifts every
 *   column after a gap one place left, which looks right on a dense test sheet and is wrong on
 *   most real ones. See [CellRef].
 *
 * ## Memory
 *
 * Every part that can be large is STREAMED through SAX, never read into a string: the sheet and
 * the shared strings both. What is kept is capped by [SheetLimits], and a cap that is hit is
 * said in the sheet's note - a sheet that is silently missing its bottom half reads as a sheet
 * with no bottom half.
 *
 * ## What is out of scope, and why
 *
 * `.xls` is the binary format from before 2007: a FAT-style compound file holding BIFF8 records
 * with strings split across continuation records. It shares nothing with this file but the
 * word "spreadsheet", and reading it is a reader of its own. Recognised and named here - see
 * [SheetProblem.LEGACY_XLS] - rather than registered and failing.
 */
object SheetReader {

    /**
     * Open a workbook.
     *
     * @param open a fresh stream over the file, each time it is called. An XLSX is read in two
     *   passes - the workbook, then whichever sheet is on screen - and a zip stream is one pass.
     * @param extension only consulted to decide what to SAY about a file that is not a
     *   spreadsheet. What a file is gets decided by its bytes.
     */
    fun read(open: () -> InputStream, extension: String = "xlsx"): SheetOpen = try {
        when (sniff(open)) {
            Magic.ZIP -> readZip(open, extension.lowercase(Locale.US))
            Magic.COMPOUND -> if (isEncryptedPackage(open)) {
                SheetOpen.Failed(SheetProblem.ENCRYPTED)
            } else {
                SheetOpen.Failed(SheetProblem.LEGACY_XLS)
            }
            Magic.OTHER -> SheetOpen.Failed(SheetProblem.NOT_A_SPREADSHEET)
        }
    } catch (e: Exception) {
        SheetOpen.Failed(SheetProblem.DAMAGED, e.message ?: e::class.java.simpleName)
    }

    // ── what the bytes are ────────────────────────────────────────────────────────

    private enum class Magic { ZIP, COMPOUND, OTHER }

    private fun sniff(open: () -> InputStream): Magic {
        val head = ByteArray(8)
        val n = open().use { readFully(it, head) }
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()
        ) return Magic.ZIP
        if (n == 8 && head.contentEquals(OLE_MAGIC)) return Magic.COMPOUND
        return Magic.OTHER
    }

    /** The compound-file signature, shared by `.xls` AND by every password-protected OOXML. */
    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    /**
     * Whether a compound file is an encrypted OOXML package rather than a legacy `.xls`.
     *
     * Both start with the same eight bytes. Excel encrypts an `.xlsx` by wrapping it in a
     * compound file with a stream named `EncryptedPackage`, and that name sits in the directory
     * sector near the front as UTF-16. Looking for it is a byte search; the alternative of
     * walking the compound file's FAT to list its streams is a parser, for one bit of answer.
     */
    private fun isEncryptedPackage(open: () -> InputStream): Boolean {
        val needle = "EncryptedPackage".toByteArray(Charsets.UTF_16LE)
        val window = ByteArray(1 shl 20)
        val n = open().use { readFully(it, window) }
        outer@ for (i in 0..n - needle.size) {
            for (j in needle.indices) if (window[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val r = input.read(into, total, into.size - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    // ── the first pass ────────────────────────────────────────────────────────────

    private fun readZip(open: () -> InputStream, extension: String): SheetOpen {
        val small = HashMap<String, String>()
        var strings: SharedStrings? = null
        var ods: OdsHandler? = null
        var odsError: Exception? = null
        var mimetype: String? = null

        ZipInputStream(open().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                val base = name.substringAfterLast('/')
                when {
                    name == "mimetype" -> mimetype = boundedText(zip, 1024).trim()
                    base.endsWith(".rels") || name == "META-INF/manifest.xml" ->
                        small[name] = boundedText(zip, SMALL_PART_BYTES)
                    base.equals("workbook.xml", true) || base.equals("styles.xml", true) ->
                        small[name] = boundedText(zip, SMALL_PART_BYTES)
                    base.equals("sharedStrings.xml", true) -> {
                        val h = SharedStringsHandler()
                        parseStream(zip, h)
                        strings = h.result()
                    }
                    // Only for a file that says it is a spreadsheet, or has the name of one with
                    // no mimetype to say otherwise. A text document's content.xml is the same
                    // part name and has no tables in it worth walking.
                    name == "content.xml" &&
                        (mimetype == ODS_MIME || (mimetype == null && extension == "ods")) -> {
                        // Held rather than thrown: an encrypted ODS fails here as a syntax error,
                        // and the manifest that says it is encrypted usually comes AFTER.
                        val h = OdsHandler()
                        try {
                            parseStream(zip, h)
                            ods = h
                        } catch (e: Exception) {
                            odsError = e
                        }
                    }
                }
            }
        }

        // ODF encrypts each part separately and says so in the manifest. The parts themselves
        // are then noise, which a parser reports as a syntax error about a document that is
        // perfectly sound.
        if (small["META-INF/manifest.xml"]?.contains("encryption-data") == true) {
            return SheetOpen.Failed(SheetProblem.ENCRYPTED)
        }
        odsError?.let { throw it }
        ods?.let { return odsBook(it) }
        if (mimetype == ODS_MIME) return SheetOpen.Failed(SheetProblem.NO_SHEETS)
        return xlsxBook(open, small, strings)
    }

    private const val ODS_MIME = "application/vnd.oasis.opendocument.spreadsheet"

    /** Rels, the workbook part and styles. Small in every real file; capped for the rest. */
    private const val SMALL_PART_BYTES = 16 * 1024 * 1024

    // ── XLSX ──────────────────────────────────────────────────────────────────────

    private fun xlsxBook(
        open: () -> InputStream,
        parts: Map<String, String>,
        strings: SharedStrings?,
    ): SheetOpen {
        // The package rels name the workbook part. It is `xl/workbook.xml` in practice, and
        // reading it from the rels costs nothing and copes with the producers that differ.
        val root = parts["_rels/.rels"]?.let { relationships(it, "") }
        val bookPath = root?.values?.firstOrNull { it.type.endsWith("/officeDocument") }?.target
            ?: "xl/workbook.xml"
        val bookXml = parts.entries.firstOrNull { it.key.equals(bookPath, true) }?.value
            ?: return SheetOpen.Failed(SheetProblem.NOT_A_SPREADSHEET)

        val dir = bookPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val relsPath = dir + "_rels/" + bookPath.substringAfterLast('/') + ".rels"
        val rels = parts.entries.firstOrNull { it.key.equals(relsPath, true) }?.value
            ?.let { relationships(it, dir) }
            .orEmpty()

        val book = WorkbookHandler().also { parse(bookXml, it) }
        val stylesPath = rels.values.firstOrNull { it.type.endsWith("/styles") }?.target
        val styles = stylesPath?.let { p -> parts.entries.firstOrNull { it.key.equals(p, true) }?.value }
            ?.let { xml -> StylesHandler().also { parse(xml, it) }.result() }
            ?: Styles.NONE

        if (book.sheets.isEmpty()) return SheetOpen.Failed(SheetProblem.NO_SHEETS)

        val listed = book.sheets.map { s ->
            val rel = rels[s.relId]
            Triple(
                SheetInfo(s.name, hidden = s.hidden, chart = rel?.type?.endsWith("/chartsheet") == true),
                rel?.target,
                s.name,
            )
        }
        val context = XlsxContext(strings ?: SharedStrings.EMPTY, styles, book.date1904)
        return SheetOpen.Ready(
            Workbook(listed.map { it.first }) { index ->
                val (info, part, name) = listed[index]
                when {
                    info.chart -> Sheet(name, emptyList(), 0, note = "This is a chart sheet. Charts are not drawn here.")
                    part == null -> Sheet(name, emptyList(), 0, note = "This sheet's part is missing from the file.")
                    else -> loadSheet(open, part, name, context)
                }
            },
        )
    }

    /** What every sheet of one workbook is read against. */
    internal class XlsxContext(
        val strings: SharedStrings,
        val styles: Styles,
        val date1904: Boolean,
    )

    private fun loadSheet(open: () -> InputStream, part: String, name: String, context: XlsxContext): Sheet {
        ZipInputStream(open().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // Case-insensitive: OOXML part names are, and a producer that writes
                // `Sheet1.xml` against a rel saying `sheet1.xml` is within the format.
                if (!entry.name.equals(part, true)) continue
                val h = SheetHandler(name, context)
                parseStream(zip, h)
                return h.result()
            }
        }
        return Sheet(name, emptyList(), 0, note = "This sheet's part is missing from the file.")
    }

    /**
     * Read one sheet's XML. The streaming path above is what the app uses; this is the same
     * handler over a string, so a sheet can be tested without building a zip around it.
     */
    internal fun sheetFromXml(
        xml: String,
        strings: List<String> = emptyList(),
        styles: Styles = Styles.NONE,
        date1904: Boolean = false,
        name: String = "Sheet1",
    ): Sheet {
        val h = SheetHandler(name, XlsxContext(SharedStrings(strings, false), styles, date1904))
        parse(xml, h)
        return h.result()
    }

    internal data class Rel(val type: String, val target: String)

    /** A rels part as id to (type, resolved target). Targets resolve against [dir]. */
    internal fun relationships(xml: String, dir: String): Map<String, Rel> {
        val out = HashMap<String, Rel>()
        parse(xml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
                if (local(qName, localName) != "Relationship") return
                if (attr(a, "TargetMode") == "External") return
                val id = attr(a, "Id") ?: return
                val target = attr(a, "Target") ?: return
                out[id] = Rel(attr(a, "Type").orEmpty(), resolvePart(dir, target))
            }
        })
        return out
    }

    /**
     * A rel target as a zip entry name.
     *
     * Relative to the source part's folder unless it starts with `/`, may climb with `..`, and
     * may be percent-encoded - a sheet part with a space in its name is written `%20`.
     */
    internal fun resolvePart(dir: String, target: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(target.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(target)
        val joined = if (decoded.startsWith("/")) decoded.drop(1) else dir + decoded
        val out = ArrayDeque<String>()
        for (seg in joined.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> out.removeLastOrNull()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/")
    }

    private class WorkbookHandler : DefaultHandler() {
        data class Entry(val name: String, val relId: String, val hidden: Boolean)

        val sheets = ArrayList<Entry>()
        var date1904 = false

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            when (local(qName, localName)) {
                "workbookPr" -> date1904 = attr(a, "date1904").let { it == "1" || it == "true" }
                // `r:id`, whose local name is `id`. `sheetId` is a different attribute - the
                // creation number - and reading it as the rel id is the guessing this avoids.
                "sheet" -> sheets.add(
                    Entry(
                        name = attr(a, "name").orEmpty(),
                        relId = attr(a, "id").orEmpty(),
                        hidden = attr(a, "state").let { it == "hidden" || it == "veryHidden" },
                    ),
                )
            }
        }
    }

    // ── shared strings ────────────────────────────────────────────────────────────

    /**
     * The shared-strings table.
     *
     * @param clipped true when [SheetLimits.MAX_STRING_CHARS] was reached and later strings were
     *   not kept. A cell pointing past the kept ones then shows [CLIPPED] rather than nothing,
     *   and the sheet says why.
     */
    internal class SharedStrings(private val items: List<String?>, val clipped: Boolean) {
        val size: Int get() = items.size

        /** The text at [index], [CLIPPED] if it was not kept, or null for a bad index. */
        operator fun get(index: Int): String? =
            if (index in items.indices) items[index] ?: CLIPPED else null

        companion object {
            val EMPTY = SharedStrings(emptyList(), false)
            const val CLIPPED = "…"
        }
    }

    internal fun sharedStrings(xml: String): SharedStrings =
        SharedStringsHandler().also { parse(xml, it) }.result()

    private class SharedStringsHandler : DefaultHandler() {
        private val items = ArrayList<String?>()
        private var sb = StringBuilder()
        private var inT = false
        private var inPhonetic = 0
        private var chars = 0L
        private var clipped = false

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            when (local(qName, localName)) {
                "si" -> sb = StringBuilder()
                // Phonetic guides on Japanese text carry their own `t` elements. They are
                // reading aids drawn above the text, not the text, and appending them doubles
                // every annotated word.
                "rPh" -> inPhonetic++
                "t" -> inT = inPhonetic == 0
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (inT && ch != null) sb.appendRaw(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (local(qName, localName)) {
                "t" -> inT = false
                "rPh" -> inPhonetic--
                "si" -> {
                    chars += sb.length
                    if (chars > SheetLimits.MAX_STRING_CHARS) {
                        clipped = true
                        items.add(null)
                    } else {
                        items.add(clip(decodeEscapes(sb.toString())))
                    }
                }
            }
        }

        fun result() = SharedStrings(items, clipped)
    }

    // ── styles ────────────────────────────────────────────────────────────────────

    /**
     * Which number format each cell style uses.
     *
     * @param xf the `numFmtId` of each entry of `cellXfs`, in order. A cell's `s` attribute is
     *   an index into it.
     * @param custom the workbook's own formats by id. Ids below 164 are built in and live in
     *   [CellFormat.builtIn]; a workbook may also redefine one of those, and its definition wins.
     */
    internal class Styles(val xf: IntArray, val custom: Map<Int, String>) {
        /** The format code for style index [s]. General when anything is missing. */
        fun code(s: Int): String {
            val id = if (s in xf.indices) xf[s] else 0
            return custom[id] ?: CellFormat.builtIn(id) ?: "General"
        }

        companion object {
            val NONE = Styles(IntArray(0), emptyMap())
        }
    }

    internal fun styles(xml: String): Styles = StylesHandler().also { parse(xml, it) }.result()

    private class StylesHandler : DefaultHandler() {
        private val custom = HashMap<Int, String>()
        private val xf = ArrayList<Int>()
        private var inCellXfs = false

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            when (local(qName, localName)) {
                "numFmt" -> {
                    val id = attr(a, "numFmtId")?.toIntOrNull() ?: return
                    custom[id] = attr(a, "formatCode").orEmpty()
                }
                "cellXfs" -> inCellXfs = true
                // ONLY under cellXfs. `cellStyleXfs` has `xf` elements of the same shape and
                // counting them shifts every index, which formats dates as currency.
                "xf" -> if (inCellXfs) xf.add(attr(a, "numFmtId")?.toIntOrNull() ?: 0)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (local(qName, localName) == "cellXfs") inCellXfs = false
        }

        fun result() = Styles(xf.toIntArray(), custom)
    }

    // ── a worksheet ───────────────────────────────────────────────────────────────

    private class SheetHandler(private val name: String, private val ctx: XlsxContext) : StoppableHandler() {
        private val rows = ArrayList<SheetRow>()
        private var cells = ArrayList<Cell>()
        private var row = -1
        private var col = -1
        private var kept = 0
        private var widest = 0
        private var beyondCols = 0
        private var frozenRows = 0
        private var frozenCols = 0
        private var declaredRows = 0
        private var stoppedAtRow = -1

        // The cell being read.
        private var type = ""
        private var style = 0
        private val value = StringBuilder()
        private val inline = StringBuilder()
        private val formula = StringBuilder()
        private var sharedIndex: String? = null
        private var sharedRef = false
        private var inV = false
        private var inF = false
        private var inInline = false
        private var inPhonetic = 0

        /** The master formula of each shared group, and the cell it was written in. */
        private val shared = HashMap<String, Triple<String, Int, Int>>()

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            when (local(qName, localName)) {
                "dimension" -> attr(a, "ref")?.substringAfter(':')?.let { CellRef.parse(it) }
                    ?.let { declaredRows = it.first + 1 }
                // Only a FROZEN pane. A split pane is two scrollable views of one sheet, which
                // is a desktop affordance and not a statement about which rows are headers.
                "pane" -> if (attr(a, "state")?.startsWith("frozen") == true) {
                    frozenCols = attr(a, "xSplit")?.toDoubleOrNull()?.toInt() ?: 0
                    frozenRows = attr(a, "ySplit")?.toDoubleOrNull()?.toInt() ?: 0
                }
                "row" -> {
                    // `r` is optional. Absent means the row after the last one.
                    row = attr(a, "r")?.toIntOrNull()?.minus(1) ?: (row + 1)
                    col = -1
                    cells = ArrayList()
                }
                "c" -> {
                    val ref = attr(a, "r")?.let { CellRef.parse(it) }
                    // THE line this reader exists for. The column comes from the reference, and
                    // only when the reference is absent - which the format allows - from the
                    // position after the previous cell.
                    col = ref?.second ?: (col + 1)
                    if (ref != null) row = ref.first
                    type = attr(a, "t").orEmpty()
                    style = attr(a, "s")?.toIntOrNull() ?: 0
                    value.setLength(0)
                    inline.setLength(0)
                    formula.setLength(0)
                    sharedIndex = null
                    sharedRef = false
                }
                "v" -> inV = true
                "f" -> {
                    inF = true
                    if (attr(a, "t") == "shared") {
                        sharedIndex = attr(a, "si")
                        sharedRef = attr(a, "ref") != null
                    }
                }
                "is" -> inInline = true
                "rPh" -> inPhonetic++
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch == null) return
            when {
                inV -> value.appendRaw(ch, start, length)
                inF -> formula.appendRaw(ch, start, length)
                inInline && inPhonetic == 0 -> inline.appendRaw(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (local(qName, localName)) {
                "v" -> inV = false
                "f" -> inF = false
                "is" -> inInline = false
                "rPh" -> inPhonetic--
                "c" -> finishCell()
                "row" -> if (cells.isNotEmpty()) {
                    cells.sortBy { it.col }
                    rows.add(SheetRow(row, cells))
                    cells = ArrayList()
                }
            }
        }

        private fun finishCell() {
            val f = formulaText()
            val cell = CellValue.xlsx(type, value.toString(), inline.toString(), style, f, ctx)
                ?: return
            if (col >= SheetLimits.MAX_COLS) {
                beyondCols++
                return
            }
            cells.add(cell.copy(col = col))
            if (col + 1 > widest) widest = col + 1
            if (++kept >= SheetLimits.MAX_CELLS) {
                stoppedAtRow = row
                stop()
            }
        }

        /**
         * The formula as written in this cell.
         *
         * A filled-down column stores its formula ONCE, on the first cell, and every cell below
         * says only "same as group 0". Taken literally that leaves most formula cells in a real
         * sheet with no formula at all, so the master's text is shifted by this cell's distance
         * from it - which is what the desktop shows in its formula bar.
         */
        private fun formulaText(): String? {
            val own = formula.toString()
            val si = sharedIndex
            if (si != null) {
                if (sharedRef || own.isNotEmpty()) {
                    shared[si] = Triple(own, row, col)
                    return own.ifEmpty { null }
                }
                val (master, mr, mc) = shared[si] ?: return null
                return CellRef.shiftFormula(master, row - mr, col - mc)
            }
            return own.ifEmpty { null }
        }

        fun result(): Sheet {
            if (cells.isNotEmpty()) {
                cells.sortBy { it.col }
                rows.add(SheetRow(row, cells))
            }
            // Producers write rows in order; nothing in the format promises it.
            rows.sortBy { it.index }
            val notes = ArrayList<String>()
            if (stoppedAtRow >= 0) {
                notes += if (declaredRows > stoppedAtRow + 1) {
                    "Showing rows 1–${stoppedAtRow + 1} of ${"%,d".format(declaredRows)}. " +
                        "The rest is not loaded, to keep within the phone's memory."
                } else {
                    "Showing rows 1–${stoppedAtRow + 1}. Later rows are not loaded, to keep within the phone's memory."
                }
            }
            if (beyondCols > 0) {
                notes += "$beyondCols cells past column ${CellRef.name(SheetLimits.MAX_COLS - 1)} are not shown."
            }
            if (ctx.strings.clipped) notes += "Some text in this workbook was too long to load and shows as …"
            return Sheet(
                name = name,
                rows = rows,
                columns = widest,
                frozenRows = frozenRows,
                frozenCols = frozenCols,
                note = notes.joinToString(" ").ifEmpty { null },
            )
        }
    }

    // ── ODS ───────────────────────────────────────────────────────────────────────

    private fun odsBook(h: OdsHandler): SheetOpen {
        val sheets = h.result()
        if (sheets.isEmpty()) return SheetOpen.Failed(SheetProblem.NO_SHEETS)
        return SheetOpen.Ready(
            Workbook(sheets.map { SheetInfo(it.first.name, hidden = it.second) }) { sheets[it].first },
        )
    }

    /** One ODS `content.xml`, every sheet in it. For tests; the app streams the same handler. */
    internal fun odsSheets(xml: String): List<Sheet> =
        OdsHandler().also { parse(xml, it) }.result().map { it.first }

    /**
     * OpenDocument spreadsheet content.
     *
     * The same grid in a different shape, with its own version of the sparse trap: ODF does not
     * address cells at all. It REPEATS them - `table:number-columns-repeated="1020"` is how a
     * row says "and then nothing until column AMJ", and `number-rows-repeated` does the same
     * down the sheet. Counting those is what puts a cell in its column; materialising them is
     * what turns a 3-cell sheet into a million empty objects. Empty repeats move the cursor and
     * create nothing.
     *
     * Unlike XLSX, every cell carries its display text in `text:p`, already formatted by the
     * application that saved it - so dates, currency and percentages need no format engine
     * here. The typed value is kept as [Cell.raw].
     *
     * All sheets live in this one part, so the cell cap is across the whole workbook, and once
     * it is reached the parse carries on WITHOUT keeping cells, only to learn the names of the
     * remaining sheets so that none of them disappear from the tab strip.
     */
    private class OdsHandler : DefaultHandler() {
        private val sheets = ArrayList<Pair<Sheet, Boolean>>()
        private val hiddenStyles = HashSet<String>()
        private var styleName: String? = null

        private var name = ""
        private var hidden = false
        private var rows = ArrayList<SheetRow>()
        private var rowCells = ArrayList<Cell>()
        private var row = 0
        private var col = 0
        private var widest = 0
        private var rowRepeat = 1
        private var kept = 0
        private var full = false
        private var truncatedHere = false
        private var beyondCols = 0

        private var inCell = false
        private var cellRepeat = 1
        private var cellType = ""
        private var cellRaw: String? = null
        private var cellFormula: String? = null
        private val text = StringBuilder()
        private var paragraphs = 0
        private var inParagraph = 0
        private var inAnnotation = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes?) {
            when (local(qName, localName)) {
                // A hidden sheet is a table style with display off, declared before the body.
                "style" -> styleName = if (attr(a, "family") == "table") attr(a, "name") else null
                "table-properties" -> if (attr(a, "display") == "false") styleName?.let { hiddenStyles += it }
                "table" -> {
                    name = attr(a, "name").orEmpty()
                    hidden = attr(a, "style-name") in hiddenStyles
                    rows = ArrayList()
                    row = 0
                    widest = 0
                    truncatedHere = false
                    beyondCols = 0
                }
                "table-row" -> {
                    rowRepeat = attr(a, "number-rows-repeated")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    rowCells = ArrayList()
                    col = 0
                }
                "table-cell", "covered-table-cell" -> {
                    inCell = true
                    cellRepeat = attr(a, "number-columns-repeated")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    cellType = attr(a, "value-type").orEmpty()
                    cellRaw = attr(a, "value") ?: attr(a, "date-value") ?: attr(a, "time-value")
                        ?: attr(a, "boolean-value")
                    cellFormula = attr(a, "formula")?.let { f ->
                        // `of:=SUM([.A1:.A3])`. The namespace prefix is noise to a reader.
                        f.replaceFirst(NAMESPACE_PREFIX, "").removePrefix("=")
                    }
                    text.setLength(0)
                    paragraphs = 0
                }
                // A comment on the cell has paragraphs of its own, which are not the cell's text.
                "annotation" -> inAnnotation++
                "p" -> if (inCell && inAnnotation == 0) {
                    inParagraph++
                    if (paragraphs++ > 0) text.append('\n')
                }
                // A run of spaces is one element with a count, because XML collapses them.
                "s" -> if (inParagraph > 0 && inAnnotation == 0) {
                    repeat((attr(a, "c")?.toIntOrNull() ?: 1).coerceIn(1, 256)) { text.append(' ') }
                }
                "tab" -> if (inParagraph > 0 && inAnnotation == 0) text.append('\t')
                "line-break" -> if (inParagraph > 0 && inAnnotation == 0) text.append('\n')
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            // Inside `text:p` only. Between the tags of a pretty-printed file there is
            // indentation, and it is not the cell's text.
            if (inParagraph > 0 && inAnnotation == 0 && ch != null) text.appendRaw(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (local(qName, localName)) {
                "annotation" -> inAnnotation--
                "p" -> if (inParagraph > 0) inParagraph--
                "table-cell", "covered-table-cell" -> {
                    inCell = false
                    inParagraph = 0
                    val cell = cell()
                    if (cell == null) {
                        col += cellRepeat
                        return
                    }
                    // A repeated cell WITH content is that content several times over - a
                    // column of identical values - and is materialised, within the caps.
                    repeat(cellRepeat) {
                        if (col >= SheetLimits.MAX_COLS) {
                            beyondCols++
                        } else if (full) {
                            truncatedHere = true
                        } else {
                            rowCells.add(cell.copy(col = col))
                            if (col + 1 > widest) widest = col + 1
                        }
                        col++
                    }
                }
                "table-row" -> {
                    if (rowCells.isEmpty()) {
                        row += rowRepeat
                        return
                    }
                    repeat(rowRepeat) {
                        if (full) {
                            truncatedHere = true
                        } else {
                            rows.add(SheetRow(row, rowCells))
                            kept += rowCells.size
                            if (kept >= SheetLimits.MAX_CELLS) full = true
                        }
                        row++
                    }
                    rowCells = ArrayList()
                }
                "table" -> {
                    val notes = ArrayList<String>()
                    if (truncatedHere) {
                        notes += if (rows.isEmpty()) {
                            "This sheet is not loaded: the workbook is past the phone's memory limit by now."
                        } else {
                            "Showing rows 1–${rows.last().index + 1}. The rest is not loaded, to keep within the phone's memory."
                        }
                    }
                    if (beyondCols > 0) {
                        notes += "$beyondCols cells past column ${CellRef.name(SheetLimits.MAX_COLS - 1)} are not shown."
                    }
                    sheets.add(Sheet(name, rows, widest, note = notes.joinToString(" ").ifEmpty { null }) to hidden)
                    // After the cap a sheet that did fit still needs its own `full` decided,
                    // so the flag is not reset: the cap is the workbook's, not the sheet's.
                }
            }
        }

        private fun cell(): Cell? {
            val shown = clip(text.toString())
            val raw = cellRaw
            if (shown.isEmpty() && raw == null && cellFormula == null) return null
            val kind = when (cellType) {
                "float", "percentage", "currency" -> CellKind.NUMBER
                "date", "time" -> CellKind.DATE
                "boolean" -> CellKind.BOOL
                else -> if (shown.startsWith("#") && shown.endsWith("!")) CellKind.ERROR else CellKind.TEXT
            }
            // Some producers omit `text:p` for a number. The typed value is then all there is,
            // and General formatting is what a desktop would show for it.
            val display = when {
                shown.isNotEmpty() -> shown
                kind == CellKind.NUMBER -> raw?.toDoubleOrNull()?.let { CellFormat.general(it) } ?: raw.orEmpty()
                kind == CellKind.BOOL -> raw.orEmpty().uppercase(Locale.US)
                else -> raw.orEmpty()
            }
            return Cell(
                col = 0,
                text = display,
                kind = kind,
                raw = raw?.takeIf { it != display && (kind == CellKind.NUMBER || kind == CellKind.DATE) },
                formula = cellFormula,
            )
        }

        fun result(): List<Pair<Sheet, Boolean>> = sheets
    }

    /** `of:` or `oooc:` before a formula. Not "everything before a colon": `A1:B2` has one. */
    private val NAMESPACE_PREFIX = Regex("^[a-z]+:(?==)")

    // ── the XML, and the zip ──────────────────────────────────────────────────────

    private fun boundedText(input: InputStream, limit: Int): String {
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < limit) {
            val read = input.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, minOf(read, limit - out.size()))
        }
        return out.toString("UTF-8")
    }

    private fun factory(): SAXParserFactory {
        val factory = SAXParserFactory.newInstance()
        // Namespaces OFF, as in OfficeReader: prefixes differ between Excel, LibreOffice,
        // Google Sheets and Numbers exports, and the local name is the stable part.
        factory.isNamespaceAware = false
        // External entities refused. A workbook is untrusted input, and a parser that resolves
        // entities for one is the XXE hole - opening a spreadsheet could read a file off the
        // phone or make a network request.
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return factory
    }

    private fun parse(xml: String, handler: DefaultHandler) {
        runParse(handler) { factory().newSAXParser().parse(xml.byteInputStream(Charsets.UTF_8), handler) }
    }

    /**
     * A handler stopping itself at a cap is an exception by necessity - it is the only way out
     * of a SAX parse - and is not an error. Checked by the flag rather than the exception's
     * type, because a parser is free to wrap what a handler threw.
     */
    private inline fun runParse(handler: DefaultHandler, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            if (!(handler is StoppableHandler && handler.stopped)) throw e
        }
    }

    /**
     * Parse straight from a zip entry, without holding the entry.
     *
     * Two wrappers, each for a reason. The parser closes its input when done, and closing a
     * `ZipInputStream` mid-walk ends the walk - so the close is swallowed. And the bytes are
     * counted, because a sheet whose cells are all empty never reaches the cell cap: a zip bomb
     * of blank styled cells would otherwise inflate for as long as it liked.
     */
    private fun parseStream(input: InputStream, handler: DefaultHandler) {
        val guarded = object : FilterInputStream(input) {
            private var seen = 0L
            override fun close() = Unit
            override fun read(): Int {
                val r = super.read()
                if (r >= 0) count(1)
                return r
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val r = super.read(b, off, len)
                if (r > 0) count(r)
                return r
            }
            private fun count(n: Int) {
                seen += n
                if (seen > MAX_PART_BYTES) throw java.io.IOException("A part of this file is larger than ${MAX_PART_BYTES shr 20} MB once unpacked.")
            }
        }
        runParse(handler) { factory().newSAXParser().parse(InputSource(guarded), handler) }
    }

    /** Uncompressed bytes per part. Far past any sheet that fits in [SheetLimits.MAX_CELLS]. */
    private const val MAX_PART_BYTES = 512L * 1024 * 1024

    /** A handler that can end its own parse once it has all it will keep. */
    private abstract class StoppableHandler : DefaultHandler() {
        var stopped = false
            private set

        fun stop() {
            stopped = true
            throw SAXException("enough")
        }
    }

    /** `x:sheet` to `sheet`. Namespaces are off, so the prefix arrives in the qualified name. */
    private fun local(qName: String?, localName: String?): String {
        val n = qName?.takeIf { it.isNotEmpty() } ?: localName ?: return ""
        val colon = n.indexOf(':')
        return if (colon < 0) n else n.substring(colon + 1)
    }

    private fun attr(a: Attributes?, name: String): String? {
        val attrs = a ?: return null
        for (i in 0 until attrs.length) {
            if (local(attrs.getQName(i), attrs.getLocalName(i)) == name) return attrs.getValue(i)
        }
        return null
    }

    private fun StringBuilder.appendRaw(ch: CharArray, start: Int, length: Int) {
        // Past the per-cell cap, characters are dropped as they arrive rather than collected
        // and cut later; one cell must not be able to hold the heap.
        val room = SheetLimits.MAX_CELL_CHARS + 1 - this.length
        if (room > 0) append(ch, start, minOf(length, room))
    }

    internal fun clip(s: String): String =
        if (s.length > SheetLimits.MAX_CELL_CHARS) s.take(SheetLimits.MAX_CELL_CHARS) + "…" else s

    /**
     * `_x000D_` to a carriage return.
     *
     * OOXML escapes characters XML cannot carry as `_xHHHH_`, and Excel writes them for the line
     * breaks in any cell that was pasted from Windows text. Left alone they appear in the cell
     * as literal underscores and hex.
     */
    internal fun decodeEscapes(s: String): String {
        if (!s.contains("_x")) return s
        return ESCAPE.replace(s) { m ->
            val code = m.groupValues[1].toInt(16)
            when {
                // `_x005F_` escapes the underscore itself, so `_x005F_x0041_` is literal text.
                code == 0x5F -> "_"
                code == 0x0D -> ""
                else -> code.toChar().toString()
            }
        }
    }

    private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")
}

/** What opening a workbook came to. */
sealed interface SheetOpen {
    data class Ready(val book: Workbook) : SheetOpen
    data class Failed(val problem: SheetProblem, val detail: String = "") : SheetOpen
}

/** Each way a file can fail to be a readable workbook, with what to tell the person holding it. */
enum class SheetProblem(val title: String, val body: String) {
    ENCRYPTED(
        "This workbook is password-protected",
        "Its contents are encrypted. Open it in an app that can ask for the password.",
    ),
    LEGACY_XLS(
        "This is an old-format Excel file",
        "Files saved before Excel 2007 are a different format, which Filet does not read.",
    ),
    NOT_A_SPREADSHEET(
        "This is not a spreadsheet",
        "It has a spreadsheet's name, but nothing inside it is a workbook.",
    ),
    NO_SHEETS(
        "This workbook has no sheets",
        "The file opened, and it lists no sheets to show.",
    ),
    DAMAGED(
        "This workbook could not be read",
        "The file looks damaged or incomplete. Another app may recover more of it.",
    ),
}

/**
 * One XLSX cell's stored value as what a spreadsheet shows.
 *
 * Separate from the handler so that the type switch - the part with the shared-strings trap and
 * the date trap in it - is reachable from a test without any XML at all.
 */
internal object CellValue {

    /**
     * @return null for a cell that holds nothing, which is most `c` elements in a styled sheet:
     *   a border or a fill writes a cell with a style and no value.
     */
    fun xlsx(
        type: String,
        v: String,
        inline: String,
        style: Int,
        formula: String?,
        ctx: SheetReader.XlsxContext,
    ): Cell? {
        val cell = when (type) {
            // The trap. `v` is an INDEX, not the text.
            "s" -> {
                val i = v.trim().toIntOrNull() ?: return null
                Cell(0, ctx.strings[i] ?: "", CellKind.TEXT)
            }
            "inlineStr" -> Cell(0, SheetReader.clip(SheetReader.decodeEscapes(inline)), CellKind.TEXT)
            // A formula's string result.
            "str" -> Cell(0, SheetReader.clip(SheetReader.decodeEscapes(v)), CellKind.TEXT)
            "b" -> Cell(0, if (v.trim() == "1") "TRUE" else "FALSE", CellKind.BOOL)
            "e" -> Cell(0, v, CellKind.ERROR)
            // An ISO 8601 date, which only strict-conformance producers write.
            "d" -> Cell(0, v.replace('T', ' ').removeSuffix("Z"), CellKind.DATE)
            else -> {
                if (v.isEmpty()) {
                    // A formula never calculated, or calculated to nothing.
                    return if (formula != null) Cell(0, "", CellKind.TEXT) else null
                }
                val number = v.trim().toDoubleOrNull() ?: return Cell(0, v, CellKind.TEXT)
                val shown = CellFormat.format(number, ctx.styles.code(style), ctx.date1904)
                val general = CellFormat.general(number)
                Cell(0, shown.first, shown.second, raw = general.takeIf { it != shown.first })
            }
        }
        return if (formula != null) cell.copy(formula = formula) else cell
    }
}

/**
 * Cell references: `B4` to a row and a column, and back.
 *
 * Pure, and tested on its own, because a mistake here is not a crash - it is every value on a
 * sheet sitting one column away from its header.
 */
object CellRef {

    /** `A` to 0, `Z` to 25, `AA` to 26, `XFD` to 16383. -1 for anything that is not letters. */
    fun column(letters: String): Int {
        if (letters.isEmpty() || letters.length > 3) return -1
        var n = 0
        for (ch in letters) {
            val c = ch.uppercaseChar()
            if (c !in 'A'..'Z') return -1
            // Bijective base 26: there is no zero digit, which is why `AA` follows `Z` rather
            // than `BA`. Treating it as ordinary base 26 is the classic off-by-one past Z.
            n = n * 26 + (c - 'A' + 1)
        }
        return n - 1
    }

    /** 0 to `A`, 26 to `AA`. */
    fun name(col: Int): String {
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** `B4` or `$B$4` to (row 3, column 1). Null for anything else. */
    fun parse(ref: String): Pair<Int, Int>? {
        val m = REF.matchEntire(ref.trim()) ?: return null
        val col = column(m.groupValues[1])
        val row = m.groupValues[2].toIntOrNull() ?: return null
        if (col < 0 || row < 1) return null
        return (row - 1) to col
    }

    private val REF = Regex("""\$?([A-Za-z]{1,3})\$?(\d{1,7})""")

    /**
     * Move every relative reference in [formula] by [dRow] rows and [dCol] columns.
     *
     * What a shared formula means: `=A2*B2` written once on row 2 is `=A3*B3` on row 3. A `$`
     * pins its half of a reference and is left alone. Text in double quotes and sheet names in
     * single quotes are copied untouched, and so is anything inside `[ ]`, which is a table's
     * structured reference. A reference pushed off the grid becomes `#REF!`, which is what the
     * producing application writes in the same case.
     *
     * Whole-column and whole-row ranges (`A:A`, `1:1`) are not shifted. They appear in shared
     * formulas rarely, and getting them right needs the range operator parsed rather than
     * matched.
     */
    fun shiftFormula(formula: String, dRow: Int, dCol: Int): String {
        if (dRow == 0 && dCol == 0) return formula
        val out = StringBuilder(formula.length + 8)
        var i = 0
        while (i < formula.length) {
            val ch = formula[i]
            when {
                ch == '"' || ch == '\'' -> {
                    val end = closing(formula, i, ch)
                    out.append(formula, i, end)
                    i = end
                }
                ch == '[' -> {
                    val end = formula.indexOf(']', i).let { if (it < 0) formula.length else it + 1 }
                    out.append(formula, i, end)
                    i = end
                }
                startsRef(formula, i) -> {
                    val m = SHIFTABLE.find(formula, i)
                    if (m == null || m.range.first != i || !endsRef(formula, m.range.last + 1)) {
                        out.append(ch)
                        i++
                    } else {
                        out.append(shiftOne(m, dRow, dCol))
                        i = m.range.last + 1
                    }
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    private val SHIFTABLE = Regex("""(\$?)([A-Za-z]{1,3})(\$?)(\d{1,7})""")

    private fun shiftOne(m: MatchResult, dRow: Int, dCol: Int): String {
        val (colPin, letters, rowPin, digits) = m.destructured
        val col0 = column(letters)
        val row0 = digits.toInt()
        if (col0 < 0) return m.value
        val col = if (colPin.isEmpty()) col0 + dCol else col0
        val row = if (rowPin.isEmpty()) row0 + dRow else row0
        if (col !in 0 until 16384 || row !in 1..1_048_576) return "#REF!"
        return colPin + name(col) + rowPin + row
    }

    /** A reference cannot start inside a name: `LOG10`, `Sheet2`, `_xlfn.X1`. */
    private fun startsRef(s: String, i: Int): Boolean {
        val c = s[i]
        if (c != '$' && !c.isLetter()) return false
        if (i == 0) return true
        val p = s[i - 1]
        return !(p.isLetterOrDigit() || p == '_' || p == '.' || p == '$')
    }

    /** ...and cannot be followed by `(`, which makes it a function, or by `!`, a sheet name. */
    private fun endsRef(s: String, i: Int): Boolean {
        if (i >= s.length) return true
        val c = s[i]
        return !(c.isLetterOrDigit() || c == '_' || c == '(' || c == '!' || c == '.')
    }

    /** Past the closing quote, where a doubled quote is an escaped one. */
    private fun closing(s: String, start: Int, q: Char): Int {
        var i = start + 1
        while (i < s.length) {
            if (s[i] == q) {
                if (i + 1 < s.length && s[i + 1] == q) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return s.length
    }
}

/**
 * Excel number formats, the subset that decides what a cell reads as.
 *
 * ## What is covered
 *
 * - **Dates and times** - every `y m d h s` token, `AM/PM`, month and weekday names, elapsed
 *   `[h]:mm`, fractional seconds, and both date systems (1900 with its leap-year bug, and the
 *   1904 system older Mac workbooks use).
 * - **Numbers** - fixed decimals, thousands separators, trailing-comma scaling (`#,##0,"K"`),
 *   padding zeros, interleaved literals (`000-0000`), scientific, and fractions (`# ?/?`).
 * - **Percentages** - each `%` multiplies by 100 and prints itself.
 * - **Currency** - as literals: `$`, quoted symbols and `[$₱-3409]`-style brackets all print
 *   their symbol. The built-in currency and accounting formats (5-8, 37-44) are the en-US
 *   definitions, because the file records only the id and the symbol comes from the computer's
 *   locale.
 * - **Sections** - positive;negative;zero, with the negative section taking the absolute value,
 *   and `[<100]`-style conditions.
 *
 * ## What is not
 *
 * Colours (`[Red]`) are dropped rather than drawn. `_x` padding and `*x` fill produce nothing,
 * because they size text to a column width a phone does not share. Built-in 14, the "short date",
 * is `m/d/yyyy` - the format's own definition - where a desktop would substitute its locale.
 */
object CellFormat {

    /** The built-in format codes, by id. */
    fun builtIn(id: Int): String? = when (id) {
        0 -> "General"
        1 -> "0"
        2 -> "0.00"
        3 -> "#,##0"
        4 -> "#,##0.00"
        5 -> "\"$\"#,##0_);\\(\"$\"#,##0\\)"
        6 -> "\"$\"#,##0_);[Red]\\(\"$\"#,##0\\)"
        7 -> "\"$\"#,##0.00_);\\(\"$\"#,##0.00\\)"
        8 -> "\"$\"#,##0.00_);[Red]\\(\"$\"#,##0.00\\)"
        9 -> "0%"
        10 -> "0.00%"
        11 -> "0.00E+00"
        12 -> "# ?/?"
        13 -> "# ??/??"
        14 -> "m/d/yyyy"
        15 -> "d-mmm-yy"
        16 -> "d-mmm"
        17 -> "mmm-yy"
        18 -> "h:mm AM/PM"
        19 -> "h:mm:ss AM/PM"
        20 -> "h:mm"
        21 -> "h:mm:ss"
        22 -> "m/d/yyyy h:mm"
        // East Asian locale dates. The exact form is locale-bound; what matters is that they
        // read as dates and not as serial numbers.
        in 27..36, in 50..58 -> "m/d/yyyy"
        37 -> "#,##0_);\\(#,##0\\)"
        38 -> "#,##0_);[Red]\\(#,##0\\)"
        39 -> "#,##0.00_);\\(#,##0.00\\)"
        40 -> "#,##0.00_);[Red]\\(#,##0.00\\)"
        41 -> "_(* #,##0_);_(* \\(#,##0\\);_(* \"-\"_);_(@_)"
        42 -> "_(\"$\"* #,##0_);_(\"$\"* \\(#,##0\\);_(\"$\"* \"-\"_);_(@_)"
        43 -> "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)"
        44 -> "_(\"$\"* #,##0.00_);_(\"$\"* \\(#,##0.00\\);_(\"$\"* \"-\"??_);_(@_)"
        45 -> "mm:ss"
        46 -> "[h]:mm:ss"
        47 -> "mm:ss.0"
        48 -> "##0.0E+0"
        49 -> "@"
        else -> null
    }

    /**
     * [value] under [code], and whether that made it a date.
     *
     * Never throws: a format this does not understand falls back to General, because a number
     * shown plainly is still the right number, and a cell that fails to draw is not.
     */
    fun format(value: Double, code: String, date1904: Boolean = false): Pair<String, CellKind> =
        runCatching { formatOrThrow(value, code, date1904) }
            .getOrElse { general(value) to CellKind.NUMBER }

    private fun formatOrThrow(value: Double, code: String, date1904: Boolean): Pair<String, CellKind> {
        if (value.isNaN() || value.isInfinite()) return "#NUM!" to CellKind.ERROR
        if (code.isBlank() || code.equals("General", true)) return general(value) to CellKind.NUMBER

        val sections = splitSections(code).map { Section.of(it) }
        val (section, absolute) = pick(sections, value)
        val tokens = section.tokens
        val v = if (absolute) kotlin.math.abs(value) else value

        if (tokens.any { it is Tok.Date || it is Tok.Elapsed || it is Tok.AmPm }) {
            // Before the epoch and past 9999-12-31 a desktop prints ####. The number is the
            // more useful thing to show.
            if (v < 0 || v >= 2_958_466) return general(value) to CellKind.NUMBER
            return date(v, tokens, date1904) to CellKind.DATE
        }
        // A text-only section (`@`) applied to a number shows the number.
        if (tokens.none { it is Tok.Ph || it is Tok.General }) {
            val lit = tokens.filterIsInstance<Tok.Lit>().joinToString("") { it.s }.trim()
            return (if (lit.isEmpty() || tokens.any { it is Tok.At }) general(value) else lit) to CellKind.NUMBER
        }
        val sign = if (!absolute && v < 0) "-" else ""
        val body = number(kotlin.math.abs(v), tokens)
        val shown = (sign + body).trim()
        // A rounded-away negative - `-0.001` under `0.00` - is still written with its sign by
        // a desktop. That is a quirk worth matching; somebody comparing will see it.
        return shown to CellKind.NUMBER
    }

    /** General: the number, shortest form, at most ten significant digits. */
    fun general(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "#NUM!"
        if (value == 0.0) return "0"
        val abs = kotlin.math.abs(value)
        if (abs >= 1e11 || abs < 1e-9) {
            val s = String.format(Locale.US, "%.5E", value)
            val mantissa = s.substringBefore('E').trimEnd('0').trimEnd('.')
            val exp = s.substringAfter('E').toInt()
            return mantissa + "E" + (if (exp < 0) "-" else "+") + kotlin.math.abs(exp).toString().padStart(2, '0')
        }
        if (value == Math.rint(value)) return value.toLong().toString()
        val bd = BigDecimal(value).round(java.math.MathContext(10, RoundingMode.HALF_UP))
        return bd.stripTrailingZeros().toPlainString()
    }

    // ── sections ──

    private class Section(val tokens: List<Tok>, val condition: Pair<String, Double>?) {
        companion object {
            fun of(raw: String): Section {
                val cond = CONDITION.find(raw)?.let { it.groupValues[1] to it.groupValues[2].toDouble() }
                return Section(tokenize(raw), cond)
            }
        }
    }

    private val CONDITION = Regex("""\[(<=|>=|<>|<|>|=)(-?\d+(?:\.\d+)?)]""")

    /**
     * Which section applies, and whether it is given the absolute value.
     *
     * With conditions, the first whose condition holds; a section without one catches the rest.
     * Without, the documented rules: one section for everything; two for non-negative and
     * negative; three for positive, negative and zero. The negative section gets the absolute
     * value because it supplies its own sign - `(1,234)` is how accountants write `-1234`.
     */
    private fun pick(sections: List<Section>, v: Double): Pair<Section, Boolean> {
        if (sections.any { it.condition != null }) {
            for ((i, s) in sections.withIndex()) {
                val c = s.condition ?: return s to (i > 0 && v < 0)
                val holds = when (c.first) {
                    "<" -> v < c.second
                    ">" -> v > c.second
                    "<=" -> v <= c.second
                    ">=" -> v >= c.second
                    "=" -> v == c.second
                    else -> v != c.second
                }
                if (holds) return s to false
            }
            return sections.last() to false
        }
        return when {
            sections.size == 1 -> sections[0] to false
            v < 0 -> sections[1] to true
            v == 0.0 && sections.size >= 3 -> sections[2] to false
            else -> sections[0] to false
        }
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var quoted = false
        var bracket = false
        while (i < code.length) {
            val c = code[i]
            when {
                c == '\\' && i + 1 < code.length && !quoted -> {
                    sb.append(c).append(code[i + 1])
                    i += 2
                    continue
                }
                c == '"' -> quoted = !quoted
                !quoted && c == '[' -> bracket = true
                !quoted && c == ']' -> bracket = false
                !quoted && !bracket && c == ';' -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                    i++
                    continue
                }
            }
            sb.append(c)
            i++
        }
        out.add(sb.toString())
        return out
    }

    // ── tokens ──

    private sealed interface Tok {
        data class Lit(val s: String) : Tok
        data class Ph(val c: Char) : Tok
        data object Dot : Tok
        data object Comma : Tok
        data object Pct : Tok
        data class Exp(val plus: Boolean) : Tok
        data object Slash : Tok
        data object At : Tok
        data object General : Tok
        data class Date(val c: Char, val n: Int) : Tok
        data class Elapsed(val c: Char) : Tok
        data class AmPm(val full: Boolean, val upper: Boolean) : Tok
    }

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> {
                    val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    out.add(Tok.Lit(s.substring(i + 1, end)))
                    i = end + 1
                }
                c == '\\' -> {
                    if (i + 1 < s.length) out.add(Tok.Lit(s[i + 1].toString()))
                    i += 2
                }
                // Padding to the width of the next character, and repeat-to-fill. Both size
                // text to a desktop column, and there is no such column here.
                c == '_' || c == '*' -> i += 2
                c == '[' -> {
                    val end = s.indexOf(']', i).let { if (it < 0) s.length else it }
                    bracket(s.substring(i + 1, end))?.let { out.add(it) }
                    i = end + 1
                }
                s.regionMatches(i, "General", 0, 7, ignoreCase = true) -> {
                    out.add(Tok.General)
                    i += 7
                }
                s.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> {
                    out.add(Tok.AmPm(full = true, upper = s[i].isUpperCase()))
                    i += 5
                }
                s.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> {
                    out.add(Tok.AmPm(full = false, upper = s[i].isUpperCase()))
                    i += 3
                }
                (c == 'E' || c == 'e') && i + 1 < s.length && (s[i + 1] == '+' || s[i + 1] == '-') -> {
                    out.add(Tok.Exp(s[i + 1] == '+'))
                    i += 2
                }
                c.lowercaseChar() in "ymdhse" -> {
                    val lower = c.lowercaseChar()
                    var n = 0
                    while (i < s.length && s[i].lowercaseChar() == lower) {
                        n++
                        i++
                    }
                    // `e` is the era year, which is the year in every Gregorian locale.
                    out.add(Tok.Date(if (lower == 'e') 'y' else lower, if (lower == 'e') 4 else n))
                }
                c == '0' || c == '#' || c == '?' -> { out.add(Tok.Ph(c)); i++ }
                c == '.' -> { out.add(Tok.Dot); i++ }
                c == ',' -> { out.add(Tok.Comma); i++ }
                c == '%' -> { out.add(Tok.Pct); i++ }
                c == '/' -> { out.add(Tok.Slash); i++ }
                c == '@' -> { out.add(Tok.At); i++ }
                else -> { out.add(Tok.Lit(c.toString())); i++ }
            }
        }
        return out
    }

    /** `[$€-407]` is a currency symbol, `[h]` elapsed hours, `[Red]` a colour (dropped). */
    private fun bracket(inner: String): Tok? {
        if (inner.startsWith("$")) {
            val symbol = inner.drop(1).substringBefore('-')
            return if (symbol.isEmpty()) null else Tok.Lit(symbol)
        }
        val lower = inner.lowercase(Locale.US)
        if (lower.isNotEmpty() && lower.all { it == lower[0] } && lower[0] in "hms") return Tok.Elapsed(lower[0])
        return null
    }

    // ── dates ──

    private val MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val DAYS = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /**
     * A serial date as (year, month, day, weekday 0=Monday).
     *
     * The 1900 system counts day 1 as 1900-01-01 and, to match Lotus 1-2-3, believes in
     * 1900-02-29 as day 60. So days before it are one later than plain arithmetic says and days
     * after it are not: 61 is March 1st. Getting this wrong puts every date in the first two
     * months of 1900 off by one, and every date after them right - which is why it survives
     * testing on modern dates and must be pinned by its own test.
     */
    internal fun civil(days: Long, date1904: Boolean): IntArray {
        if (!date1904 && days == 60L) return intArrayOf(1900, 2, 29, 2)
        val d = when {
            date1904 -> LocalDate.of(1904, 1, 1).plusDays(days)
            days < 60 -> LocalDate.of(1899, 12, 31).plusDays(days)
            else -> LocalDate.of(1899, 12, 30).plusDays(days)
        }
        return intArrayOf(d.year, d.monthValue, d.dayOfMonth, d.dayOfWeek.value - 1)
    }

    private fun date(serial: Double, tokens: List<Tok>, date1904: Boolean): String {
        // Fractional seconds are shown only when asked for, and then rounding is to that
        // precision; otherwise to the second, as a desktop does - 0.99999999 of a day is
        // midnight of the next one, not 23:59:59.
        val fracDigits = fractionalSeconds(tokens)
        val unit = when (fracDigits) {
            0 -> 1000L
            1 -> 100L
            2 -> 10L
            else -> 1L
        }
        var days = kotlin.math.floor(serial).toLong()
        var ms = Math.round((serial - days) * 86_400_000.0 / unit) * unit
        if (ms >= 86_400_000L) {
            days += 1
            ms -= 86_400_000L
        }
        val (y, mo, d, wd) = civil(days, date1904)
        val h = (ms / 3_600_000L).toInt()
        val mi = ((ms / 60_000L) % 60).toInt()
        val sec = ((ms / 1000L) % 60).toInt()
        val twelve = tokens.any { it is Tok.AmPm }

        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            when (val t = tokens[i]) {
                is Tok.Lit -> out.append(t.s)
                is Tok.Date -> when (t.c) {
                    'y' -> out.append(if (t.n <= 2) (y % 100).toString().padStart(2, '0') else y.toString())
                    'd' -> out.append(
                        when (t.n) {
                            1 -> d.toString()
                            2 -> d.toString().padStart(2, '0')
                            3 -> DAYS[wd].take(3)
                            else -> DAYS[wd]
                        },
                    )
                    'h' -> {
                        val hh = if (twelve) (if (h % 12 == 0) 12 else h % 12) else h
                        out.append(if (t.n >= 2) hh.toString().padStart(2, '0') else hh.toString())
                    }
                    's' -> {
                        out.append(if (t.n >= 2) sec.toString().padStart(2, '0') else sec.toString())
                        // `ss.00`: the dot and zeros that follow are the fraction.
                        if (fracDigits > 0 && i + 1 < tokens.size && tokens[i + 1] == Tok.Dot) {
                            out.append('.').append(
                                (ms % 1000).toString().padStart(3, '0').take(fracDigits),
                            )
                            i += 1 + fracDigits
                        }
                    }
                    'm' -> out.append(
                        if (t.n <= 2 && isMinute(tokens, i)) {
                            if (t.n == 2) mi.toString().padStart(2, '0') else mi.toString()
                        } else {
                            when (t.n) {
                                1 -> mo.toString()
                                2 -> mo.toString().padStart(2, '0')
                                3 -> MONTHS[mo - 1].take(3)
                                5 -> MONTHS[mo - 1].take(1)
                                else -> MONTHS[mo - 1]
                            }
                        },
                    )
                }
                is Tok.Elapsed -> {
                    val total = days * 86_400_000L + ms
                    out.append(
                        when (t.c) {
                            'h' -> (total / 3_600_000L).toString()
                            'm' -> (total / 60_000L).toString()
                            else -> (total / 1000L).toString()
                        },
                    )
                }
                is Tok.AmPm -> {
                    val am = h < 12
                    val s = if (t.full) (if (am) "AM" else "PM") else (if (am) "A" else "P")
                    out.append(if (t.upper) s else s.lowercase(Locale.US))
                }
                // In a date, punctuation that would be numeric elsewhere is just punctuation.
                Tok.Dot -> out.append('.')
                Tok.Comma -> out.append(',')
                Tok.Slash -> out.append('/')
                Tok.Pct -> out.append('%')
                is Tok.Ph -> out.append(t.c)
                else -> Unit
            }
            i++
        }
        return out.toString().trim()
    }

    /** `.0`, `.00` or `.000` straight after a seconds token. */
    private fun fractionalSeconds(tokens: List<Tok>): Int {
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t is Tok.Date && t.c == 's' && i + 1 < tokens.size && tokens[i + 1] == Tok.Dot) {
                var n = 0
                while (i + 2 + n < tokens.size && tokens[i + 2 + n] == Tok.Ph('0')) n++
                return n.coerceAtMost(3)
            }
        }
        return 0
    }

    /**
     * Whether `m` or `mm` at [i] is minutes rather than a month.
     *
     * The format has no separate token for minutes. It is minutes when the nearest date token
     * before it is hours, or the nearest after it is seconds - `h:mm`, `mm:ss` - and a month
     * everywhere else. `mmm` and longer are always months.
     */
    private fun isMinute(tokens: List<Tok>, i: Int): Boolean {
        for (j in i - 1 downTo 0) {
            val t = tokens[j]
            if (t is Tok.Date || t is Tok.Elapsed) return (t is Tok.Date && t.c == 'h') || (t is Tok.Elapsed && t.c == 'h')
        }
        for (j in i + 1 until tokens.size) {
            val t = tokens[j]
            if (t is Tok.Date || t is Tok.Elapsed) return (t is Tok.Date && t.c == 's') || (t is Tok.Elapsed && t.c == 's')
        }
        return false
    }

    // ── numbers ──

    private fun number(abs: Double, tokens: List<Tok>): String {
        var v = abs
        repeat(tokens.count { it == Tok.Pct }) { v *= 100 }

        val first = tokens.indexOfFirst { it is Tok.Ph || it == Tok.General }
        var last = tokens.indexOfLast { it is Tok.Ph || it == Tok.General }
        // `# ?/8` ends on a written denominator, a literal, which belongs to the number.
        val slash = tokens.indexOf(Tok.Slash)
        if (slash > first) {
            val written = tokens.indexOfLast { it is Tok.Lit && it.s.isNotBlank() && it.s.all(Char::isDigit) }
            if (written > slash) last = maxOf(last, written)
        }
        // Commas straight after the last digit scale by a thousand each - `0.0,"K"` and
        // `#,##0,,` - and print nothing. Read as literals they would print a comma instead.
        var scale = last + 1
        while (scale < tokens.size && tokens[scale] == Tok.Comma) {
            v /= 1000
            scale++
        }
        val prefix = literals(tokens.subList(0, first))
        val suffix = literals(tokens.subList(scale, tokens.size))
        val zone = tokens.subList(first, last + 1)

        val body = when {
            zone.any { it == Tok.General } -> general(v)
            zone.any { it is Tok.Exp } -> scientific(v, zone)
            zone.any { it == Tok.Slash } -> fraction(v, zone)
            else -> decimal(v, zone)
        }
        return prefix + body + suffix
    }

    private fun literals(tokens: List<Tok>): String = buildString {
        for (t in tokens) when (t) {
            is Tok.Lit -> append(t.s)
            Tok.Pct -> append('%')
            Tok.Dot -> append('.')
            Tok.Comma -> append(',')
            Tok.Slash -> append('/')
            else -> Unit
        }
    }

    private fun decimal(value: Double, zone: List<Tok>): String {
        val dot = zone.indexOf(Tok.Dot)
        val intPart = if (dot < 0) zone else zone.subList(0, dot)
        val fracPart = if (dot < 0) emptyList() else zone.subList(dot + 1, zone.size)

        // Commas after the last integer digit scale by a thousand each - `#,##0,` shows
        // thousands. Commas BETWEEN digits switch on grouping. One token, two meanings.
        val lastPh = intPart.indexOfLast { it is Tok.Ph }
        val scaleBy = intPart.drop(lastPh + 1).count { it == Tok.Comma }
        val grouped = intPart.take(lastPh + 1).any { it == Tok.Comma }
        var v = value
        repeat(scaleBy) { v /= 1000 }

        val fracPh = fracPart.filterIsInstance<Tok.Ph>()
        val rounded = BigDecimal.valueOf(v).setScale(fracPh.size, RoundingMode.HALF_UP)
        val intDigits = rounded.toBigInteger().toString()
        val fracDigits = rounded.toPlainString().substringAfter('.', "").padEnd(fracPh.size, '0')

        val intText = if (grouped) {
            val minDigits = intPart.count { it == Tok.Ph('0') }
            val padded = if (intDigits == "0" && minDigits == 0) "" else intDigits.padStart(minDigits, '0')
            group(padded) + literals(intPart.filterIsInstance<Tok.Lit>())
        } else {
            fill(intDigits, intPart)
        }

        if (fracPh.isEmpty()) return intText + (if (dot >= 0) "." else "")
        // Right to left: a `#` drops a trailing zero, a `?` turns it into a space, a `0` keeps it.
        val chars = fracDigits.toCharArray()
        var end = chars.size
        while (end > 0 && chars[end - 1] == '0' && fracPh[end - 1].c != '0') {
            if (fracPh[end - 1].c == '?') chars[end - 1] = ' '
            end--
        }
        val frac = buildString {
            var k = 0
            for (t in fracPart) when (t) {
                is Tok.Ph -> {
                    if (k < end) append(chars[k]) else if (t.c == '?') append(' ')
                    k++
                }
                is Tok.Lit -> append(t.s)
                else -> Unit
            }
        }
        return "$intText.$frac"
    }

    /**
     * Integer digits into placeholders, right to left, keeping literals where they sit.
     *
     * Digits beyond the placeholders all go into the left-most one; a placeholder with no digit
     * prints `0` for `0`, a space for `?` and nothing for `#`. That is what lets `000-0000` lay
     * out a phone number and `#,###` print nothing at all for zero.
     */
    private fun fill(digits: String, part: List<Tok>): String {
        val ph = part.count { it is Tok.Ph }
        val source = if (digits == "0") "" else digits
        val out = ArrayList<String>()
        var d = source.length - 1
        var seen = 0
        for (t in part.asReversed()) {
            when (t) {
                is Tok.Ph -> {
                    seen++
                    if (seen == ph) {
                        // The left-most placeholder takes every remaining digit.
                        out.add(if (d >= 0) source.substring(0, d + 1) else blank(t.c))
                        d = -1
                    } else {
                        out.add(if (d >= 0) source[d].toString() else blank(t.c))
                        d--
                    }
                }
                is Tok.Lit -> out.add(t.s)
                else -> Unit
            }
        }
        return out.asReversed().joinToString("")
    }

    private fun blank(c: Char): String = when (c) {
        '0' -> "0"
        '?' -> " "
        else -> ""
    }

    private fun group(digits: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        val lead = digits.length % 3
        if (lead > 0) sb.append(digits, 0, lead)
        var i = lead
        while (i < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, i, i + 3)
            i += 3
        }
        return sb.toString()
    }

    private fun scientific(value: Double, zone: List<Tok>): String {
        val e = zone.indexOfFirst { it is Tok.Exp }
        val mantissa = zone.subList(0, e)
        val expDigits = zone.drop(e + 1).count { it is Tok.Ph }.coerceAtLeast(1)
        val dot = mantissa.indexOf(Tok.Dot)
        val decimals = if (dot < 0) 0 else mantissa.drop(dot + 1).count { it is Tok.Ph }
        // `##0.0E+0` is engineering notation: the exponent is a multiple of the integer
        // placeholder count.
        val intPh = (if (dot < 0) mantissa else mantissa.subList(0, dot)).count { it is Tok.Ph }.coerceAtLeast(1)
        if (value == 0.0) {
            return "0" + (if (decimals > 0) "." + "0".repeat(decimals) else "") + "E+" + "0".repeat(expDigits)
        }
        var exp = kotlin.math.floor(kotlin.math.log10(value)).toInt()
        if (intPh > 1) exp = Math.floorDiv(exp, intPh) * intPh
        var m = BigDecimal.valueOf(value).movePointLeft(exp).setScale(decimals, RoundingMode.HALF_UP)
        if (intPh == 1 && m >= BigDecimal.TEN) {
            exp += 1
            m = BigDecimal.valueOf(value).movePointLeft(exp).setScale(decimals, RoundingMode.HALF_UP)
        }
        val plus = (zone[e] as Tok.Exp).plus
        val sign = if (exp < 0) "-" else if (plus) "+" else ""
        return m.toPlainString() + "E" + sign + kotlin.math.abs(exp).toString().padStart(expDigits, '0')
    }

    /**
     * `# ?/?` and friends: a whole part, then the nearest fraction whose denominator has as many
     * digits as the placeholders after the slash - or exactly the denominator written, for
     * `# ?/8`.
     */
    private fun fraction(value: Double, zone: List<Tok>): String {
        val slash = zone.indexOf(Tok.Slash)
        val after = zone.subList(slash + 1, zone.size)
        val fixed = after.filterIsInstance<Tok.Lit>().joinToString("") { it.s }.trim().toIntOrNull()
        val denDigits = after.count { it is Tok.Ph }.coerceIn(1, 4)
        val before = zone.subList(0, slash)
        // A whole part exists when there is a placeholder group, then a literal (the space),
        // then the numerator's placeholders.
        val gap = before.indexOfLast { it is Tok.Lit }
        val hasWhole = gap > 0 && before.subList(0, gap).any { it is Tok.Ph }

        val whole = if (hasWhole) kotlin.math.floor(value).toLong() else 0L
        val frac = value - whole
        var bestN = 0L
        var bestD = 1L
        if (fixed != null && fixed > 0) {
            bestD = fixed.toLong()
            bestN = Math.round(frac * bestD)
        } else {
            val maxD = Math.pow(10.0, denDigits.toDouble()).toLong() - 1
            var bestErr = Double.MAX_VALUE
            for (d in 1..maxD) {
                val n = Math.round(frac * d)
                val err = kotlin.math.abs(frac - n.toDouble() / d)
                if (err < bestErr - 1e-12) {
                    bestErr = err
                    bestN = n
                    bestD = d
                }
            }
        }
        var w = whole
        if (bestN == bestD && hasWhole) {
            w += 1
            bestN = 0
        }
        return when {
            hasWhole && bestN == 0L -> w.toString()
            hasWhole && w == 0L -> "$bestN/$bestD"
            hasWhole -> "$w $bestN/$bestD"
            else -> "$bestN/$bestD"
        }
    }
}
