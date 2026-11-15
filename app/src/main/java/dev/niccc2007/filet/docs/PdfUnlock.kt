package dev.niccc2007.filet.docs

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Random access to a PDF's bytes, so a file of any size is read where it lies. */
interface PdfSource {
    val size: Long

    /** Up to [len] bytes from [at] into [into], returning how many; fewer only at the end. */
    fun read(at: Long, into: ByteArray, off: Int, len: Int): Int
}

class ByteArrayPdf(private val bytes: ByteArray) : PdfSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(at: Long, into: ByteArray, off: Int, len: Int): Int {
        if (at >= bytes.size) return 0
        val n = minOf(len.toLong(), bytes.size - at).toInt()
        System.arraycopy(bytes, at.toInt(), into, off, n)
        return n
    }
}

/**
 * Opens a password-protected PDF into a plain copy that Android's own PDF reader can show.
 *
 * Android's reader takes a password only from Android 15 on. Below that it can show the file
 * once nothing in it is encrypted, so this does the decrypting: it checks the password the way
 * the PDF standard says, then writes every object out again with its strings and streams
 * decrypted and the encryption entry gone. Nothing else is changed, so the copy is the same
 * document, page for page.
 *
 * Decision: our own decryptor rather than a PDF library. A library that can do this is
 * megabytes in the app for one feature, and the standard's password scheme is short and fixed:
 * RC4 40 to 128 bit, AES-128 and AES-256, the only ones a password opens. A PDF locked to a
 * certificate rather than a password is the one case left out, and it says so.
 */
object PdfUnlock {

    sealed interface Result {
        /** Written: the copy in the output opens without a password. */
        data object Unlocked : Result

        data object WrongPassword : Result

        /** Nothing in it is encrypted; the original opens as it is. */
        data object NotLocked : Result

        /** Locked in a way a password does not open, or too damaged to read; [why] says which. */
        data class Unsupported(val why: String) : Result
    }

    fun unlock(src: PdfSource, password: String, out: OutputStream): Result = try {
        Unlocker(Bytes(src)).run(password, out)
    } catch (e: OutOfMemoryError) {
        Result.Unsupported("This PDF is too large to unlock on this phone.")
    } catch (e: Unreadable) {
        Result.Unsupported(e.message ?: "This PDF is damaged.")
    }

    /** Whether [src] is encrypted at all: what the reader refusing it is about. */
    fun isLocked(src: PdfSource): Boolean = runCatching { Unlocker(Bytes(src)).encryptDict() != null }.getOrDefault(false)

    internal class Unreadable(why: String) : Exception(why)
}

// ── the object model ──

internal sealed interface PObj
internal data class PNum(val text: String) : PObj {
    val long: Long? get() = text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
}
internal data class PName(val raw: String) : PObj
internal class PStr(var bytes: ByteArray) : PObj
internal class PArr(val items: MutableList<PObj>) : PObj
internal class PDict(val map: LinkedHashMap<String, PObj>) : PObj {
    operator fun get(k: String): PObj? = map[k]
    fun num(k: String): Long? = (map[k] as? PNum)?.long
    fun name(k: String): String? = (map[k] as? PName)?.raw
}
internal data class PRef(val num: Int, val gen: Int) : PObj
internal data class PKw(val text: String) : PObj

/** An object as it sits in the file: its value, and where its stream's bytes are. */
internal class Indirect(val num: Int, val gen: Int, val value: PObj, val dataAt: Long, val dataLen: Long)

/** A cross-reference entry: type 1 is at [a] in the file; type 2 is item [b] of object stream [a]. */
internal class XEntry(val type: Int, val a: Long, val b: Int)

// ── reading ──

/** The file through a window, so parsing reads byte by byte without a call per byte. */
internal class Bytes(private val src: PdfSource) {
    val size: Long = src.size
    private val buf = ByteArray(64 * 1024)
    private var start = -1L
    private var len = 0

