package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
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
 * A live stereo spectrum of what's playing on the phone, for
 * `source s = android.spectrum` (also `android.audioSpectrum`).
 *
 * Captures this phone's media playback (never the mic) through
 * [AudioPlaybackCaptureConfiguration] into an [AudioRecord], runs each channel
 * through [Fft], and emits at most every 33 ms (~30 fps). No foreground service:
 * capture runs in the app process while a widget subscribes, and stops when the
 * last subscriber goes away.
 *
 * Value shape: `{l: [32 nums], r: [32 nums], available, capturing}`. Bands run
 * low→high (index 0 is ~50 Hz bass); a widget mirrors them for display.
 * Without RECORD_AUDIO, `available` is false and the bands are zeros; grant it
 * with `adb shell pm grant <package> android.permission.RECORD_AUDIO` (the
 * debug fork's package is `dev.omalauncher.debug`).
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
            while (CaptureSession.projection == null) {
                // MainActivity asks on the way in; wait for the consent instead of failing.
                val wait = zeros(capturing = false, available = true)
                last = wait
                trySend(wait)
                delay(2000)
            }
            val record = openCapture()
            if (record == null) {
                // The target disallows capture, or audio is otherwise unreachable: zeros, not a crash.
                Log.w("spectrum", "AudioRecord capture refused (target opted out?); emitting zeros")
                val off = zeros(capturing = false, available = true)
                last = off
                trySend(off)
                awaitClose { }
                return@callbackFlow
            }
            val reader =
                launch(Dispatchers.IO) {
                    val pcm = ShortArray(Fft.WINDOW * 2)
                    val left = FloatArray(Fft.WINDOW)
                    val right = FloatArray(Fft.WINDOW)
                    var filled = 0
                    var lastEmit = 0L
                    runCatching { record.startRecording() }
                    while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = record.read(pcm, filled * 2, pcm.size - filled * 2)
                        if (n <= 0) {
                            delay(10)
                            continue
                        }
                        filled += n / 2
                        if (filled < Fft.WINDOW) continue
                        filled = 0
                        Fft.channel(pcm, Fft.WINDOW, 0, left)
                        Fft.channel(pcm, Fft.WINDOW, 1, right)
                        val (l, r) = Fft.stereo(left, right)
                        last = frame(l, r, capturing = true)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= 33) {
                            lastEmit = now
                            trySend(last)
                        }
                    }
                }
            awaitClose {
                reader.cancel()
                runCatching { record.stop() }
                record.release()
                last = zeros(capturing = false, available = true)
            }
        }.conflate()

    /** The capture tap: media/game/unknown usages, 48 kHz stereo 16-bit. Null when capture refuses.
     *  Needs [CaptureSession]'s MediaProjection: the Builder has no no-arg constructor. */
    private fun openCapture(): AudioRecord? {
        val projection = CaptureSession.projection ?: return null
        return runCatching {
            val tap =
                AudioPlaybackCaptureConfiguration
                    .Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            val format =
                AudioFormat
                    .Builder()
                    .setSampleRate(Fft.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            val min = AudioRecord.getMinBufferSize(Fft.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord
                .Builder()
                .setAudioPlaybackCaptureConfig(tap)
                .setAudioFormat(format)
                .setBufferSizeInBytes((min.coerceAtLeast(Fft.WINDOW * 4)) * 2)
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
    }

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
                "reason" to Str("grant RECORD_AUDIO"),
            ),
        )
    }
}
