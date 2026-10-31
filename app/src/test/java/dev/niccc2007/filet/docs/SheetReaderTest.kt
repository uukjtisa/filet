package dev.niccc2007.filet.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spreadsheet readers, over real archives built byte for byte in the test.
 *
 * Each fixture is a zip with the parts a producer writes - content types, package rels,
 * workbook, workbook rels, shared strings, styles, sheets - because the faults this guards
 * against live in how those parts point at each other, and a mock of the parsed result would
 * test nothing of that.
 */
class SheetReaderTest {

    // ── fixtures ──

    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val ns = """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
    private val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /**
     * An xlsx whose sheets are given as (tab name, part file, sheetData xml). The part files are
     * deliberately allowed to differ from the tab order.
     */
    private fun xlsx(
        sheets: List<Triple<String, String, String>>,
        strings: List<String> = emptyList(),
        styles: String? = null,
        date1904: Boolean = false,
        theme: String? = null,
    ): ByteArray {
        val book = buildString {
            append("""<?xml version="1.0"?><workbook $ns>""")
            if (date1904) append("""<workbookPr date1904="1"/>""")
            append("<sheets>")
            sheets.forEachIndexed { i, (name, _, _) ->
                append("""<sheet name="$name" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
            }
            append("</sheets></workbook>")
        }
        val rels = buildString {
            append("""<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            sheets.forEachIndexed { i, (_, part, _) ->
                append("""<Relationship Id="rId${i + 1}" Type="$relNs/worksheet" Target="worksheets/$part"/>""")
            }
            append("""<Relationship Id="rS" Type="$relNs/sharedStrings" Target="sharedStrings.xml"/>""")
            append("""<Relationship Id="rT" Type="$relNs/styles" Target="styles.xml"/>""")
            if (theme != null) append("""<Relationship Id="rH" Type="$relNs/theme" Target="theme/theme1.xml"/>""")
            append("</Relationships>")
        }
        val sst = buildString {
            append("""<?xml version="1.0"?><sst $ns count="${strings.size}" uniqueCount="${strings.size}">""")
            for (s in strings) append("<si><t>").append(s).append("</t></si>")
            append("</sst>")
        }
        val parts = mutableListOf(
            "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$relNs/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to book,
            "xl/_rels/workbook.xml.rels" to rels,
            "xl/sharedStrings.xml" to sst,
            "xl/styles.xml" to (styles ?: """<?xml version="1.0"?><styleSheet $ns><cellXfs count="1"><xf numFmtId="0"/></cellXfs></styleSheet>"""),
        )
        if (theme != null) parts += "xl/theme/theme1.xml" to theme
        for ((_, part, data) in sheets) {
            parts += "xl/worksheets/$part" to """<?xml version="1.0"?><worksheet $ns>$data</worksheet>"""
        }
        return zip(*parts.toTypedArray())
    }

    private fun open(bytes: ByteArray, ext: String = "xlsx"): Workbook {
        val r = SheetReader.read({ bytes.inputStream() }, ext)
        assertTrue("expected a workbook, got $r", r is SheetOpen.Ready)
        return (r as SheetOpen.Ready).book
    }

    private fun Sheet.text(ref: String): String? {
        val (row, col) = CellRef.parse(ref)!!
        return rows.firstOrNull { it.index == row }?.at(col)?.text
    }

    private fun Sheet.cell(ref: String): Cell? {
        val (row, col) = CellRef.parse(ref)!!
        return rows.firstOrNull { it.index == row }?.at(col)
    }

    // ── references ──

    @Test
    fun `column letters are bijective base 26, past Z and past ZZ`() {
        assertEquals(0, CellRef.column("A"))
        assertEquals(25, CellRef.column("Z"))
        // The off-by-one: ordinary base 26 makes AA 26*1+0 = 26 only by accident and BA wrong.
        assertEquals(26, CellRef.column("AA"))
        assertEquals(27, CellRef.column("AB"))
        assertEquals(52, CellRef.column("BA"))
        assertEquals(701, CellRef.column("ZZ"))
        assertEquals(702, CellRef.column("AAA"))
        assertEquals(16383, CellRef.column("XFD"))
        assertEquals(-1, CellRef.column("A1"))
        for (c in listOf(0, 25, 26, 51, 52, 701, 702, 16383)) assertEquals(c, CellRef.column(CellRef.name(c)))
    }

    @Test
    fun `a reference parses to a zero-based row and column, anchors ignored`() {
        assertEquals(3 to 1, CellRef.parse("B4"))
        assertEquals(9 to 27, CellRef.parse("\$AB\$10"))
        assertNull(CellRef.parse("4B"))
        assertNull(CellRef.parse("A0"))
    }

    @Test
    fun `a shared formula shifts its relative references and leaves pinned ones`() {
        assertEquals("A3*B3", CellRef.shiftFormula("A2*B2", 1, 0))
        assertEquals("\$A\$2*C2", CellRef.shiftFormula("\$A\$2*B2", 0, 1))
        assertEquals("SUM(B2:B11)", CellRef.shiftFormula("SUM(A1:A10)", 1, 1))
        // A function name that looks like a reference, a quoted sheet, and a string.
        assertEquals("LOG10(A3)", CellRef.shiftFormula("LOG10(A2)", 1, 0))
        assertEquals("'Q1 A1'!B3&\"A1\"", CellRef.shiftFormula("'Q1 A1'!B2&\"A1\"", 1, 0))
        assertEquals("Sheet2!A3", CellRef.shiftFormula("Sheet2!A2", 1, 0))
        assertEquals("#REF!", CellRef.shiftFormula("A1", -1, 0))
    }

    // ── workbook structure ──

    @Test
    fun `sheets come in tab order and are found through the rels, not by part number`() {
        // The first tab lives in sheet3.xml - what a workbook looks like after its tabs were
        // reordered. Guessing sheet1.xml for the first tab reads the wrong sheet.
        val book = open(
            xlsx(
                listOf(
                    Triple("Summary", "sheet3.xml", """<sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>summary</t></is></c></row></sheetData>"""),
                    Triple("Data", "sheet1.xml", """<sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>data</t></is></c></row></sheetData>"""),
                ),
            ),
        )
        assertEquals(listOf("Summary", "Data"), book.sheets.map { it.name })
        assertEquals("summary", book.sheet(0).text("A1"))
        assertEquals("data", book.sheet(1).text("A1"))
    }

    // ── values ──

    @Test
    fun `a shared-string cell shows its text, not its index`() {
        val book = open(
            xlsx(
                listOf(Triple("S", "sheet1.xml", """<sheetData><row r="1"><c r="A1" t="s"><v>1</v></c><c r="B1" t="s"><v>0</v></c></row></sheetData>""")),
                strings = listOf("first", "second"),
            ),
        )
        val sheet = book.sheet(0)
        assertEquals("second", sheet.text("A1"))
        assertEquals("first", sheet.text("B1"))
    }

    @Test
    fun `rich-text shared strings join their runs and drop phonetic guides`() {
        val sst = SheetReader.sharedStrings(
            """<sst><si><r><t>Bold</t></r><r><t xml:space="preserve"> plain</t></r><rPh><t>ふりがな</t></rPh></si><si><t>a_x000D_b</t></si></sst>""",
        )
        assertEquals("Bold plain", sst[0])
        assertEquals("ab", sst[1])
    }

    @Test
    fun `a date cell shows a date through cellXfs and a custom numFmt`() {
        // Style 0 is General, style 1 the built-in short date, style 2 a custom format, style 3
        // a percentage. The cellStyleXfs entry must not shift the cellXfs index.
        val styles = """<styleSheet><numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm"/></numFmts>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0"/></cellStyleXfs>""" +
            """<cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/><xf numFmtId="10"/></cellXfs></styleSheet>"""
        val book = open(
            xlsx(
                listOf(
                    Triple(
                        "S", "sheet1.xml",
                        """<sheetData><row r="1"><c r="A1"><v>46095</v></c><c r="B1" s="1"><v>46095</v></c>""" +
                            """<c r="C1" s="2"><v>46095.5</v></c><c r="D1" s="3"><v>0.125</v></c></row></sheetData>""",
                    ),
                ),
                styles = styles,
            ),
        )
        val sheet = book.sheet(0)
        assertEquals("46095", sheet.text("A1"))
        assertEquals("3/14/2026", sheet.text("B1"))
        assertEquals(CellKind.DATE, sheet.cell("B1")!!.kind)
        assertEquals("46095", sheet.cell("B1")!!.raw)
        assertEquals("2026-03-14 12:00", sheet.text("C1"))
        assertEquals("12.50%", sheet.text("D1"))
    }

    // ── cell styles ──

    /**
     * Fonts 0 plain, 1 bold, 2 italic+underline in theme accent 1, 3 white bold. Fills 0 none,
     * 1 gray125 (the default every file carries), 2 solid yellow by rgb, 3 solid theme 4 with a
     * light tint, 4 solid navy by indexed. The dxfs block carries a font and a fill of its own
     * that must not shift either count.
     */
    private val styled = """<styleSheet>""" +
        """<fonts count="4"><font><sz val="11"/></font><font><b/></font>""" +
        """<font><i/><u/><color theme="4"/></font><font><b val="1"/><color rgb="FFFFFFFF"/></font></fonts>""" +
        """<fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/><bgColor indexed="64"/></patternFill></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor theme="4" tint="0.7999816888943144"/></patternFill></fill>""" +
        """<fill><patternFill patternType="solid"><fgColor indexed="18"/></patternFill></fill></fills>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="1" fillId="2"/></cellStyleXfs>""" +
        """<cellXfs count="6"><xf numFmtId="0" fontId="0" fillId="0"/><xf numFmtId="0" fontId="1" fillId="0"/>""" +
        """<xf numFmtId="0" fontId="2" fillId="0"/><xf numFmtId="0" fontId="1" fillId="3"/>""" +
        """<xf numFmtId="0" fontId="3" fillId="4"/><xf numFmtId="0" fontId="0" fillId="2"/></cellXfs>""" +
        """<dxfs count="1"><dxf><font><b/></font><fill><patternFill><bgColor rgb="FFFF0000"/></patternFill></fill></dxf></dxfs>""" +
        """</styleSheet>"""

    private fun hex(c: Int?) = c?.let { "%06X".format(it and 0xFFFFFF) }

    @Test
    fun `bold, italic, underline, fills and font colours reach the cells`() {
        val sheet = open(
            xlsx(
                listOf(
                    Triple(
                        "S", "sheet1.xml",
                        """<sheetData><row r="1"><c r="A1" s="3" t="inlineStr"><is><t>Item</t></is></c>""" +
                            """<c r="B1" s="4" t="inlineStr"><is><t>Total</t></is></c></row>""" +
                            """<row r="2"><c r="A2" s="1"><v>1</v></c><c r="B2" s="2"><v>2</v></c><c r="C2"><v>3</v></c></row></sheetData>""",
                    ),
                ),
                styles = styled,
            ),
        ).sheet(0)
        val header = sheet.cell("A1")!!.style!!
        assertTrue(header.bold)
        // Theme slot 4 is accent 1 in the default theme, lightened 80%.
        assertEquals(SheetLook.tint(SheetLook.OFFICE_THEME[4], 0.7999816888943144), header.fill)
        val total = sheet.cell("B1")!!.style!!
        assertTrue(total.bold)
        assertEquals("000080", hex(total.fill))
        assertEquals("FFFFFF", hex(total.ink))
        assertEquals(CellStyle(bold = true), sheet.cell("A2")!!.style)
        val styledText = sheet.cell("B2")!!.style!!
        assertTrue(styledText.italic && styledText.underline && !styledText.bold)
        assertEquals("4472C4", hex(styledText.ink))
        // Style 0 changes nothing, and a plain cell carries no style at all.
        assertNull(sheet.cell("C2")!!.style)
    }

    @Test
    fun `identical looks are one shared instance, and plain is none at all`() {
        // Two cellXfs entries that differ only in number format draw the same.
        val s = SheetReader.styles(
            """<styleSheet><fonts count="2"><font/><font><b/></font></fonts>""" +
                """<cellXfs count="3"><xf fontId="0"/><xf numFmtId="0" fontId="1"/><xf numFmtId="14" fontId="1"/></cellXfs></styleSheet>""",
        )
        assertTrue(s.look(1) === s.look(2))
        assertNull(s.look(0))
        assertNull(s.look(99))
    }

    @Test
    fun `an empty cell with a fill is kept, one with only weight is not`() {
        val sheet = SheetReader.sheetFromXml(
            """<worksheet><sheetData><row r="1"><c r="A1" s="5"/><c r="B1" s="1"/><c r="C1" t="inlineStr"><is><t>x</t></is></c></row></sheetData></worksheet>""",
            styles = SheetReader.styles(styled),
        )
        val a1 = sheet.cell("A1")
        assertNotNull("the gap in a coloured band is part of the band", a1)
        assertEquals("", a1!!.text)
        assertEquals("FFFF00", hex(a1.style!!.fill))
        assertNull(sheet.cell("B1"))
    }

    @Test
    fun `a formatted row and a formatted column colour the cells the file never wrote`() {
        val sheet = SheetReader.sheetFromXml(
            """<worksheet><cols><col min="3" max="16384" style="5"/></cols><sheetData>""" +
                """<row r="1" s="4" customFormat="1"><c r="A1" t="inlineStr"><is><t>x</t></is></c></row>""" +
                """<row r="2" s="4"><c r="A2" t="inlineStr"><is><t>y</t></is></c></row>""" +
                """<row r="4" s="5" customFormat="1"/>""" +
                """</sheetData></worksheet>""",
            styles = SheetReader.styles(styled),
        )
        val r1 = sheet.rows.first { it.index == 0 }
        val r2 = sheet.rows.first { it.index == 1 }
        // The row's look for its unwritten cells; a written cell keeps its own (style 0, plain).
        assertEquals("000080", hex(sheet.lookAt(r1, 1)?.fill))
        assertNull(sheet.lookAt(r1, 0))
        // `s` without customFormat is ignored, so the column's fill shows through at C.
        assertNull(sheet.lookAt(r2, 1))
        assertEquals("FFFF00", hex(sheet.lookAt(r2, 2)?.fill))
        // A filled row with no cells is still a row, and a row the file never wrote takes the column.
        assertEquals("FFFF00", hex(sheet.lookAt(sheet.rows.first { it.index == 3 }, 0)?.fill))
        assertEquals("FFFF00", hex(sheet.lookAt(null, 200)?.fill))
        assertNull(sheet.lookAt(null, 1))
        assertEquals(SheetLimits.MAX_COLS - 1, sheet.columnStyles.single().last)
    }

    @Test
    fun `a theme part's own colours replace the default theme`() {
        val theme = """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:themeElements>""" +
            """<a:clrScheme name="Mine"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>""" +
            """<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="0E2841"/></a:dk2>""" +
            """<a:lt2><a:srgbClr val="E8E8E8"/></a:lt2><a:accent1><a:srgbClr val="156082"/></a:accent1></a:clrScheme>""" +
            """</a:themeElements></a:theme>"""
        val sheet = open(
            xlsx(
                listOf(Triple("S", "sheet1.xml", """<sheetData><row r="1"><c r="A1" s="2"><v>1</v></c></row></sheetData>""")),
                styles = styled,
                theme = theme,
            ),
        ).sheet(0)
        assertEquals("156082", hex(sheet.cell("A1")!!.style!!.ink))
    }

    @Test
    fun `a broken theme part falls back to the default theme rather than failing the workbook`() {
        val sheet = open(
            xlsx(
                listOf(Triple("S", "sheet1.xml", """<sheetData><row r="1"><c r="A1" s="2"><v>1</v></c></row></sheetData>""")),
                styles = styled,
                theme = "<a:theme",
            ),
        ).sheet(0)
        assertEquals("4472C4", hex(sheet.cell("A1")!!.style!!.ink))
    }

    @Test
    fun `the 1900 leap-year bug and the 1904 system`() {
        assertEquals("2/28/1900", CellFormat.format(59.0, "m/d/yyyy").first)
        assertEquals("2/29/1900", CellFormat.format(60.0, "m/d/yyyy").first)
        assertEquals("3/1/1900", CellFormat.format(61.0, "m/d/yyyy").first)
        assertEquals("1/1/1900", CellFormat.format(1.0, "m/d/yyyy").first)
        assertEquals("1/2/1904", CellFormat.format(1.0, "m/d/yyyy", date1904 = true).first)
    }

    @Test
    fun `time, month names, elapsed hours and minutes-versus-months`() {
        assertEquals("1:30 PM", CellFormat.format(0.5625, "h:mm AM/PM").first)
        assertEquals("14-Mar-26", CellFormat.format(46095.0, "d-mmm-yy").first)
        assertEquals("Saturday, March 14, 2026", CellFormat.format(46095.0, "dddd, mmmm d, yyyy").first)
        assertEquals("27:00:00", CellFormat.format(1.125, "[h]:mm:ss").first)
        assertEquals("05:07", CellFormat.format((5 * 60 + 7) / 86400.0, "mm:ss").first)
    }

    @Test
    fun `numbers, currency, sections and scaling`() {
        assertEquals("1,234.50", CellFormat.format(1234.5, "#,##0.00").first)
        assertEquals("-\$1,234.50", CellFormat.format(-1234.5, "\"$\"#,##0.00").first)
        assertEquals("(1,234)", CellFormat.format(-1234.0, CellFormat.builtIn(37)!!).first)
        assertEquals("₱1,000.00", CellFormat.format(1000.0, "[\$₱-3409]#,##0.00").first)
        assertEquals("-", CellFormat.format(0.0, CellFormat.builtIn(43)!!).first)
        assertEquals("1.5K", CellFormat.format(1500.0, "0.0,\"K\"").first)
        assertEquals("001-2345", CellFormat.format(12345.0, "000-0000").first)
        assertEquals("1.23E+05", CellFormat.format(123456.0, "0.00E+00").first)
        assertEquals("1 1/2", CellFormat.format(1.5, "# ?/?").first)
        assertEquals("0.3", CellFormat.general(0.1 + 0.2))
    }

    @Test
    fun `sparse cells land in their own columns`() {
        // A, B, then F. Laid out in document order F would sit under C.
        val sheet = SheetReader.sheetFromXml(
            """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c><c r="B1"><v>2</v></c><c r="F1"><v>6</v></c></row>""" +
                """<row r="4"><c r="AB4"><v>28</v></c></row></sheetData></worksheet>""",
        )
        val row = sheet.rows[0]
        assertEquals(listOf(0, 1, 5), row.cells.map { it.col })
        assertNull(row.at(2))
        assertEquals("6", row.at(5)!!.text)
        assertEquals(3, sheet.rows[1].index)
        assertEquals("28", sheet.rows[1].at(27)!!.text)
        assertEquals(28, sheet.columns)
    }

    @Test
    fun `cells with no reference continue from the previous one`() {
        val sheet = SheetReader.sheetFromXml(
            """<worksheet><sheetData><row><c r="C1"><v>3</v></c><c><v>4</v></c></row><row><c><v>9</v></c></row></sheetData></worksheet>""",
        )
        assertEquals(listOf(2, 3), sheet.rows[0].cells.map { it.col })
        assertEquals(1, sheet.rows[1].index)
    }

    @Test
    fun `a formula shows its cached value and keeps its text, shared ones shifted`() {
        val sheet = SheetReader.sheetFromXml(
            """<worksheet><sheetData>""" +
                """<row r="2"><c r="C2"><f t="shared" ref="C2:C3" si="0">A2*B2</f><v>6</v></c></row>""" +
                """<row r="3"><c r="C3"><f t="shared" si="0"/><v>20</v></c></row>""" +
                """<row r="4"><c r="C4" t="str"><f>UPPER(A4)</f><v>HI</v></c><c r="D4" t="b"><v>1</v></c><c r="E4" t="e"><v>#DIV/0!</v></c></row>""" +
                """</sheetData></worksheet>""",
        )
        assertEquals("6", sheet.text("C2"))
        assertEquals("A2*B2", sheet.cell("C2")!!.formula)
        assertEquals("20", sheet.text("C3"))
        assertEquals("A3*B3", sheet.cell("C3")!!.formula)
        assertEquals("HI", sheet.text("C4"))
        assertEquals("UPPER(A4)", sheet.cell("C4")!!.formula)
        assertEquals("TRUE", sheet.text("D4"))
        assertEquals(CellKind.ERROR, sheet.cell("E4")!!.kind)
    }

    @Test
    fun `frozen panes are read, split panes are not`() {
        val frozen = SheetReader.sheetFromXml(
            """<worksheet><sheetViews><sheetView><pane xSplit="1" ySplit="2" state="frozen"/></sheetView></sheetViews><sheetData/></worksheet>""",
        )
        assertEquals(2, frozen.frozenRows)
        assertEquals(1, frozen.frozenCols)
        val split = SheetReader.sheetFromXml(
            """<worksheet><sheetViews><sheetView><pane xSplit="1200" ySplit="900" state="split"/></sheetView></sheetViews><sheetData/></worksheet>""",
        )
        assertEquals(0, split.frozenRows)
    }

    @Test
    fun `past the cell cap the sheet stops and says how far it got`() {
        val rows = SheetLimits.MAX_CELLS / 2 + 10
        val xml = buildString {
            append("""<worksheet><dimension ref="A1:B$rows"/><sheetData>""")
            for (r in 1..rows) append("""<row r="$r"><c r="A$r"><v>$r</v></c><c r="B$r"><v>1</v></c></row>""")
            append("</sheetData></worksheet>")
        }
        val sheet = SheetReader.sheetFromXml(xml)
        assertEquals(SheetLimits.MAX_CELLS, sheet.rows.sumOf { it.cells.size })
        val note = sheet.note
        assertNotNull(note)
        assertTrue(note!!, note.contains("Showing rows 1–${SheetLimits.MAX_CELLS / 2}"))
        assertTrue(note, note.contains("%,d".format(rows)))
    }

    // ── what is not a readable workbook ──

    @Test
    fun `a compound file is named as encrypted or as legacy xls, never as damaged`() {
        val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        val encrypted = magic + ByteArray(600) + "EncryptedPackage".toByteArray(Charsets.UTF_16LE) + ByteArray(64)
        val legacy = magic + ByteArray(600) + "Workbook".toByteArray(Charsets.UTF_16LE) + ByteArray(64)
        assertEquals(SheetProblem.ENCRYPTED, (SheetReader.read({ encrypted.inputStream() }) as SheetOpen.Failed).problem)
        assertEquals(SheetProblem.LEGACY_XLS, (SheetReader.read({ legacy.inputStream() }) as SheetOpen.Failed).problem)
    }

    @Test
    fun `a zip with no workbook, and bytes that are not a zip, say so`() {
        val docx = zip("word/document.xml" to "<w:document/>")
        assertEquals(SheetProblem.NOT_A_SPREADSHEET, (SheetReader.read({ docx.inputStream() }) as SheetOpen.Failed).problem)
        val text = "a,b,c\n1,2,3".toByteArray()
        assertEquals(SheetProblem.NOT_A_SPREADSHEET, (SheetReader.read({ text.inputStream() }) as SheetOpen.Failed).problem)
        val cut = xlsx(listOf(Triple("S", "sheet1.xml", "<sheetData/>"))).copyOf(200)
        assertTrue(SheetReader.read({ cut.inputStream() }) is SheetOpen.Failed)
    }

    // ── ODS ──

    private fun ods(content: String, extraManifest: String = ""): ByteArray = zip(
        "mimetype" to "application/vnd.oasis.opendocument.spreadsheet",
        "META-INF/manifest.xml" to """<manifest:manifest xmlns:manifest="m">$extraManifest</manifest:manifest>""",
        "content.xml" to content,
    )

    private val odsHead = """<?xml version="1.0"?><office:document-content xmlns:office="o" xmlns:table="t" xmlns:text="x" xmlns:style="s">"""

    @Test
    fun `ods repeats are counted, not materialised, and keep columns in place`() {
        val content = odsHead +
            """<office:automatic-styles><style:style style:name="ta2" style:family="table"><style:table-properties table:display="false"/></style:style></office:automatic-styles>""" +
            """<office:body><office:spreadsheet>""" +
            """<table:table table:name="Budget">""" +
            """<table:table-row><table:table-cell office:value-type="string"><text:p>Item</text:p></table:table-cell>""" +
            """<table:table-cell table:number-columns-repeated="3"/>""" +
            """<table:table-cell office:value-type="float" office:value="1234.5" table:formula="of:=SUM([.A1:.A3])"><text:p>1,234.50</text:p></table:table-cell>""" +
            """<table:table-cell table:number-columns-repeated="1019"/></table:table-row>""" +
            """<table:table-row table:number-rows-repeated="1048570"><table:table-cell table:number-columns-repeated="1024"/></table:table-row>""" +
            """<table:table-row><table:table-cell office:value-type="date" office:date-value="2026-03-14"><text:p>03/14/2026</text:p><office:annotation><text:p>a note</text:p></office:annotation></table:table-cell></table:table-row>""" +
            """</table:table><table:table table:name="Lookup" table:style-name="ta2"/>""" +
            """</office:spreadsheet></office:body></office:document-content>"""
        val book = open(ods(content), "ods")
        assertEquals(listOf("Budget", "Lookup"), book.sheets.map { it.name })
        assertTrue(book.sheets[1].hidden)
        val sheet = book.sheet(0)
        assertEquals(2, sheet.rows.size)
        assertEquals(listOf(0, 4), sheet.rows[0].cells.map { it.col })
        assertEquals("1,234.50", sheet.text("E1"))
        assertEquals("1234.5", sheet.cell("E1")!!.raw)
        assertEquals("SUM([.A1:.A3])", sheet.cell("E1")!!.formula)
        assertEquals(1048571, sheet.rows[1].index)
        assertEquals("03/14/2026", sheet.rows[1].at(0)!!.text)
        assertEquals(CellKind.DATE, sheet.rows[1].at(0)!!.kind)
        assertEquals(5, sheet.columns)
    }

    @Test
    fun `an encrypted ods is named as encrypted`() {
        val bytes = ods("garbage", """<manifest:encryption-data/>""")
        assertEquals(SheetProblem.ENCRYPTED, (SheetReader.read({ bytes.inputStream() }, "ods") as SheetOpen.Failed).problem)
    }

    // ── zoom ──

    @Test
    fun `zoom clamps at both bounds, and lower still for a grid too wide to lay out`() {
        assertEquals(SheetZoom.MIN, SheetZoom.clamp(0.1f))
        assertEquals(SheetZoom.MAX, SheetZoom.clamp(9f))
        assertEquals(1.7f, SheetZoom.clamp(1.7f), 0f)
        // 200,000 px unscaled: 1.25x is the most that stays under the layout limit.
        assertEquals(1.25f, SheetZoom.clamp(3f, 200_000f), 0.0001f)
        // Never pushed below 1x by width.
        assertEquals(1f, SheetZoom.clamp(3f, 900_000f), 0f)
    }

    @Test
    fun `double tap goes in from normal size and back out from anywhere zoomed`() {
        assertEquals(SheetZoom.DOUBLE_TAP, SheetZoom.doubleTap(1f), 0f)
        assertEquals(1f, SheetZoom.doubleTap(SheetZoom.DOUBLE_TAP), 0f)
        assertEquals(1f, SheetZoom.doubleTap(2.8f), 0f)
        assertEquals(SheetZoom.DOUBLE_TAP, SheetZoom.doubleTap(0.6f), 0f)
    }

    // ── the pinch in flight ──

    /** Where moving content [at] content pixels in is drawn on screen, laid out at [offset]. */
    private fun drawnAt(at: Float, axis: SheetAxis, lead: Float = axis.lead, offset: Float = axis.offset) = lead + at - offset

    @Test
    fun `landing puts every cell where the picture had it, not only the one under the fingers`() {
        val across = SheetAxis(offset = 340f, lead = 96f, content = 5000f, size = 1080f)
        for (ratio in listOf(2f, 1.3f, 0.85f)) {
            val p = SheetPinch().by(ratio, cx = 610f, cy = 0f, from = 1f, lo = SheetZoom.MIN, hi = SheetZoom.MAX)
            val leadAfter = 96f * p.scale + 1f // the band's real size after, rounding and all
            val landed = p.lands(across, leadAfter, p.x)
            for (cell in listOf(340f, 900f, 1200f)) {
                val picture = p.x + p.scale * drawnAt(cell, across)
                val laidOut = drawnAt(cell * p.scale, across, lead = leadAfter, offset = landed)
                assertEquals("cell $cell at $ratio", picture, laidOut, 0.01f)
            }
        }
    }

    @Test
    fun `the point under the fingers stays under them while they move`() {
        val p = SheetPinch()
            .by(1.2f, cx = 300f, cy = 500f, from = 1f, lo = SheetZoom.MIN, hi = SheetZoom.MAX)
            .by(1.5f, cx = 300f, cy = 500f, from = 1f, lo = SheetZoom.MIN, hi = SheetZoom.MAX)
        assertEquals(1.8f, p.scale, 0.0001f)
        assertEquals(300f, p.x + p.scale * 300f, 0.01f)
        assertEquals(500f, p.y + p.scale * 500f, 0.01f)
    }

    @Test
    fun `a pinch never promises a size that cannot land`() {
        val grown = SheetPinch().by(10f, 0f, 0f, from = 1.5f, lo = SheetZoom.MIN, hi = SheetZoom.MAX)
        assertEquals(SheetZoom.MAX, 1.5f * grown.scale, 0.0001f)
        val shrunk = SheetPinch().by(0.01f, 0f, 0f, from = 1.5f, lo = SheetZoom.MIN, hi = SheetZoom.MAX)
        assertEquals(SheetZoom.MIN, 1.5f * shrunk.scale, 0.0001f)
        // Pushing against a bound moves nothing, so the picture does not slide under the fingers.
        val pinned = SheetPinch().by(2f, 400f, 400f, from = SheetZoom.MAX, lo = SheetZoom.MIN, hi = SheetZoom.MAX)
        assertTrue(pinned.idle)
        assertEquals(grown, grown.by(Float.NaN, 9f, 9f, 1.5f, SheetZoom.MIN, SheetZoom.MAX))
        assertEquals(grown, grown.by(0f, 9f, 9f, 1.5f, SheetZoom.MIN, SheetZoom.MAX))
    }

    @Test
    fun `shrinking at the top-left keeps the sheet on its corner`() {
        // Zooming out at the left edge cannot scroll to before the first column.
        val across = SheetAxis(offset = 0f, lead = 96f, content = 5000f, size = 1080f)
        val down = SheetAxis(offset = 0f, lead = 140f, content = 90_000f, size = 1900f)
        val p = SheetPinch().by(0.85f, 540f, 900f, from = 1f, lo = SheetZoom.MIN, hi = SheetZoom.MAX).held(across, down)
        assertEquals(0f, p.lands(across, 96f * p.scale, p.x), 0.01f)
        assertEquals(0f, p.lands(down, 140f * p.scale, p.y), 0.01f)
    }

    @Test
    fun `shrinking at the far edge does not pull past the last column`() {
        val start = SheetAxis(offset = 0f, lead = 96f, content = 5000f, size = 1080f)
        val across = start.copy(offset = start.farthest(1f))
        val down = SheetAxis(offset = 0f, lead = 140f, content = 90_000f, size = 1900f)
        val p = SheetPinch().by(0.85f, 1000f, 900f, from = 1f, lo = SheetZoom.MIN, hi = SheetZoom.MAX).held(across, down)
        assertEquals(across.farthest(p.scale), p.lands(across, 96f * p.scale, p.x), 0.01f)
    }

    @Test
    fun `a sheet narrower than the screen sits at its start`() {
        val across = SheetAxis(offset = 0f, lead = 96f, content = 300f, size = 1080f)
        val down = SheetAxis(offset = 0f, lead = 140f, content = 600f, size = 1900f)
        val p = SheetPinch().by(1.6f, 900f, 1200f, from = 1f, lo = SheetZoom.MIN, hi = SheetZoom.MAX).held(across, down)
        assertEquals(0f, p.lands(across, 96f * p.scale, p.x), 0.01f)
        assertEquals(0f, p.lands(down, 140f * p.scale, p.y), 0.01f)
    }

    @Test
    fun `shrinking lands mid-pinch before the picture runs out of rows, growing never does`() {
        assertTrue(SheetPinch(scale = SheetPinch.OUT - 0.01f).outOfRows)
        assertFalse(SheetPinch(scale = SheetPinch.OUT).outOfRows)
        assertFalse(SheetPinch(scale = SheetZoom.MAX).outOfRows)
    }

    @Test
    fun `a column and its letter share a left edge at every zoom`() {
        val widths = floatArrayOf(80f, 44f, 113.6f, 34f, 200f, 57.3f, 61f)
        for (scale in listOf(0.5f, 1.37f, 3f)) {
            val g = SheetGeometry(widths, gutterDp = 37f, pinCols = 1, scale = scale, density = 2.625f)
            for (offset in listOf(0, 71, 333)) {
                for (col in widths.indices) assertEquals("col $col at $scale", g.cellLeft(col, offset), g.letterLeft(col, offset))
            }
            // And no drift: each moving column starts exactly where the last ended, so column G
            // is where the sum of the rounded widths before it says.
            for (k in 1 until g.widths.size) assertEquals(g.starts[k - 1] + g.widths[k - 1], g.starts[k])
            assertEquals(g.movingLeft, g.cellLeft(1, 0))
        }
    }
}