    fun at(p: Long): Int {
        if (p < 0 || p >= size) return -1
        if (p < start || p >= start + len) {
            start = p
            len = src.read(p, buf, 0, buf.size)
            if (len <= 0) return -1
        }
        return buf[(p - start).toInt()].toInt() and 0xFF
    }

    fun slice(p: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = src.read(p + got, out, got, n - got)
            if (r <= 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    fun startsWith(p: Long, s: String): Boolean = s.indices.all { at(p + it) == s[it].code }
}

internal class Parser(val b: Bytes, var pos: Long = 0) {

    fun ws(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
    private fun delim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
        c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

    fun skipWs() {
        while (true) {
            val c = b.at(pos)
            when {
                c < 0 -> return
                ws(c) -> pos++
                c == '%'.code -> while (b.at(pos).let { it >= 0 && it != 10 && it != 13 }) pos++
                else -> return
            }
        }
    }

    /** A run of regular characters: a number, a keyword. */
    fun token(): String {
        skipWs()
        val sb = StringBuilder()
        while (true) {
            val c = b.at(pos)
            if (c < 0 || ws(c) || delim(c)) break
            sb.append(c.toChar())
            pos++
        }
        return sb.toString()
    }

    fun obj(depth: Int = 0): PObj {
        if (depth > 200) throw PdfUnlock.Unreadable("This PDF is damaged.")
        skipWs()
        val c = b.at(pos)
        return when {
            c < 0 -> throw PdfUnlock.Unreadable("This PDF ends too early.")
            c == '/'.code -> {
                pos++
                val sb = StringBuilder()
                while (true) {
                    val d = b.at(pos)
                    if (d < 0 || ws(d) || delim(d)) break
                    sb.append(d.toChar())
                    pos++
                }
                PName(sb.toString())
            }
            c == '('.code -> PStr(literal())
            c == '<'.code && b.at(pos + 1) == '<'.code -> {
                pos += 2
                val map = LinkedHashMap<String, PObj>()
                while (true) {
                    skipWs()
                    val d = b.at(pos)
                    if (d < 0) throw PdfUnlock.Unreadable("This PDF is damaged.")
                    if (d == '>'.code && b.at(pos + 1) == '>'.code) { pos += 2; break }
                    val key = obj(depth + 1) as? PName ?: throw PdfUnlock.Unreadable("This PDF is damaged.")
                    map[key.raw] = obj(depth + 1)
                }
                PDict(map)
            }
            c == '<'.code -> PStr(hex())
            c == '['.code -> {
                pos++
                val items = ArrayList<PObj>()
                while (true) {
                    skipWs()
                    val d = b.at(pos)
                    if (d < 0) throw PdfUnlock.Unreadable("This PDF is damaged.")
                    if (d == ']'.code) { pos++; break }
                    items += obj(depth + 1)
                }
                PArr(items)
            }
            c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> {
                val first = token()
                val after = pos
                if (first.all { it.isDigit() }) {
                    val second = token()
                    if (second.isNotEmpty() && second.all { it.isDigit() }) {
                        skipWs()
                        val r = b.at(pos)
                        val next = b.at(pos + 1)
                        if (r == 'R'.code && (next < 0 || ws(next) || delim(next))) {
                            pos++
                            return PRef(first.toInt(), second.toInt())
                        }
                    }
                }
                pos = after
                PNum(first)
            }
            else -> {
                val t = token()
                if (t.isEmpty()) { pos++; PKw(c.toChar().toString()) } else PKw(t)
            }
        }
    }

    /** A literal string, bytes kept exactly: an encrypted one is binary and must not be touched. */
    private fun literal(): ByteArray {
        pos++
        val out = ByteArrayOutputStream()
        var depth = 1
        while (true) {
            val c = b.at(pos++)
            if (c < 0) break
            when (c) {
                '\\'.code -> {
                    val e = b.at(pos++)
                    when (e) {
                        'n'.code -> out.write(10)
                        'r'.code -> out.write(13)
                        't'.code -> out.write(9)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        13 -> if (b.at(pos) == 10) pos++
                        10 -> {}
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            repeat(2) {
                                val d = b.at(pos)
                                if (d in '0'.code..'7'.code) { v = v * 8 + (d - '0'.code); pos++ }
                            }
                            out.write(v and 0xFF)
                        }
                        -1 -> {}
                        else -> out.write(e)
                    }
                }
                '('.code -> { depth++; out.write(c) }
                ')'.code -> { depth--; if (depth == 0) break; out.write(c) }
                else -> out.write(c)
            }
        }
        return out.toByteArray()
    }

    private fun hex(): ByteArray {
        pos++
        val out = ByteArrayOutputStream()
        var hi = -1
        while (true) {
            val c = b.at(pos++)
            if (c < 0 || c == '>'.code) break
            val v = Character.digit(c, 16)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write(hi * 16 + v); hi = -1 }
        }
        if (hi >= 0) out.write(hi * 16)
        return out.toByteArray()
    }
}

// ── the work ──

private class Unlocker(val b: Bytes) {
    val xref = HashMap<Int, XEntry>()
    var trailer: PDict? = null

