package dev.omalauncher.widgets

import dev.omalauncher.widgets.runtime.Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {
    /** A sine at [hz], full scale, [n] samples at 48 kHz. */
    private fun sine(
        hz: Double,
        n: Int = Fft.WINDOW,
        rate: Int = Fft.SAMPLE_RATE,
    ) = FloatArray(n) { i -> sin(2 * PI * hz * i / rate).toFloat() }

    private fun peak(bands: FloatArray) = bands.indices.maxBy { bands[it] }

    @Test fun silenceIsZeros() {
        val (l, r) = Fft.spectrumForTest(FloatArray(Fft.WINDOW), FloatArray(Fft.WINDOW))
        assertEquals(Fft.BINS, l.size)
        assertEquals(Fft.BINS, r.size)
        assertTrue("left not silent: ${l.max()}", l.all { it == 0f })
        assertTrue("right not silent: ${r.max()}", r.all { it == 0f })
    }

    @Test fun tonePeaksInItsBand() {
        // 440 Hz in log bands from 50 Hz to 20 kHz over 32 bins lands near bin 11-12.
        val (l, _) = Fft.spectrumForTest(sine(440.0), FloatArray(Fft.WINDOW))
        val p = peak(l)
        assertTrue("440 Hz peaked at bin $p: ${l.toList()}", p in 10..13)
        assertTrue("peak too quiet: ${l[p]}", l[p] > 0.3f)
        assertTrue("bass leaked: bin0=${l[0]}", l[0] < 0.1f)
        assertTrue("treble leaked: bin31=${l[31]}", l[31] < 0.1f)
        assertTrue("out of range: ${l.toList()}", l.all { it in 0f..1f })
    }

    @Test fun channelsAreIndependent() {
        val (l, r) = Fft.spectrumForTest(sine(440.0), sine(5000.0))
        val pl = peak(l)
        val pr = peak(r)
        assertTrue("left peaked at $pl, want bass half", pl < Fft.BINS / 2)
        assertTrue("right peaked at $pr, want treble half", pr > Fft.BINS / 2)
        assertTrue("right leaked into left: l[$pr]=${l[pr]}", l[pr] < 0.15f)
        assertTrue("left leaked into right: r[$pl]=${r[pl]}", r[pl] < 0.15f)
    }

    @Test fun binsRunBassToTreble() {
        // Low tones peak low, high tones peak high, monotonically.
        val peaks = listOf(100.0, 440.0, 2000.0, 8000.0).map { peak(Fft.spectrumForTest(sine(it), sine(it)).first) }
        assertEquals(peaks.sorted(), peaks)
        assertTrue("100 Hz should sit near the bottom, got ${peaks[0]}", peaks[0] <= 6)
        assertTrue("8 kHz should sit near the top, got ${peaks[3]}", peaks[3] >= 24)
    }
}
