package dev.niccc2007.filet.playback

/**
 * A radix-2 FFT, for onset detection.
 *
 * ## Why there is an FFT here at all
 *
 * The first onset detector measured broadband energy and called a rise above the recent
 * average a beat. That is the simplest thing that works and it is wrong in a way you can
 * hear: a snare under a sustained pad adds almost nothing to the total energy, so it is
 * missed, while a swell in the pad adds a lot and fires. Reported as the pulse being
 * inaccurate, which it was.
 *
 * What the literature uses instead - aubio, librosa, Essentia all do this - is **spectral
 * flux**: take the spectrum every few milliseconds and sum how much each bin went UP since
 * the last one. A snare is a sudden arrival of energy across many bins at once and shows up
 * clearly; a pad swelling is a slow change in a few bins and does not. It needs a spectrum,
 * which needs this.
 *
 * ## Why it is written out rather than taken from somewhere
 *
 * It is forty lines and the alternative is a dependency for forty lines. Iterative rather
 * than recursive, in place, and over plain `FloatArray` - a recursive one allocating arrays
 * per level would spend more time in the garbage collector than in the transform, and this
 * runs twelve thousand times for a four-minute track.
 */
object Fft {

    /**
     * In-place complex FFT. [re] and [im] must be the same length and a power of two.
     *
     * Decimation in time: the bit-reversal permutation first, then log2(n) passes of
     * butterflies over doubling spans. The twiddle factors are stepped by repeated complex
     * multiplication within a span rather than recomputed - two multiplies instead of two
     * trigonometric calls, which is most of the running time at this size.
     */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        if (n <= 1) return
        require(n and (n - 1) == 0) { "FFT length must be a power of two, was $n" }

        // Bit reversal.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(angle).toFloat()
            val wIm = kotlin.math.sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** A Hann window of [n] points, which is the usual one and is cached by the caller. */
    fun hann(n: Int) = FloatArray(n) {
        (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * it / (n - 1))).toFloat()
    }
}
