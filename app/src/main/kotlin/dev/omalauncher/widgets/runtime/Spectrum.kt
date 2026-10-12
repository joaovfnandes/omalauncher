package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A live spectrum of what's playing on the phone, for
 * `source s = android.spectrum` (also `android.audioSpectrum`).
 *
 * Captures the output mix (never the mic) through [Visualizer] on session 0,
 * runs the mono waveform through [Fft], and emits at most every 33 ms
 * (~30 fps). No foreground service: capture runs in the app process while a
 * widget subscribes, and stops when the last subscriber goes away.
 *
 * Mono fallback: [Visualizer] mixes stereo to mono, so `l` and `r` carry the
 * same bands and `stereo` is false. True L/R separation needs
 * AudioPlaybackCapture with a MediaProjection token, which requires a user
 * consent dialog and a foreground service — it can't be granted over adb for
 * an unattended test, so it stays a follow-up. The widget shape stays
 * `{l, r}` so it upgrades without changes.
 *
 * Value shape: `{l: [32 nums], r: [32 nums], available, capturing, stereo}`.
 * Bands run low→high (index 0 is ~50 Hz bass). Without RECORD_AUDIO,
 * `available` is false and the bands are zeros; grant it with
 * `adb shell pm grant <package> android.permission.RECORD_AUDIO` (the debug
 * fork's package is `dev.omalauncher.debug`).
 */
class Spectrum(
    private val context: Context,
) {
    private val granted get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The latest frame, so value() answers between emissions. */
    @Volatile
    private var last: Value = zeros(capturing = false, available = true)

    fun value(): Value = if (granted) last else unavailable()

    /**
     * Emits a fresh spectrum ~30 times a second while subscribed. Deliberately
     * not distinctUntilChanged: every frame differs, and conflation already
     * drops frames a slow widget can't keep up with.
     */
    fun updates(): Flow<Value> =
        callbackFlow {
            while (!granted) {
                trySend(unavailable())
                delay(2000)
            }
            val viz = openVisualizer()
            if (viz == null) {
                // Another app holds the output-mix capture, or audio is otherwise
                // unreachable: zeros, not a crash.
                val off = zeros(capturing = false, available = true)
                last = off
                trySend(off)
                awaitClose { }
                return@callbackFlow
            }
            // Visualizer capture sizes are powers of two; use whatever it granted.
            val window = viz.captureSize.takeIf { it >= 128 } ?: 1024
            val rate = (viz.samplingRate / 1000).takeIf { it > 0 } ?: Fft.SAMPLE_RATE
            val reader =
                launch(Dispatchers.IO) {
                    val wave = ByteArray(window)
                    val mono = FloatArray(window)
                    var lastEmit = 0L
                    while (isActive) {
                        val ok = runCatching { viz.getWaveForm(wave) }.getOrDefault(Visualizer.ERROR)
                        if (ok != Visualizer.SUCCESS) {
                            delay(50)
                            continue
                        }
                        // 8-bit unsigned with 128 as silence, to floats in -1..1.
                        for (i in wave.indices) mono[i] = ((wave[i].toInt() and 0xFF) - 128) / 128f
                        val bands = Fft.logBands(Fft.magnitudes(mono), sampleRate = rate, fftSize = window)
                        last = frame(bands, bands, capturing = true)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= 33) {
                            lastEmit = now
                            trySend(last)
                        }
                        delay(33)
                    }
                }
            awaitClose {
                reader.cancel()
                runCatching { viz.setEnabled(false) }
                viz.release()
                last = zeros(capturing = false, available = true)
            }
        }.conflate()

    /** The output-mix tap: session 0, largest capture size, normalized scaling. Null when capture refuses. */
    private fun openVisualizer(): Visualizer? =
        runCatching {
            Visualizer(0).apply {
                val max = runCatching { Visualizer.getCaptureSizeRange().getOrNull(1) }.getOrNull() ?: 1024
                if (setCaptureSize(max.coerceIn(128, 1024)) != Visualizer.SUCCESS) {
                    throw IllegalStateException("capture size $max refused")
                }
                setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
                if (setEnabled(true) != Visualizer.SUCCESS || !enabled) {
                    throw IllegalStateException("visualizer refused to enable")
                }
            }
        }.getOrNull()

    private fun frame(
        l: FloatArray,
        r: FloatArray,
        capturing: Boolean,
    ): Value =
        Obj(
            mapOf(
                "l" to Lst(l.map { Num(it.toDouble()) }),
                "r" to Lst(r.map { Num(it.toDouble()) }),
                "available" to bool(true),
                "capturing" to bool(capturing),
                "stereo" to bool(false),
            ),
        )

    private fun zeros(
        capturing: Boolean,
        available: Boolean,
    ): Value {
        val empty = Lst(List(Fft.BINS) { Num(0.0) })
        return Obj(
            mapOf(
                "l" to empty,
                "r" to empty,
                "available" to bool(available),
                "capturing" to bool(capturing),
                "stereo" to bool(false),
            ),
        )
    }

    private fun unavailable(): Value {
        val empty = Lst(List(Fft.BINS) { Num(0.0) })
        return Obj(
            mapOf(
                "l" to empty,
                "r" to empty,
                "available" to bool(false),
                "capturing" to bool(false),
                "stereo" to bool(false),
                "reason" to Str("grant RECORD_AUDIO"),
            ),
        )
    }
}
