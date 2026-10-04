package `in`.isro.sih26173.itantramessage.data.nearby

import `in`.isro.sih26173.itantramessage.domain.speech.SpeechRecognizer
import `in`.isro.sih26173.itantramessage.domain.speech.TextToSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Handles speech I/O on top of text transport. Not wired into the text path yet
 * (no breaking changes). Holds state for future PTT/conversation modes.
 */
class VoiceStreamHandler(
    private val recognizer: SpeechRecognizer,
    private val tts: TextToSpeechEngine,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VoiceStreamState())
    val state: StateFlow<VoiceStreamState> = _state.asStateFlow()

    private var captureJob: Job? = null
    private var speaking = false

    suspend fun initialize() {
        tts.initialize()
    }

    fun startCapture(onFinalText: (String) -> Unit) {
        if (speaking) return
        captureJob?.cancel()
        captureJob = scope.launch {
            recognizer.reset()
            recognizer.start()
            _state.value = _state.value.copy(capturing = true, listening = true)
            // Frame consumption to be wired to MicRecorder later.
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        scope.launch { recognizer.stop() }
        _state.value = _state.value.copy(capturing = false, listening = false)
    }

    suspend fun playReceived(text: String) {
        if (text.isBlank()) return
        speaking = true
        _state.value = _state.value.copy(speaking = true)
        tts.speak(text, _state.value.outputLanguageCode)
        _state.value = _state.value.copy(speaking = false)
        speaking = false
    }

    fun setLanguages(input: String, output: String) {
        _state.value = _state.value.copy(
            inputLanguageCode = input,
            outputLanguageCode = output,
        )
        recognizer.languageCode = input
    }

    fun release() {
        captureJob?.cancel()
        scope.launch { tts.release() }
    }
}

data class VoiceStreamState(
    val capturing: Boolean = false,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val inputLanguageCode: String = "en",
    val outputLanguageCode: String = "en",
)
