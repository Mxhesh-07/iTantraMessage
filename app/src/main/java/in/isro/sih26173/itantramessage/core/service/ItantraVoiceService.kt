package `in`.isro.sih26173.itantramessage.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service for PTT/streaming (production scaffold). Keeps process alive.
 */
class ItantraVoiceService : Service() {
    companion object {
        const val CHANNEL_ID = "itantra_voice"
        const val NOTIF_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iTantra Voice")
            .setContentText("PTT/streaming active")
            .setSmallIcon(android.R.drawable.stat_notify_voicemail)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "iTantra Voice", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
