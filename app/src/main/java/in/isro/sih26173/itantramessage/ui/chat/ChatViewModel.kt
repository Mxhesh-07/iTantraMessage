package `in`.isro.sih26173.itantramessage.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import `in`.isro.sih26173.itantramessage.ItantraMessageApp
import `in`.isro.sih26173.itantramessage.data.database.DeliveryStatus
import `in`.isro.sih26173.itantramessage.data.database.MessageDao
import `in`.isro.sih26173.itantramessage.data.database.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import `in`.isro.sih26173.itantramessage.core.neural.NeuralEngineFactory
import `in`.isro.sih26173.itantramessage.domain.speech.RecognitionResult
import kotlinx.coroutines.delay

/** One message as the chat list draws it. */
data class ChatMessage(
    val id: String,
    val text: String?,
    val timestamp: Long,
    val status: DeliveryStatus,
    val isMine: Boolean,
) {
    /**
     * Whether the bubble is empty because the text failed to decrypt.
     *
     * Distinguishing "unreadable" from "empty" matters: a bubble with no text and no
     * marker looks like a rendering bug, while one labelled "Could not read" tells the
     * user the message arrived and something is wrong with the key.
     */
    val isUnreadable: Boolean get() = text == null
}

/** Everything the chat screen draws, as one object. */
enum class VoiceMode {
    PTT,
    CONTINUOUS,
}

data class ChatUiState(
    val peerName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val canSend: Boolean = false,
    val isLoading: Boolean = true,

    /**
     * Whether the voice controls should be drawn at all.
     *
     * Not the same question as "has the user enabled voice", and the difference is the
     * point of the field. Voice is on by default in the sense that nobody has to opt in --
     * but the offline engine behind it is a stub with no model behind it, so a microphone
     * drawn today would be a control that cannot recognise anything.
     *
     * So the controls appear only when [NeuralEngineFactory.isRealEngineAvailable] is true,
     * which is the same constant that chooses the implementation. When the ONNX models
     * land and the flag is flipped, the controls appear without a further UI change.
     */
    val voiceAvailable: Boolean = false,
    val voiceCapturing: Boolean = false,
    val voiceSpeaking: Boolean = false,
    val voiceInputLang: String = "EN",
    val voiceOutputLang: String = "EN",
    val voiceTranscript: String = "",
    val voiceMode: VoiceMode = VoiceMode.PTT,
)

/**
 * Chat screen ViewModel.
 *
 * ## Decryption happens here, not in the composable
 *
 * A stored message is ciphertext, so every visible message needs a Keystore operation to
 * display. Doing that inside a `Text()` lambda would run on every recomposition and on
 * every scroll frame -- dozens of Keystore round trips per second on a device that has
 * 2 GB of RAM. Instead each row is decrypted once when the underlying entity changes and
 * the result is held in [ChatMessage.text], so recomposition reads a String.
 */