    fun encryptDict(): PDict? {
        readXrefs()
        return when (val e = trailer?.get("Encrypt")) {
            is PDict -> e
            is PRef -> load(e.num)?.value as? PDict
            else -> null
        }
    }

    fun run(password: String, out: OutputStream): PdfUnlock.Result {
        val enc = encryptDict() ?: return PdfUnlock.Result.NotLocked
        if (enc.name("Filter") != "Standard") {
            return PdfUnlock.Result.Unsupported("This PDF is locked to a certificate, not a password.")
        }
        val sec = Security.of(enc, trailer!!)
            ?: return PdfUnlock.Result.Unsupported("This PDF is locked in a way Filet cannot open.")
        if (!sec.open(password)) return PdfUnlock.Result.WrongPassword
        write(sec, (trailer!!["Encrypt"] as? PRef)?.num, out)
        return PdfUnlock.Result.Unlocked
    }

    // ── cross-reference ──

    fun readXrefs() {
        if (trailer != null) return
        val ok = runCatching {
            val tail = b.slice(maxOf(0, b.size - 2048), minOf(2048, b.size).toInt())
            val text = String(tail, Charsets.ISO_8859_1)
            val at = text.lastIndexOf("startxref")
            if (at < 0) error("no startxref")
            val p = Parser(b, maxOf(0, b.size - 2048) + at + 9)
            var next: Long? = p.token().toLong()
            val seen = HashSet<Long>()
            while (next != null && seen.add(next)) next = section(next)
            trailer != null && xref.isNotEmpty()
        }.getOrDefault(false)
        if (!ok) rebuild()
        if (trailer == null) throw PdfUnlock.Unreadable("This PDF is damaged: its index cannot be found.")
    }

    /** Read one section at [at]; returns where the previous one is. */
    private fun section(at: Long): Long? {
        val p = Parser(b, at)
        p.skipWs()
        if (b.startsWith(p.pos, "xref")) {
            p.pos += 4
            while (true) {
                p.skipWs()
                if (b.startsWith(p.pos, "trailer")) { p.pos += 7; break }
                val first = p.token().toIntOrNull() ?: error("bad xref")
                val count = p.token().toIntOrNull() ?: error("bad xref")
                for (i in 0 until count) {
                    val off = p.token().toLongOrNull() ?: error("bad xref")
                    val gen = p.token().toIntOrNull() ?: error("bad xref")
                    val kind = p.token()
                    if (kind == "n" && off > 0) xref.putIfAbsent(first + i, XEntry(1, off, gen))
                }
            }
            val t = p.obj() as? PDict ?: error("bad trailer")
            if (trailer == null) trailer = t
            t.num("XRefStm")?.let { runCatching { stream(it) } }
            return t.num("Prev")
        }
        return stream(at)
    }

