package dev.niccc2007.filet.handlers

/**
 * The platform's own lines about a decoder that would not start, copied into Filet's log.
 *
 * ## Why
 *
 * Bug identified here: the phone's hardware decoder turned down a 2160 x 3402, 60 fps file every
 * time, and all the exception said was `IllegalStateException`, with no message. The reason is
 * written by the platform's codec layer to the system log, in this app's own process, and the
 * system log keeps only a few minutes - by the time anyone asked, it was gone. So when a decoder
 * refuses, the lines are read straight away, from this process only (no permission is needed for
 * that), and kept with the rest of the run.
 */
internal object CodecLog {

    /**
     * Tags the codec layer writes under, in this process: these exactly, and any tag starting with
     * one of [FAMILIES]. Bug identified here: "MediaCodec" was matched as a start too, which let in
     * Media3's own `MediaCodecRenderer` and `MediaCodecVideoRenderer`, and the forty lines kept
     * after a refusal were forty lines of a Java stack trace with the platform's own lines pushed
     * out.
     */
    private val TAGS = setOf("MediaCodec", "ACodec")
    private val FAMILIES = listOf("CCodec", "Codec2", "C2")

    /**
     * The lines worth keeping from a dump of this process's log: the codec layer's warnings and
     * errors, and any line of it that says something failed. Newest last, at most [max]. Pure.
     */
    fun relevant(lines: List<String>, max: Int = MAX_LINES): List<String> =
        lines.filter { line ->
            val tag = tagOf(line) ?: return@filter false
            val codec = tag in TAGS || FAMILIES.any { tag.startsWith(it) }
            if (!codec) return@filter false
            val level = levelOf(line)
            level == 'E' || level == 'W' || line.contains("fail", ignoreCase = true) || line.contains("error", ignoreCase = true)
        }.takeLast(max)

    /** The tag of a `logcat -v time` line: `11-13 16:01:52.699 E/CCodec  ( 1234): ...`. */
    fun tagOf(line: String): String? {
        val m = TIME_LINE.find(line) ?: return null
        return m.groupValues[2].trim()
    }

    private fun levelOf(line: String): Char? = TIME_LINE.find(line)?.groupValues?.get(1)?.firstOrNull()

    private val TIME_LINE = Regex("""^\S+ \S+ ([VDIWEF])/([^(]+)\(""")

    const val MAX_LINES = 40
    private const val SETTLE_MS = 1_500L

    /**
     * Reads this process's log and writes the [relevant] lines to Filet's log under "video". Off
     * the main thread, once per call; a phone that will not run `logcat` simply adds nothing.
     */
    fun captureAfterRefusal() {
        Thread({
            // A moment first: the first refusal is followed within a few hundred milliseconds by
            // the retry and the next decoders, and their lines belong in the same capture.
            runCatching { Thread.sleep(SETTLE_MS) }
            val lines = runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "600", "--pid=" + android.os.Process.myPid())
                    .redirectErrorStream(true)
                    .start()
                val text = p.inputStream.bufferedReader().use { it.readLines() }
                p.waitFor()
                text
            }.getOrDefault(emptyList())
            val keep = relevant(lines)
            dev.niccc2007.filet.log.FiletLog.i("video") {
                if (keep.isEmpty()) "codec log: nothing about the refusal in this process's log" else "codec log, ${keep.size} lines:"
            }
            for (l in keep) dev.niccc2007.filet.log.FiletLog.i("video") { "  $l" }
        }, "filet-codec-log").apply { isDaemon = true }.start()
    }
}
