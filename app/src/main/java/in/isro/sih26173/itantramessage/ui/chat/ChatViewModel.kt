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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
data class ChatUiState(
    val peerName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val canSend: Boolean = false,
    val isLoading: Boolean = true,
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

    val state: StateFlow<ChatUiState> = combine(
        decrypted,
        _draft,
    ) { messages, draft ->
        ChatUiState(
            peerName = peerName(peerId),
            messages = messages,
            draft = draft,
            canSend = draft.isNotBlank(),
            isLoading = false,
        )
    }.stateIn(
        // Survives a configuration change for 5 seconds: a rotation destroys and recreates
        // the collector, and anything shorter would show an empty list for a frame.
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
    private fun peerName(address: String): String =
        nearby.bondedDevices().firstOrNull { it.address == address }?.displayName
            ?: address

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