    private fun stream(at: Long): Long? {
        val o = indirectAt(at) ?: error("bad xref stream")
        val d = o.value as? PDict ?: error("bad xref stream")
        if (d.name("Type") != "XRef") error("not an xref stream")
        if (trailer == null) trailer = d
        val data = decode(d, b.slice(o.dataAt, o.dataLen.toInt()))
        val w = (d["W"] as? PArr)?.items?.map { (it as PNum).long!!.toInt() } ?: error("bad W")
        val size = d.num("Size")?.toInt() ?: 0
        val index = (d["Index"] as? PArr)?.items?.map { (it as PNum).long!!.toInt() } ?: listOf(0, size)
        val row = w.sum()
        var p = 0
        fun field(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or (data[p++].toLong() and 0xFF) }
            return v
        }
        for (k in index.indices step 2) {
            for (i in 0 until index[k + 1]) {
                if (p + row > data.size) break
                val type = if (w[0] == 0) 1 else field(w[0]).toInt()
                val f2 = field(w[1])
                val f3 = field(w[2]).toInt()
                if (type == 1 || type == 2) xref.putIfAbsent(index[k] + i, XEntry(type, f2, f3))
            }
        }
        return d.num("Prev")
    }

    /** No usable index: find every object by walking the file, the way a reader repairs one. */
    private fun rebuild() {
        xref.clear()
        trailer = null
        var p = 0L
        var lastTrailer = -1L
        while (p < b.size) {
            val c = b.at(p)
            if (c == 'o'.code && b.startsWith(p, "obj")) {
                var q = p - 1
                while (q >= 0 && Parser(b).ws(b.at(q))) q--
                val genEnd = q
                while (q >= 0 && b.at(q) in '0'.code..'9'.code) q--
                val gen = String(b.slice(q + 1, (genEnd - q).toInt()), Charsets.ISO_8859_1).toIntOrNull()
                while (q >= 0 && Parser(b).ws(b.at(q))) q--
                val numEnd = q
                while (q >= 0 && b.at(q) in '0'.code..'9'.code) q--
                val num = String(b.slice(q + 1, (numEnd - q).toInt()), Charsets.ISO_8859_1).toIntOrNull()
                if (gen != null && num != null && numEnd < genEnd) xref[num] = XEntry(1, q + 1, gen)
                p += 3
            } else if (c == 't'.code && b.startsWith(p, "trailer")) {
                lastTrailer = p
                p += 7
            } else {
                p++
            }
        }
        if (lastTrailer >= 0) trailer = runCatching { Parser(b, lastTrailer + 7).obj() as? PDict }.getOrNull()
        if (trailer == null) {
            // A file indexed by xref streams has no trailer keyword; the last of them is it.
            trailer = xref.values.filter { it.type == 1 }.sortedByDescending { it.a }.firstNotNullOfOrNull { e ->
                (runCatching { indirectAt(e.a) }.getOrNull()?.value as? PDict)?.takeIf { it.name("Type") == "XRef" }
            }
        }
    }

    // ── objects ──

    fun load(num: Int): Indirect? {
        val e = xref[num] ?: return null
        if (e.type != 1) return null
        return runCatching { indirectAt(e.a) }.getOrNull()
    }

