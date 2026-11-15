package dev.niccc2007.filet.apk

/**
 * How far along a decompile or a rebuild is, from what each of its steps can count.
 *
 * A job is a row of stages, each weighted by how much work it is: the bytes a copy moves, the
 * classes a dex holds, the files a smali folder holds. A stage reports how far through itself
 * it is, and [at] turns that into a fraction of the whole job. Never 1 until the caller says the
 * job is done: the last stage ending is not the same as the result being written.
 */
internal class ApkProgress(weights: List<Double>) {
    private val starts: DoubleArray
    private val shares: DoubleArray

    init {
        val clean = weights.map { if (it.isFinite() && it > 0) it else 0.0 }
        val total = clean.sum()
        shares = DoubleArray(clean.size) { if (total > 0) clean[it] / total else 1.0 / clean.size.coerceAtLeast(1) }
        starts = DoubleArray(clean.size)
        for (i in 1 until clean.size) starts[i] = starts[i - 1] + shares[i - 1]
    }

    /** The whole job's fraction when [stage] is [within] of the way through itself. */
    fun at(stage: Int, within: Double): Float {
        if (stage !in starts.indices) return if (stage < 0) 0f else LAST
        val f = starts[stage] + shares[stage] * within.coerceIn(0.0, 1.0)
        return f.toFloat().coerceIn(0f, LAST)
    }

    companion object {
        /** The highest a running job shows. */
        const val LAST = 0.99f

        /** How much finer than the last report a new one must be to be worth a row update. */
        const val STEP = 0.005f

        /**
         * A decompile: copying everything that is not code, then each dex turned into smali.
         * Turning code into text is most of the time, so the copy is a tenth of the bar however
         * many bytes it moves, and the dexes share the rest by their size.
         */
        fun decompile(dexSizes: List<Long>): ApkProgress {
            val dex = dexSizes.sumOf { it.coerceAtLeast(0) }.toDouble()
            if (dex <= 0) return ApkProgress(listOf(1.0))
            return ApkProgress(listOf(COPY_SHARE) + dexSizes.map { (1 - COPY_SHARE) * it.coerceAtLeast(0) / dex })
        }

        /**
         * A rebuild: each smali folder assembled, weighted by its file count, then the APK
         * repacked, then signed. Smali assembles a folder in one call, so the bar moves from
         * folder to folder and not within one.
         */
        fun rebuild(smaliFiles: List<Int>): ApkProgress {
            val files = smaliFiles.sumOf { it.coerceAtLeast(0) }.toDouble()
            val assemble = if (files <= 0) smaliFiles.map { ASSEMBLE_SHARE / smaliFiles.size.coerceAtLeast(1) }
            else smaliFiles.map { ASSEMBLE_SHARE * it.coerceAtLeast(0) / files }
            return ApkProgress(assemble + listOf(REPACK_SHARE, SIGN_SHARE))
        }

        const val COPY_SHARE = 0.1
        const val ASSEMBLE_SHARE = 0.8
        const val REPACK_SHARE = 0.15
        const val SIGN_SHARE = 0.05
    }
}

/**
 * Passes a step on only when it says something new: a different line, or a fraction at least
 * [ApkProgress.STEP] further on. Turning a large dex into smali is thousands of classes, and a
 * row update for each would cost more than the work it reports.
 */
internal class ApkReporter(private val onProgress: (String, Float?) -> Unit) {
    private var line: String? = null
    private var shown = -1f

    fun report(step: String, fraction: Float) {
        if (step == line && fraction - shown < ApkProgress.STEP) return
        line = step
        shown = fraction
        onProgress(step, fraction)
    }
}