class ChatViewModel(
    application: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val container = (application as ItantraMessageApp).container
    private val identity = container.identity
    private val nearby = container.nearby

    /**
     * The shared repository from the application container, not a new one.
     *
     * A second repository here would open a second receive loop and, when the user
     * reconnects, close the link the first one was using. See [ItantraMessageApp].
     */
    private val repository = container.repository

    private val dao: MessageDao = container.messageDao
    private val speechRecognizer = NeuralEngineFactory.createSpeechRecognizer(application)

    /**
     * The conversation, taken from the navigation arguments.
     *
     * `SavedStateHandle` rather than a constructor parameter so the value survives process
     * death. A chat screen restored after the system killed the app must not come back
     * with a null conversation and render an empty list that looks like lost messages.
     */
    private val conversationId: String = checkNotNull(savedState[ARG_CONVERSATION_ID]) {
        "ChatViewModel requires a $ARG_CONVERSATION_ID navigation argument"
    }

    private val peerId: String = checkNotNull(savedState[ARG_PEER_ID]) {
        "ChatViewModel requires a $ARG_PEER_ID navigation argument"
    }

    private val _draft = MutableStateFlow("")

    /**
     * Rows paired with their decrypted text, decrypted once per database change.
     *
     * The decryption cannot happen inside the `combine` block below, because that block is
     * a `transform` operator and therefore not a suspend context -- calling the Keystore
     * from it does not compile. It is also the wrong place on the merits: doing it there
     * would re-decrypt every message on every draft keystroke, because the draft is half of
     * the combine. So it is its own stage, and the draft no longer triggers any crypto.
     */
    private val decrypted: Flow<List<ChatMessage>> =
        dao.observeConversation(conversationId)
            // mapNotNull over a null-text placeholder: the row is kept even when the
            // ciphertext will not decrypt, because dropping it would make an undecryptable
            // message look like one that was never received.
            .map { entities ->
                entities.map { entity ->
                    ChatMessage(
                        id = entity.messageId,
                        text = repository.plaintextOf(entity),
                        timestamp = entity.timestamp,
                        status = DeliveryStatus.entries
                            .firstOrNull { it.name == entity.status } ?: DeliveryStatus.PENDING,
                        isMine = entity.senderId == identity.id,
                    )
                }
            }

    /**
     * The id this screen should label the peer by.
     *
     * The navigation argument is a snapshot taken when the user tapped the device, which is
     * *before* the handshake. On a first connection to a peer that has never announced an id,
     * that snapshot is stale or missing -- and since a device id is minted per install, a
     * reinstall on the other phone makes it permanently wrong. The repository's live value
     * replaces it as soon as the peer's hello arrives, with the argument as the fallback for
     * the brief moment before that.
     */
    private val livePeerId: Flow<String> = repository.peerDeviceId
        .filterNotNull()
        .map { it }
        .onStart { emit(peerId) }

    /**
     * The voice settings, as one value rather than three flows.
     *
     * Grouped because `combine` has typed overloads only up to five sources, and this
     * state already combines five. Adding a sixth for the capture mode pushed it onto the
     * untyped `Array<Any>` overload, which throws at runtime rather than at compile time
     * when the arity and the lambda disagree -- a worse failure than the duplication it
     * would have avoided. One small data class also makes [toggleVoiceMode] a single
     * atomic write instead of a read-modify-write across two flows.
     */
    private val _voiceSettings = MutableStateFlow(VoiceSettings())

    private data class VoiceSettings(
        val inputLang: String = "EN",
        val outputLang: String = "EN",
        val mode: VoiceMode = VoiceMode.PTT,
    )

    /**
     * Whether the microphone is currently capturing.
     *
     * This was hardcoded `false` in the state combine, which is why the record indicator could
     * never light up: the UI had no way to learn that a recording was running. It is a flow
     * rather than a field because capture is driven from the recogniser and the indicator has to
     * follow it.
     *
     * Added as a fifth combine source. That is the limit of `combine`'s typed overloads, so
     * this must stay a single value; adding a sixth would drop to the untyped `Array<Any>`
     * overload and throw at runtime instead of failing to compile.
     */
    private val _voiceCapturing = MutableStateFlow(false)

    val state: StateFlow<ChatUiState> = combine(
        decrypted,
        _draft,
        livePeerId,
        _voiceSettings,
        _voiceCapturing,
    ) { messages: List<ChatMessage>, draft: String, currentPeer: String, voice: VoiceSettings, capturing: Boolean ->
        ChatUiState(
            peerName = peerName(currentPeer),
            messages = messages,
            draft = draft,
            canSend = draft.isNotBlank(),
            isLoading = false,
            voiceAvailable = NeuralEngineFactory.isRealEngineAvailable(application),
            voiceCapturing = capturing,
            voiceSpeaking = false,
            voiceInputLang = voice.inputLang.uppercase(),
            voiceOutputLang = voice.outputLang.uppercase(),
            voiceMode = voice.mode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(peerName = peerName(peerId)),
    )

    /**
     * The peer's display name, from the bonded list.
     *
     * Falls back to the raw id, which is what a user sees if the peer is not bonded. A
     * missing name must not be a blank title bar.
     */
    /**
     * The peer's display name, from the bonded list.
     *
     * [peerId] is the app-level device id (`IT-XXXXXX`), not the Bluetooth MAC, so the bonded
     * devices have to be resolved *through* the address-to-id map rather than matched on the
     * id directly. Comparing an id to a MAC never matches, which is why the title bar read
     * `IT-17E628` instead of "realme Narzo 10A" on a working connection.
     *
     * Falls back to the raw id, which is what a user sees if the peer is not bonded. A missing
     * name must not be a blank title bar.
     */
    private fun peerName(deviceId: String): String =
        nearby.bondedDevices()
            .firstOrNull { container.peers.deviceIdFor(it.address) == deviceId }
            ?.displayName
            ?: deviceId

    /** Update the draft. Held in a flow so it survives rotation without a saved-state key. */
    fun onDraftChanged(text: String) {
        _draft.value = text
    }

    /**
     * Send the current draft.
     *
     * The draft is cleared immediately after the row is stored, not after the message
     * arrives. Waiting for arrival would leave the text in the box during a slow connect,
     * and the user would naturally tap Send again -- producing a duplicate, which is
     * exactly what the dedup logic exists to prevent.
     */
    fun send() {
        val text = _draft.value
        if (text.isBlank()) return
        _draft.value = ""
        viewModelScope.launch {
            repository.send(peerId, text)
        }
    }

    /**
     * Set both languages at once, as the language pickers do.
     *
     * The two `_uiVoice*` flows that used to sit here were never read by anything, and
     * [setVoiceInputLang] wrote to a third pair again -- three places holding one piece of
     * state, which is how the mode toggle ended up writing to a field no composable was
     * observing. There is now one [VoiceSettings] value and every setter goes through it.
     */
    fun setVoiceLanguages(inputLang: String, outputLang: String = inputLang) {
        _voiceSettings.update {
            it.copy(inputLang = inputLang.uppercase(), outputLang = outputLang.uppercase())
        }
    }

    /**
     * Begin offline speech capture.
     *
     * Starts the microphone and buffers PCM for Whisper. Nothing is decoded yet: Whisper is
     * non-streaming, so there is no result to show until capture stops.
     */
    fun startVoice() {
        viewModelScope.launch { beginVoiceCapture() }
    }

    /**
     * Stop capture and decode.
     *
     * The transcript goes into the draft and is **not** sent automatically. It used to be sent
     * on the spot, which is the wrong default for a recogniser that has no word-error-rate
     * measurement behind it: a misheard message would be delivered before the user could see
     * it. The user reads it and presses send.
     */
    fun stopVoice() {
        viewModelScope.launch { finishVoiceCapture() }
    }

    /** Push-to-talk: press to capture. Identical to [startVoice]; the UI differs, not the work. */
    fun startVoicePTT() {
        viewModelScope.launch { beginVoiceCapture() }
    }

    /** Push-to-talk: release to decode. */
    fun stopVoicePTT() {
        viewModelScope.launch { finishVoiceCapture() }
    }

    private suspend fun beginVoiceCapture() {
        if (_voiceCapturing.value) return
        speechRecognizer.languageCode = whisperLanguage(_voiceSettings.value.inputLang)
        runCatching {
            speechRecognizer.start()
            _voiceCapturing.value = speechRecognizer.isActive
        }.onFailure {
            _voiceCapturing.value = false
        }
    }

    private suspend fun finishVoiceCapture() {
        val wasCapturing = _voiceCapturing.value
        _voiceCapturing.value = false

        val result: RecognitionResult = runCatching { speechRecognizer.endUtterance() }
            .getOrElse { RecognitionResult(text = "", isFinal = true) }

        if (!wasCapturing || result.text.isBlank()) return

        _draft.value = (if (_draft.value.isNotBlank()) _draft.value + " " else "") + result.text
    }

    /**
     * Map the UI language tag to the ISO 639-1 code Whisper expects.
     *
     * The UI carries two-letter codes already (`EN`, `HI`, `TA`, ...), which is what Whisper
     * wants. The region-qualified `hi-IN` form it used to build here was wrong for this engine:
     * Whisper does not recognise `hi-IN`, and would have fallen back to English and returned a
     * fluent, wrong transcript -- the most damaging possible failure in a messaging app.
     */
    private fun whisperLanguage(inputLang: String): String =
        when (inputLang.trim().uppercase()) {
            "HI" -> "hi"
            "TA" -> "ta"
            "TE" -> "te"
            "KN" -> "kn"
            "ML" -> "ml"
            "BN" -> "bn"
            "MR" -> "mr"
            "GU" -> "gu"
            "OR", "ODI" -> "or"
            "EN" -> "en"
            else -> inputLang.trim().lowercase().substringBefore('-').ifEmpty { "en" }
        }

    /**
     * Switch between push-to-talk and continuous capture.
     *
     * This used to be a literal no-op with the comment "state handled via combine if
     * needed", which is what a control that does nothing looks like from the inside: the
     * toggle was wired to a function that discarded its argument and never changed a
     * field, so the label stayed on `PTT` whatever the user pressed.
     *
     * Now it writes a flow that [state] combines. That alone is still not enough for the
     * control to be *shippable* -- the capture engine behind both modes is a stub, so
     * [ChatUiState.voiceAvailable] keeps the toggle off screen until it is real. But a
     * toggle whose state does not change is a defect in its own right, and fixing only the
     * visibility would have left it to reappear broken the moment the flag was flipped.
     */
    fun toggleVoiceMode() {
        _voiceSettings.update { current ->
            current.copy(
                mode = if (current.mode == VoiceMode.PTT) VoiceMode.CONTINUOUS else VoiceMode.PTT,
            )
        }
    }

    fun setVoiceInputLang(code: String) {
        _voiceSettings.update { it.copy(inputLang = code.uppercase()) }
    }

    fun setVoiceOutputLang(code: String) {
        _voiceSettings.update { it.copy(outputLang = code.uppercase()) }
    }

    // NOTE: no onCleared() here, deliberately. The repository is process-scoped and
    // deliberately outlives this ViewModel, so detaching here would close the link the
    // moment the user rotates the device -- because onCleared runs on a configuration
    // change as well as on a real teardown. That would present as "my connection drops
    // every time I turn the phone". Closing the link belongs to an explicit disconnect
    // action, which HomeScreen owns.
    companion object {
        const val ARG_CONVERSATION_ID = "conversationId"
        const val ARG_PEER_ID = "peerId"
    }
}
