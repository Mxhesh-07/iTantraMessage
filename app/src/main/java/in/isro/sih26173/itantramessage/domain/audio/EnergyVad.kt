package `in`.isro.sih26173.itantramessage.domain.audio

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Simple RMS/energy VAD with hangover. No ML, deterministic, small.
 */
class EnergyVad(config: VadConfig = VadConfig()) : VoiceActivityDetector {
    override var sampleRate: Int = config.sampleRate
    override var frameMs: Long = config.frameMs
    override var speechThresholdDb: Double = config.speechThresholdDb
    override var silenceThresholdDb: Double = config.silenceThresholdDb
    override var hangoverMs: Long = config.hangoverMs

    private var speaking = false
    private var lastSpeechTimeMs: Long = 0
    private var frameCount: Long = 0

    override val isActive: Boolean
        get() = speaking

    override fun isSpeech(samples: ShortArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return speaking
        val rms = computeRms(samples, offset, length)
        val db = rmsToDb(rms)
        val now = frameCount * frameMs
        frameCount++

        val aboveSpeech = db >= speechThresholdDb
        val belowSilence = db < silenceThresholdDb

        if (aboveSpeech) {
            speaking = true
            lastSpeechTimeMs = now
            return true
        }
        if (speaking && belowSilence) {
            val since = now - lastSpeechTimeMs
            if (since >= hangoverMs) {
                speaking = false
            }
        }
        return speaking
    }

    override fun reset() {
        speaking = false
        lastSpeechTimeMs = 0
        frameCount = 0
    }

    private fun computeRms(samples: ShortArray, offset: Int, length: Int): Double {
        var sumSq: Long = 0
        val end = (offset + length).coerceAtMost(samples.size)
        var i = offset
        while (i < end) {
            val v = samples[i].toLong()
            sumSq += v * v
            i++
        }
        val n = end - offset
        if (n <= 0) return 0.0
        return sqrt(sumSq.toDouble() / n.toDouble())
    }

    private fun rmsToDb(rms: Double): Double {
        if (rms <= 1e-6) return -120.0
        return 20.0 * ln(rms / 32767.0) / ln(2.718281828459045)
    }
}
