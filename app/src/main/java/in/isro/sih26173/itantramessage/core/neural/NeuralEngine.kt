package `in`.isro.sih26173.itantramessage.core.neural

/**
 * Abstraction for offline neural STT/VAD/TTS.
 * Model integration point (Sherpa-ONNX) - no network calls.
 */
interface NeuralEngine {
    suspend fun init()
    suspend fun vadFrame(pcm: ShortArray): Boolean // voice active?
    suspend fun sttPartial(pcm: ShortArray): String
    suspend fun sttFinal(): String
    suspend fun tts(text: String, lang: String): ByteArray // PCM/wav bytes
    fun release()
    val ramMb: Long

    // Streaming API (non-breaking, optional usage)
    suspend fun pushFrame(pcm: ShortArray) {}
    suspend fun endUtterance(): String = sttFinal()
    suspend fun resetStream() {}
}
