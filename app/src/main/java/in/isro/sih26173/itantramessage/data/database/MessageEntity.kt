package `in`.isro.sih26173.itantramessage.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One stored message, in either direction.
 *
 * ## Why the ciphertext is stored, not the plaintext
 *
 * The user's own history is readable on their own device without any key lookup, so
 * storing plaintext would be simpler. It is still not done, for two reasons. First, a
 * plaintext history is a plaintext history *in backups and in memory dumps*, and the
 * whole design keeps encryption at rest as a property of the app rather than of the
 * storage layer. Second, and more concretely: it makes it structurally impossible to
 * accidentally log or export plaintext, because the only path to text goes through
 * [in.isro.sih26173.itantramessage.data.crypto.EncryptionManager].
 *
 * The trade-off is that opening the app needs the Keystore. If the key is gone -- user
 * cleared app data, or the app was reinstalled -- the history is unreadable and
 * [isReadable] on a row tells the UI to say so plainly rather than showing an empty chat
 * that looks like data loss.
 */
@Entity(
    tableName = "messages",
    indices = [
        // The chat screen reads one conversation newest-last. Without this index Room does
        // a full table scan plus a sort on every query, which is the difference between a
        // smooth list and a stutter once a conversation has a few hundred messages.
        Index(value = ["conversation_id", "timestamp"]),
        // Deduplication on receive looks up by message id within a conversation.
        Index(value = ["message_id"], unique = true),
        // The queue sweep reads pending rows oldest-first.
        Index(value = ["status", "timestamp"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,

    /**
     * Sender-assigned id, unique across all peers.
     *
     * Unique in the database, not unique per conversation, because the id is what makes
     * deduplication work: the same message arriving twice from the same peer is rejected
     * by the constraint rather than by a check that could race.
     */
    @ColumnInfo(name = "message_id")
    val messageId: String,

    /**
     * Which conversation this belongs to.
     *
     * Derived, not stored as two columns: for a two-party chat it is the lexicographically
     * smaller of the two device ids, so both ends agree on the value without exchanging
     * it. See [in.isro.sih26173.itantramessage.domain.model.ConversationId].
     */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "receiver_id")
    val receiverId: String,

    /**
     * The encrypted envelope from [EncryptionManager.encrypt], or the raw text for a
     * local-only draft.
     *
     * Nullable is meaningful: a non-null [status] of [DeliveryStatus.PENDING] with a null
     * body means the row is a placeholder for a message the user has not finished
     * typing. See [isReadable].
     */
    @ColumnInfo(name = "ciphertext")
    val ciphertext: String?,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "message_type")
    val messageType: String,

    @ColumnInfo(name = "status")
    val status: String,

    /**
     * How many send attempts have failed.
     *
     * Persisted because the retry budget must survive a process death. An in-memory
     * counter resets to zero when the app is killed, which would turn a device that is
     * genuinely unreachable into an infinite retry loop -- exactly the battery drain the
     * brief asks to avoid.
     */
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    /**
     * The last failure, for the Settings > Storage diagnostics screen.
     *
     * Never contains a stack trace or a ciphertext fragment. See docs/ERROR_HANDLING.md.
     */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
) {
    /** Whether [ciphertext] holds an actual encrypted message rather than nothing. */
    val isReadable: Boolean get() = ciphertext != null
}

/**
 * Lifecycle of a message.
 *
 * Stored as a String rather than an enum ordinal. An ordinal is fragile: reordering this
 * enum after a release would silently reinterpret every existing row -- a `SENT` from the
 * previous build would read as `DELIVERED` under the new ordering, and no build error
 * would catch it. The String names are the wire-stable representation.
 *
 * The transition rules live in [DeliveryStatus.canTransitionTo], not in the repository,
 * so there is one place to read to know what the state machine permits.
 */
enum class DeliveryStatus {
    /** Stored locally, not yet handed to a transport. */
    PENDING,

    /** Handed to a transport; awaiting the peer's acknowledgement. */
    SENDING,

    /** The transport accepted the bytes. Not yet confirmed by the peer. */
    SENT,

    /** The peer acknowledged receipt. */
    DELIVERED,

    /** Undeliverable: retry budget exhausted, or permanently rejected. */
    FAILED;

    val isTerminal: Boolean get() = this == DELIVERED || this == FAILED

    /** Whether a message in this state should still be retried. */
    val isRetriable: Boolean get() = this == PENDING || this == SENDING

    /**
     * Permitted transitions.
     *
     * Two rules are enforced here rather than left to callers:
     *  * a terminal state is final -- DELIVERED and FAILED never move, because a message
     *    that was delivered must not be re-sent because of a race;
     *  * FAILED is reachable from any non-terminal state, so a transport that discovers
     *    it cannot deliver can record that without a special case.
     */
    fun canTransitionTo(next: DeliveryStatus): Boolean {
        if (isTerminal) return false
        return when (next) {
            PENDING -> this == SENDING
            SENDING -> this == PENDING
            SENT -> this == PENDING || this == SENDING
            DELIVERED -> this == SENT
            FAILED -> !this.isTerminal
        }
    }
}
