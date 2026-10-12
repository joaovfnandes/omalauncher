package dev.omalauncher.widgets.runtime

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin spectrum analysis for `android.spectrum`: no Android imports, so
 * unit tests run on the JVM. The capture side ([Spectrum]) feeds it windows of
 * PCM; this turns each window into 0..1 magnitudes in log-spaced bands.
 *
 * Pipeline per channel: Hann window → radix-2 FFT → magnitudes → log-spaced
 * bands from 50 Hz to 20 kHz → sqrt compression into 0..1.
 */
object Fft {
    /** Samples per FFT window; 2048 at 48 kHz is ~43 ms, fine for ~30 fps meters. */
    const val WINDOW = 2048

    /** Bands per channel; index 0 is bass (~50 Hz), the last is treble (~20 kHz). */
    const val BINS = 32

    const val SAMPLE_RATE = 48000
    const val F_MIN = 50f
    const val F_MAX = 20000f

    /** Hann window of [size] samples; cached per size because it never changes. */
    private val windows = HashMap<Int, FloatArray>()

    fun hann(size: Int): FloatArray =
        windows.getOrPut(size) {
            FloatArray(size) { i -> (0.5f * (1 - cos(2 * PI * i / (size - 1)))).toFloat() }
        }

    /**
     * Magnitudes of the positive-frequency FFT bins: [samples] (a power of two
     * in length) times [window], then an in-place radix-2 FFT. Returns N/2
     * magnitudes, scaled so a full-scale sine peaks at ~1.
     */
    fun magnitudes(
        samples: FloatArray,
        window: FloatArray = hann(samples.size),
    ): FloatArray {
        val n = samples.size
        require(n >= 2 && n and (n - 1) == 0) { "FFT needs a power-of-two window, got $n" }
        val re = FloatArray(n) { samples[it] * window[it] }
        val im = FloatArray(n)

        // Bit-reversed permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j and bit.inv()
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
            }
        }

        // Cooley-Tukey butterflies.
        var len = 2
        while (len <= n) {
            val ang = (-2 * PI / len).toFloat()
            val wRe = cos(ang.toDouble()).toFloat()
            val wIm = sin(ang.toDouble()).toFloat()
            var i = 0
            while (i < n) {
                var cRe = 1f
                var cIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * cRe - im[i + k + len / 2] * cIm
                    val vIm = re[i + k + len / 2] * cIm + im[i + k + len / 2] * cRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = cRe * wRe - cIm * wIm
                    cIm = cRe * wIm + cIm * wRe
                    cRe = nRe
                }
                i += len
            }
            len = len shl 1
        }

        // A full-scale sine has magnitude N/2 at its peak; scale that to 1.
        // (Hann halves coherent gain, so a pure tone reads ~0.5; compression below lifts it.)
        val scale = 2f / n
        return FloatArray(n / 2) { i -> sqrt(re[i] * re[i] + im[i] * im[i]) * scale }
    }

    /**
     * Groups FFT [mags] (from [magnitudes], bin width sampleRate/fftSize) into
     * [bins] log-spaced bands from [fMin] to [fMax] Hz, each the mean of its
     * FFT bins, sqrt-compressed into 0..1. Sqrt (rather than dB) keeps the
     * mapping simple — silence is 0, no floor tuning — while still showing
     * quiet detail next to loud peaks.
     */
    fun logBands(
        mags: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
        fftSize: Int = mags.size * 2,
        bins: Int = BINS,
        fMin: Float = F_MIN,
        fMax: Float = F_MAX,
    ): FloatArray {
        val binHz = sampleRate.toFloat() / fftSize
        val span = ln(fMax / fMin)
        return FloatArray(bins) { b ->
            val lo = fMin * exp(span * b / bins)
            val hi = fMin * exp(span * (b + 1) / bins)
            val from = (lo / binHz).toInt().coerceAtLeast(1)
            val to = (hi / binHz).toInt().coerceAtLeast(from + 1).coerceAtMost(mags.size)
            var sum = 0f
            for (i in from until to) sum += mags[i]
            sqrt((sum / (to - from)).coerceIn(0f, 1f))
        }
    }

    /** One channel of interleaved stereo [pcm] (16-bit) as floats in -1..1. */
    fun channel(
        pcm: ShortArray,
        frames: Int,
        channel: Int,
        out: FloatArray,
    ) {
        for (i in 0 until frames) out[i] = pcm[i * 2 + channel] / 32768f
    }

    /**
     * Both channels of a [WINDOW]-frame stereo window to a pair of band arrays,
     * for [Spectrum]'s capture loop.
     */
    fun stereo(
        left: FloatArray,
        right: FloatArray,
        bins: Int = BINS,
    ): Pair<FloatArray, FloatArray> = logBands(magnitudes(left), bins = bins) to logBands(magnitudes(right), bins = bins)

    /**
     * Two mono float windows (any power-of-two length) to band pairs, for tests:
     * synthesize sines, then check where the peak lands.
     */
    fun spectrumForTest(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int = SAMPLE_RATE,
        bins: Int = BINS,
    ): Pair<FloatArray, FloatArray> = logBands(magnitudes(left), sampleRate, left.size, bins) to logBands(magnitudes(right), sampleRate, right.size, bins)
}