    fun indirectAt(at: Long): Indirect? {
        val p = Parser(b, at)
        val num = p.token().toIntOrNull() ?: return null
        val gen = p.token().toIntOrNull() ?: return null
        if (p.token() != "obj") return null
        val value = p.obj()
        p.skipWs()
        if (!b.startsWith(p.pos, "stream")) return Indirect(num, gen, value, -1, 0)
        var start = p.pos + 6
        if (b.at(start) == 13) start++
        if (b.at(start) == 10) start++
        val dict = value as? PDict
        val said = when (val l = dict?.get("Length")) {
            is PNum -> l.long
            is PRef -> if (l.num == num) null else (load(l.num)?.value as? PNum)?.long
            else -> null
        }
        if (said != null && said >= 0 && start + said <= b.size) {
            val q = Parser(b, start + said)
            q.skipWs()
            if (b.startsWith(q.pos, "endstream")) return Indirect(num, gen, value, start, said)
        }
        // The length is wrong, as it often is: the data runs to the endstream keyword.
        var end = start
        while (end < b.size && !b.startsWith(end, "endstream")) end++
        if (end > start && b.at(end - 1) == 10) end--
        if (end > start && b.at(end - 1) == 13) end--
        return Indirect(num, gen, value, start, end - start)
    }

    fun decode(d: PDict, raw: ByteArray): ByteArray {
        val filters = when (val f = d["Filter"]) {
            is PName -> listOf(f.raw)
            is PArr -> f.items.map { (it as PName).raw }
            else -> emptyList()
        }
        val parms = when (val p = d["DecodeParms"]) {
            is PDict -> p
            is PArr -> p.items.firstOrNull() as? PDict
            else -> null
        }
        var data = raw
        for (f in filters) {
            data = when (f) {
                "FlateDecode", "Fl" -> unpredict(inflate(data), parms)
                else -> throw PdfUnlock.Unreadable("This PDF's index uses a form Filet cannot read.")
            }
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    private fun unpredict(data: ByteArray, parms: PDict?): ByteArray {
        val pred = parms?.num("Predictor")?.toInt() ?: 1
        if (pred < 10) return data
        val colors = parms?.num("Colors")?.toInt() ?: 1
        val bpc = parms?.num("BitsPerComponent")?.toInt() ?: 8
        val cols = parms?.num("Columns")?.toInt() ?: 1
        val bpp = maxOf(1, (colors * bpc + 7) / 8)
        val rowLen = (cols * colors * bpc + 7) / 8
        val out = ByteArrayOutputStream()
        var prev = ByteArray(rowLen)
        var p = 0
        while (p + 1 + rowLen <= data.size) {
            val f = data[p].toInt()
            val row = data.copyOfRange(p + 1, p + 1 + rowLen)
            for (i in 0 until rowLen) {
                val left = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val add = when (f) {
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> {
                        val pa = Math.abs(up - upLeft)
                        val pb = Math.abs(left - upLeft)
                        val pc = Math.abs(left + up - 2 * upLeft)
                        if (pa <= pb && pa <= pc) left else if (pb <= pc) up else upLeft
                    }
                    else -> 0
                }
                row[i] = ((row[i].toInt() and 0xFF) + add).toByte()
            }
            out.write(row)
            prev = row
            p += 1 + rowLen
        }
        return out.toByteArray()
    }

    // ── writing ──

    private class Counting(val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        fun text(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))
    }

    fun write(sec: Security, encryptNum: Int?, sink: OutputStream) {
        val out = Counting(sink)
        out.write("%PDF-1.7\n%".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 10))
        val written = HashMap<Int, Pair<Long, Int>>()
        for (num in xref.keys.sorted()) {
            val e = xref[num]!!
            if (e.type != 1 || num == encryptNum) continue
            val o = runCatching { indirectAt(e.a) }.getOrNull() ?: continue
            if (o.num != num) continue
            val dict = o.value as? PDict
            // Our own index replaces the old one, and a rewritten file is no longer linearized.
            if (dict?.name("Type") == "XRef" || dict?.get("Linearized") != null) continue
            sec.strings(o.value, num, o.gen)
            written[num] = out.count to o.gen
            out.text("$num ${o.gen} obj\n")
            if (o.dataAt >= 0 && dict != null) {
                var data = b.slice(o.dataAt, o.dataLen.toInt())
                if (!sec.leaves(dict)) data = sec.stream(data, num, o.gen)
                dict.map["Length"] = PNum(data.size.toString())
                out.text(serial(dict))
                out.text("\nstream\n")
                out.write(data)
                out.text("\nendstream")
            } else {
                out.text(serial(o.value))
            }
            out.text("\nendobj\n")
        }
        val t = trailer!!
        val maxNum = maxOf(xref.keys.maxOrNull() ?: 0, ((t.num("Size") ?: 1) - 1).toInt())
        val self = maxNum + 1
        val size = self + 1
        val at = out.count
        if (at > 0xFFFFFFFFL) throw PdfUnlock.Unreadable("This PDF is too large to unlock.")
        val rows = ByteArrayOutputStream()
        fun row(type: Int, f2: Long, f3: Int) {
            rows.write(type)
            for (s in 3 downTo 0) rows.write(((f2 ushr (8 * s)) and 0xFF).toInt())
            rows.write((f3 ushr 8) and 0xFF)
            rows.write(f3 and 0xFF)
        }
        for (n in 0 until size) {
            val w = written[n]
            val e = xref[n]
            when {
                n == 0 -> row(0, 0, 0xFFFF)
                n == self -> row(1, at, 0)
                w != null -> row(1, w.first, w.second)
                e != null && e.type == 2 && written.containsKey(e.a.toInt()) -> row(2, e.a, e.b)
                else -> row(0, 0, 0)
            }
        }
        val data = rows.toByteArray()
        val d = LinkedHashMap<String, PObj>()
        d["Type"] = PName("XRef")
        d["Size"] = PNum(size.toString())
        d["W"] = PArr(mutableListOf(PNum("1"), PNum("4"), PNum("2")))
        t["Root"]?.let { d["Root"] = it }
        t["Info"]?.let { if (it is PRef) d["Info"] = it }
        t["ID"]?.let { d["ID"] = it }
        d["Length"] = PNum(data.size.toString())
        out.text("$self 0 obj\n")
        out.text(serial(PDict(d)))
        out.text("\nstream\n")
        out.write(data)
        out.text("\nendstream\nendobj\nstartxref\n$at\n%%EOF\n")
        out.flush()
    }

