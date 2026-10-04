package `in`.isro.sih26173.itantramessage.core.emergency

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager

/**
 * Emergency SOS manager (scaffold). Non-interruptible escalation hooks.
 * Real TTS/siren to be wired when NeuralEngine ready.
 */
class EmergencyAlertManager(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null

    fun trigger(distressText: String) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "iTantra:EmergencyWake"
        ).apply { acquire(10_000) }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
    }

    fun release() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }
}
