package `in`.isro.sih26173.itantramessage.domain.speech

/**
 * Offline speech-to-text recognizer contract. No network calls implied.
 */
interface SpeechRecognizer {
    suspend fun start()
    suspend fun stop()
    suspend fun recognize(frame: ShortArray, offset: Int, length: Int): RecognitionResult
    fun reset()
    val isActive: Boolean
    var languageCode: String
    // Streaming (optional)
    suspend fun pushFrame(frame: ShortArray, offset: Int = 0, length: Int = frame.size) {}
    suspend fun endUtterance(): RecognitionResult = RecognitionResult(text = "", isFinal = true)
    suspend fun resetStream() {}
}

data class RecognitionResult(
    val text: String = "",
    val partial: Boolean = false,
    val confidence: Float = 0f,
    val isFinal: Boolean = false,
)