    fun serial(o: PObj): String = StringBuilder().also { serial(o, it) }.toString()

    private fun serial(o: PObj, sb: StringBuilder) {
        when (o) {
            is PNum -> sb.append(o.text)
            is PName -> sb.append('/').append(o.raw)
            is PStr -> {
                sb.append('<')
                for (x in o.bytes) sb.append(HEX[(x.toInt() shr 4) and 15]).append(HEX[x.toInt() and 15])
                sb.append('>')
            }
            is PArr -> {
                sb.append('[')
                o.items.forEachIndexed { i, it -> if (i > 0) sb.append(' '); serial(it, sb) }
                sb.append(']')
            }
            is PDict -> {
                sb.append("<<")
                for ((k, v) in o.map) { sb.append('/').append(k).append(' '); serial(v, sb); sb.append(' ') }
                sb.append(">>")
            }
            is PRef -> sb.append(o.num).append(' ').append(o.gen).append(" R")
            is PKw -> sb.append(o.text)
        }
    }

    companion object {
        private const val HEX = "0123456789ABCDEF"
    }
}

// ── the standard security handler ──

internal class Security private constructor(
    private val r: Int,
    private val n: Int,
    private val o: ByteArray,
    private val u: ByteArray,
    private val oe: ByteArray,
    private val ue: ByteArray,
    private val p: Int,
    private val id0: ByteArray,
    private val encryptMetadata: Boolean,
    private val stm: Method,
    private val str: Method,
) {
    enum class Method { NONE, RC4, AES128, AES256 }

    private var key: ByteArray = ByteArray(0)

    /** Whether [password] opens it, as the user's password or the owner's. Keeps the key. */
    fun open(password: String): Boolean {
        if (r >= 5) {
            val pw = Normalizer.normalize(password, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8).let { if (it.size > 127) it.copyOf(127) else it }
            val udata = u.copyOf(48)
            return when {
                hash(pw, u.copyOfRange(32, 40), ByteArray(0), r).contentEquals(u.copyOf(32)) -> {
                    key = aesNoPad(hash(pw, u.copyOfRange(40, 48), ByteArray(0), r), ue.copyOf(32)); true
                }
                hash(pw, o.copyOfRange(32, 40), udata, r).contentEquals(o.copyOf(32)) -> {
                    key = aesNoPad(hash(pw, o.copyOfRange(40, 48), udata, r), oe.copyOf(32)); true
                }
                else -> false
            }
        }
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        fileKey(pad(pw)).let { if (userOk(it)) { key = it; return true } }
        // The owner's password: it unlocks the user's, which then gives the key.
        var h = md5(pad(pw))
        if (r >= 3) repeat(50) { h = md5(h.copyOf(n)) }
        val rk = h.copyOf(n)
        var x = o.copyOf(32)
        if (r == 2) x = rc4(rk, x) else for (i in 19 downTo 0) x = rc4(xor(rk, i), x)
        fileKey(x).let { if (userOk(it)) { key = it; return true } }
        return false
    }

    private fun fileKey(padded: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        md.update(padded)
        md.update(o.copyOf(32))
        md.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
        md.update(id0)
        if (r >= 4 && !encryptMetadata) md.update(byteArrayOf(-1, -1, -1, -1))
        var h = md.digest()
        if (r >= 3) repeat(50) { h = md5(h.copyOf(n)) }
        return h.copyOf(n)
    }

    private fun userOk(k: ByteArray): Boolean = if (r == 2) {
        rc4(k, PAD).contentEquals(u.copyOf(32))
    } else {
        var x = rc4(k, md5(PAD + id0))
        for (i in 1..19) x = rc4(xor(k, i), x)
        x.contentEquals(u.copyOf(16))
    }

    /** Decrypt every string in [o], in place. */
    fun strings(o: PObj, num: Int, gen: Int) {
        if (str == Method.NONE) return
        when (o) {
            is PStr -> o.bytes = crypt(str, o.bytes, num, gen)
            is PArr -> o.items.forEach { strings(it, num, gen) }
            is PDict -> o.map.values.forEach { strings(it, num, gen) }
            else -> {}
        }
    }

    fun stream(data: ByteArray, num: Int, gen: Int): ByteArray = crypt(stm, data, num, gen)

    /** A stream left as it is: metadata kept readable on purpose, or one with its own Identity filter. */
    fun leaves(d: PDict): Boolean {
        if (stm == Method.NONE) return true
        if (!encryptMetadata && d.name("Type") == "Metadata") return true
        val f = d["Filter"]
        val crypt = (f as? PName)?.raw == "Crypt" || (f as? PArr)?.items?.any { (it as? PName)?.raw == "Crypt" } == true
        return crypt
    }

    private fun crypt(m: Method, data: ByteArray, num: Int, gen: Int): ByteArray = when (m) {
        Method.NONE -> data
        Method.AES256 -> aesCbc(key, data)
        Method.RC4, Method.AES128 -> {
            val md = MessageDigest.getInstance("MD5")
            md.update(key)
            md.update(byteArrayOf(num.toByte(), (num shr 8).toByte(), (num shr 16).toByte(), gen.toByte(), (gen shr 8).toByte()))
            if (m == Method.AES128) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
            val k = md.digest().copyOf(minOf(n + 5, 16))
            if (m == Method.RC4) rc4(k, data) else aesCbc(k, data)
        }
    }

    companion object {
        private val PAD = byteArrayOf(
            0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(), 0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
        )

        fun of(enc: PDict, trailer: PDict): Security? {
            val v = enc.num("V")?.toInt() ?: 0
            val r = enc.num("R")?.toInt() ?: return null
            val bytes = { k: String -> (enc[k] as? PStr)?.bytes ?: ByteArray(0) }
            val id0 = ((trailer["ID"] as? PArr)?.items?.firstOrNull() as? PStr)?.bytes ?: ByteArray(0)
            fun method(filter: String?): Method {
                if (filter == null || filter == "Identity") return if (filter == null && v < 4) Method.RC4 else Method.NONE
                val cf = (enc["CF"] as? PDict)?.get(filter) as? PDict ?: return Method.NONE
                return when (cf.name("CFM")) {
                    "V2" -> Method.RC4
                    "AESV2" -> Method.AES128
                    "AESV3" -> Method.AES256
                    else -> Method.NONE
                }
            }
            val (stm, str) = when (v) {
                1, 2 -> Method.RC4 to Method.RC4
                4, 5 -> method(enc.name("StmF") ?: "Identity") to method(enc.name("StrF") ?: "Identity")
                else -> return null
            }
            if (r !in 2..6) return null
            val n = when {
                r == 2 -> 5
                r >= 5 -> 32
                v >= 4 -> 16
                else -> ((enc.num("Length") ?: 40).toInt() / 8).coerceIn(5, 16)
            }
            val em = (enc["EncryptMetadata"] as? PKw)?.text != "false"
            val o = bytes("O")
            val u = bytes("U")
            if (o.size < 32 || u.size < 32 || (r >= 5 && (o.size < 48 || u.size < 48))) return null
            return Security(r, n, o, u, bytes("OE"), bytes("UE"), (enc.num("P") ?: 0).toInt(), id0, em, stm, str)
        }

        private fun pad(pw: ByteArray): ByteArray = (pw.take(32) + PAD.toList()).take(32).toByteArray()

        private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b)

        private fun xor(k: ByteArray, i: Int) = ByteArray(k.size) { (k[it].toInt() xor i).toByte() }

        fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            var i = 0
            j = 0
            return ByteArray(data.size) { k ->
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
                (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
        }

        /** AES-CBC with the IV in front and PKCS#7 padding after, as PDF writes strings and streams. */
        private fun aesCbc(key: ByteArray, data: ByteArray): ByteArray {
            if (data.size < 32) return if (data.size == 16) ByteArray(0) else data.copyOf(0)
            val body = data.size - 16 - (data.size - 16) % 16
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data, 0, 16))
            val plain = c.doFinal(data, 16, body)
            val pad = plain.last().toInt() and 0xFF
            val padded = pad in 1..16 && plain.size >= pad && (plain.size - pad until plain.size).all { (plain[it].toInt() and 0xFF) == pad }
            return if (padded) plain.copyOf(plain.size - pad) else plain
        }

        private fun aesNoPad(key: ByteArray, data: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            return c.doFinal(data)
        }

        private fun sha(alg: String, b: ByteArray) = MessageDigest.getInstance(alg).digest(b)

        /** The password hash of revision 5, and the hardened one of revision 6. */
        private fun hash(pw: ByteArray, salt: ByteArray, udata: ByteArray, r: Int): ByteArray {
            var k = sha("SHA-256", pw + salt + udata)
            if (r == 5) return k
            var i = 0
            while (true) {
                val one = pw + k + udata
                val k1 = ByteArray(one.size * 64)
                for (rr in 0 until 64) System.arraycopy(one, 0, k1, rr * one.size, one.size)
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                val e = c.doFinal(k1)
                val mod = (0 until 16).sumOf { e[it].toInt() and 0xFF } % 3
                k = sha(if (mod == 0) "SHA-256" else if (mod == 1) "SHA-384" else "SHA-512", e)
                i++
                if (i >= 64 && (e.last().toInt() and 0xFF) <= i - 32) break
            }
            return k.copyOf(32)
        }
    }
}
