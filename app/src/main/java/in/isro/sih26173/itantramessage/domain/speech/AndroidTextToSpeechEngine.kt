package `in`.isro.sih26173.itantramessage.domain.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Android's built-in TTS. Can be fully offline if language packs are installed.
 * This satisfies "offline TTS" without pulling new heavy deps initially.
 */
class AndroidTextToSpeechEngine(private val context: Context) : TextToSpeechEngine {
    private var tts: TextToSpeech? = null
    private var ready = false

    override val isReady: Boolean
        get() = ready

    override suspend fun initialize() = suspendCancellableCoroutine { cont ->
        if (ready) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        tts = TextToSpeech(context) { status ->
            when (status) {
                TextToSpeech.SUCCESS -> {
                    ready = true
                    cont.resume(Unit)
                }
                else -> {
                    ready = false
                    Log.w(TAG, "TTS init failed: $status")
                    cont.resume(Unit)
                }
            }
        }
        cont.invokeOnCancellation {
            // ignore
        }
    }

    override suspend fun speak(text: String, languageCode: String): TtsResult {
        if (!ready || text.isEmpty()) return TtsResult(success = true)
        val locale = Locale.forLanguageTag(languageCode.ifEmpty { "en" })
        tts?.language = locale
        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "itantra-tts-${System.nanoTime()}")
        val ok = res == TextToSpeech.SUCCESS
        return TtsResult(success = ok)
    }

    override suspend fun stop() {
        tts?.stop()
    }

    override fun release() {
        ready = false
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "AndroidTtsEngine"
    }
}
