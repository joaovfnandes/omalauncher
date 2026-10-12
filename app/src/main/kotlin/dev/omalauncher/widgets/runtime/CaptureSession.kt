package dev.omalauncher.widgets.runtime

import android.media.projection.MediaProjection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * FORK-LOCAL (spectrum/local): the MediaProjection that
 * [AudioPlaybackCaptureConfiguration.Builder] requires. The stock app never
 * captures playback; the fork's [Spectrum] source does.
 *
 * MainActivity asks for it on the way in (the system dialog, or silently when
 * `adb shell appops set <package> PROJECT_MEDIA allow` pre-granted it) and
 * parks it here; [Spectrum] picks it up when a widget subscribes. It lives as
 * long as the process; a restart asks again.
 */
object CaptureSession {
    @Volatile
    var projection: MediaProjection? = null
        private set

    /** Bumped whenever the projection arrives or goes away. */
    val changes = MutableStateFlow(0)

    fun set(p: MediaProjection?) {
        projection?.takeIf { it != p }?.runCatching { stop() }
        projection = p
        changes.update { it + 1 }
    }
}
