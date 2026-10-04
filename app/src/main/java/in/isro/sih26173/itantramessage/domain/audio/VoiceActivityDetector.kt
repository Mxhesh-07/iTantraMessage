package `in`.isro.sih26173.itantramessage.domain.audio

/**
 * Lightweight voice activity detection (energy + threshold + hysteresis).
 * Pure domain (no Android types).
 */
interface VoiceActivityDetector {
    fun isSpeech(samples: ShortArray, offset: Int, length: Int): Boolean
    fun reset()
    var silenceThresholdDb: Double
    var speechThresholdDb: Double
    var hangoverMs: Long
    var sampleRate: Int
    var frameMs: Long
    val isActive: Boolean
}

data class VadConfig(
    val sampleRate: Int = 16000,
    val frameMs: Long = 20,
    val speechThresholdDb: Double = -50.0,
    val silenceThresholdDb: Double = -60.0,
    val hangoverMs: Long = 300,
) {
    val frameSize: Int = ((sampleRate * frameMs) / 1000).toInt().coerceAtLeast(1)
}
