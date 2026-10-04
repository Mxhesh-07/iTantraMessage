package `in`.isro.sih26173.itantramessage.domain.speech

interface TextToSpeechEngine {
    suspend fun initialize()
    suspend fun speak(text: String, languageCode: String): TtsResult
    suspend fun stop()
    fun release()
    val isReady: Boolean
}

data class TtsResult(
    val success: Boolean = false,
    val durationMs: Long = 0L,
    val error: String? = null,
)
