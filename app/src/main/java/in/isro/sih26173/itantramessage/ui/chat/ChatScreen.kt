package `in`.isro.sih26173.itantramessage.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.isro.sih26173.itantramessage.R
import `in`.isro.sih26173.itantramessage.data.database.DeliveryStatus
import `in`.isro.sih26173.itantramessage.ui.theme.ItantraMessageTheme
import `in`.isro.sih26173.itantramessage.ui.theme.StatusColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import `in`.isro.sih26173.itantramessage.ui.components.VoiceStatusBar
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.TextButton

/**
 * The conversation.
 *
 * ## Scroll behaviour
 *
 * A [LazyColumn] with a [rememberLazyListState], and a [LaunchedEffect] that scrolls to the
 * bottom when the message count grows. Two details matter here:
 *
 *  * The list is keyed by message id, not by index. Without a stable key, receiving a
 *    message inserts a row and every row below it is recomposed with the wrong content for
 *    a frame -- visible as the chat visibly shuffling when a message arrives.
 *  * [reverseLayout] is deliberately **not** used. It would be the more efficient choice
 *    for a chat (it anchors at the bottom for free), but it reverses the reading order for
 *    a screen reader, which then announces the newest message first. Correctness of
 *    reading order beats one less recomposition here.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onVoiceStart: (() -> Unit)? = null,
    onVoiceStop: (() -> Unit)? = null,
    onVoicePTTStart: (() -> Unit)? = null,
    onVoicePTTStop: (() -> Unit)? = null,
    onToggleVoiceMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        // Scroll only when a message is *added*. Keying on the list identity means a
        // status change to an existing message (PENDING to SENT) does not yank the view
        // down while the user is reading history above.
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // imePadding so the input sits above the on-screen keyboard. Without it the
            // Send button is under the keyboard, and on a small screen there is no room to
            // scroll it into view.
            .imePadding(),
    ) {
        ChatTopBar(peerName = state.peerName, onBack = onBack)

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items = state.messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }
        LaunchedEffect(state.messages.size) {
            if (state.messages.isNotEmpty()) {
                listState.animateScrollToItem(state.messages.lastIndex)
            }
        }



        MessageInput(
            draft = state.draft,
            canSend = state.canSend,
            onDraftChanged = onDraftChanged,
            onSend = onSend,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
            onVoicePTTStart = onVoicePTTStart,
            onVoicePTTStop = onVoicePTTStop,
            voiceAvailable = state.voiceAvailable,
            inputLang = state.voiceInputLang,
            voiceMode = state.voiceMode,
            onToggleVoiceMode = onToggleVoiceMode,
        )

        if (state.voiceCapturing || state.voiceSpeaking) {
            VoiceStatusBar(
                capturing = state.voiceCapturing,
                speaking = state.voiceSpeaking,
                inputLang = state.voiceInputLang,
                outputLang = state.voiceOutputLang,
            )
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ChatTopBar(peerName: String, onBack: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics {
                    contentDescription = "Navigate up"
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = peerName.ifEmpty { "Chat" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.chat_encrypted_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One message.
 *
 * ## The 75% width cap
 *
 * A message bubble is capped at 75% of the row width. Without it a long message spans the
 * full width and is indistinguishable from a system message, and the eye has no anchor for
 * where the next message begins. It is the single most effective change to a chat list's
 * readability and it costs one modifier.
 *
 * ## Delivery status
 *
 * A text label, not a coloured dot. The colours are distinguishable from each other and
 * from the surface, but roughly 1 in 12 men has a red-green colour vision deficiency, so a
 * status conveyed by colour alone is unreadable to a real fraction of users. The label is
 * the signal; the colour is the reinforcement.
 */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val container = if (message.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (message.isMine) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // A Row, not a Box with contentAlignment.
    //
    // `Alignment` in Compose is both an interface and a companion factory
    // (`Alignment(horizontal, vertical)`), and that factory is an extension on
    // Alignment.Horizontal that has to be imported separately. Box's contentAlignment wants
    // the full Alignment while Column's horizontalAlignment wants only the horizontal
    // axis, so one `val` cannot satisfy both without that import. A Row sidesteps the
    // whole question: horizontalArrangement and horizontalAlignment both take an
    // Alignment.Horizontal, which is exactly what the if-expression produces.
    //
    // The Row is also the more honest structure -- the bubble and its status label are a
    // column aligned to one edge, not a single object centred in the row.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = container,
                contentColor = content,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.isMine) 16.dp else 4.dp,
                    bottomEnd = if (message.isMine) 4.dp else 16.dp,
                ),
            ) {
                Text(
                    text = message.text
                        ?: stringResource(R.string.error_undecryptable),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = if (message.isMine) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            if (message.isMine) {
                Text(
                    text = statusLabel(message.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColour(message.status),
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                )
            }
        }
    }
}

