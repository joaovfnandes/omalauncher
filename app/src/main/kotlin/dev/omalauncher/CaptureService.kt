package dev.omalauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * FORK-LOCAL (spectrum/local): the foreground holder that
 * [android.media.MediaProjection] requires (API 29+ refuses
 * `getMediaProjection()` without a mediaProjection-type foreground service).
 * Started just before the projection is taken, stopped when it ends. The
 * notification says what it does; nothing is recorded, only spectrum bands.
 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Audio spectrum", NotificationManager.IMPORTANCE_LOW))
        val notification =
            Notification
                .Builder(this, CHANNEL)
                .setContentTitle("Spectrum capture")
                .setContentText("Reading playback audio for the spectrum widget")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "spectrum"
    }
}