private fun statusLabel(status: DeliveryStatus): String = when (status) {
    DeliveryStatus.PENDING -> "Queued"
    DeliveryStatus.SENDING -> "Sending"
    DeliveryStatus.SENT -> "Sent"
    DeliveryStatus.DELIVERED -> "Delivered"
    DeliveryStatus.FAILED -> "Failed"
}

@Composable
private fun statusColour(status: DeliveryStatus) = when (status) {
    DeliveryStatus.PENDING -> StatusColors.pending
    DeliveryStatus.SENDING -> StatusColors.pending
    DeliveryStatus.SENT -> StatusColors.sent
    DeliveryStatus.DELIVERED -> StatusColors.delivered
    DeliveryStatus.FAILED -> StatusColors.failed
}

@Composable
private fun MessageInput(
    draft: String,
    canSend: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceStart: (() -> Unit)? = null,
    onVoiceStop: (() -> Unit)? = null,
    onVoicePTTStart: (() -> Unit)? = null,
    onVoicePTTStop: (() -> Unit)? = null,
    voiceAvailable: Boolean = false,
    inputLang: String = "EN",
    voiceMode: VoiceMode = VoiceMode.PTT,
    onToggleVoiceMode: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = draft,
            onValueChange = onDraftChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_input_hint)) },
            // imeAction Send rather than Done: the keyboard's action key becomes the send
            // key, so a message can be sent one-handed without reaching for a button the
            // keyboard is covering.
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Send,
                // Auto-capitalised: a phone keyboard lowercases by default, so without this
                // the first letter of every sentence is lowercase. Free for the user to undo
                // and it removes a reason to blame the app for a typo.
                capitalization = KeyboardCapitalization.Sentences,
            ),
            // imeAction alone only changes the label on the keyboard's action key; without
            // keyboardActions the key is pressed and nothing happens. That was the state
            // before this was wired: the keyboard showed a Send key that silently did nothing,
            // which reads as "the app cannot send".
            keyboardActions = KeyboardActions(
                onSend = { if (canSend) onSend() },
                onDone = { if (canSend) onSend() },
            ),
            maxLines = 4,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        Spacer(Modifier.width(8.dp))
        if (canSend) {
            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .width(48.dp)
                    .height(48.dp)
                    .semantics {
                        contentDescription = "Send message"
                    },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else if (voiceAvailable) {
            // Why the whole voice row is behind one condition.
            //
            // Every control in this branch used to render whenever the draft was empty,
            // regardless of whether anything was listening. The callbacks are nullable and
            // MainActivity passes none of them, so the microphone, the push-to-talk pad
            // and the PTT/CONTINUOUS toggle were all tappable and did nothing at all --
            // reported from the hardware as "the mic button is not working".
            //
            // The engine behind them is a stub with no model behind it, so wiring the
            // callbacks would not have fixed anything: it would have made the microphone
            // silently discard what the user said, or replace it with fixed text, which
            // is worse than an inert button because it lies about having heard anything.
            //
            // So the controls are drawn only when [voiceAvailable], which the ViewModel
            // takes from the same constant that selects the real implementation. When the
            // models land the row comes back with working callbacks already in place.
            // A dead button is a defect; a button that fakes a transcript is a lie.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = inputLang.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
                if (voiceMode == VoiceMode.CONTINUOUS) {
                    IconButton(
                        onClick = { onVoiceStart?.invoke() },
                        enabled = true,
                        modifier = Modifier
                            .width(56.dp)
                            .height(48.dp)
                            .semantics {
                                contentDescription = "Voice message"
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        onVoicePTTStart?.invoke()
                                        tryAwaitRelease()
                                        onVoicePTTStop?.invoke()
                                    },
                                )
                            }
                            .width(56.dp)
                            .height(48.dp)
                            .semantics {
                                contentDescription = "Push to talk"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(
                    onClick = { onToggleVoiceMode?.invoke() },
                    modifier = Modifier.padding(start = 2.dp),
                ) {
                    Text(text = voiceMode.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ChatScreenPreview() {
    ItantraMessageTheme {
        ChatScreen(
            state = ChatUiState(
                peerName = "realme Narzo 10A",
                isLoading = false,
                draft = "",
                canSend = false,
                messages = listOf(
                    ChatMessage("1", "Hello from the iQOO", 1L, DeliveryStatus.DELIVERED, false),
                    ChatMessage("2", "Received, no internet needed", 2L, DeliveryStatus.SENT, true),
                    ChatMessage("3", null, 3L, DeliveryStatus.FAILED, true),
                ),
            ),
            onDraftChanged = {},
            onSend = {},
            onBack = {},
        )
    }
}
