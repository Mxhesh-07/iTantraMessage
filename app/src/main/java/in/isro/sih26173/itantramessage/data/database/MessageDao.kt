package `in`.isro.sih26173.itantramessage.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * All database access for messages.
 *
 * ## Why reads are Flow and writes are suspend
 *
 * Reads return [Flow] because the chat screen has to react to a message arriving from a
 * peer while the user is looking at it, with no polling and no manual refresh. Room
 * re-emits only when the table actually changes, so an idle chat screen does no work.
 *
 * Writes are `suspend` so Room runs them on its own executor rather than the caller's
 * thread. The alternative -- `allowMainThreadQueries` -- would put a disk write on the
 * UI thread, which on a low-end device is a dropped frame every time the queue is swept.
 */
@Dao
interface MessageDao {

    // ---- reads -----------------------------------------------------------------------

    /**
     * One conversation, oldest first, for the chat list.
     *
     * `Flow`, so an incoming message appears without the UI asking again. The `LIMIT`
     * pairs with the index on (conversation_id, timestamp): Room can satisfy this from
     * the index without sorting the whole conversation.
     *
     * The limit is deliberate. A user who has exchanged 10,000 messages with one peer
     * should not make the app render 10,000 rows to show the most recent 200; the older
     * ones are still in the database and still counted by Settings > Storage.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY timestamp ASC
        LIMIT :limit
        """,
    )
    fun observeConversation(
        conversationId: String,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): Flow<List<MessageEntity>>

    /** A single page, older than [before], for scrolling back through history. */
    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId AND timestamp < :before
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    suspend fun pageBefore(
        conversationId: String,
        before: Long,
        limit: Int = DEFAULT_PAGE_SIZE,
    ): List<MessageEntity>

    /**
     * The outgoing queue: everything not yet delivered, oldest first.
     *
     * `ORDER BY timestamp ASC` is the fairness rule -- a message that has waited longest
     * goes first, so one stuck conversation cannot starve another. The index on
     * (status, timestamp) serves this directly.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE status IN ('PENDING', 'SENDING')
        ORDER BY timestamp ASC
        """,
    )
    fun observeQueue(): Flow<List<MessageEntity>>

    /** Queue contents for the retry worker, which needs a snapshot rather than a stream. */
    @Query(
        """
        SELECT * FROM messages
        WHERE status IN ('PENDING', 'SENDING')
        ORDER BY timestamp ASC
        """,
    )
    suspend fun pendingForDelivery(): List<MessageEntity>

    /** Total row count, for the Settings > Storage line. */
    @Query("SELECT COUNT(*) FROM messages")
    fun observeMessageCount(): Flow<Int>

    /** Database size in bytes, for the same screen. */
    @Query("SELECT SUM(LENGTH(ciphertext)) FROM messages")
    fun observePayloadBytes(): Flow<Long?>

    /**
     * Whether [messageId] is already stored.
     *
     * Checked before inserting a received message. The unique index on `message_id` is
     * the real guarantee -- this query exists to avoid raising a constraint exception on
     * the common path, and to let the caller distinguish "duplicate" (return quietly)
     * from "genuinely new" rather than catching an exception to find out.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE message_id = :messageId)")
    suspend fun exists(messageId: String): Boolean

    // ---- writes ----------------------------------------------------------------------

    /**
     * Insert a message the user just composed.
     *
     * IGNORE, not ABORT: if the same message id somehow lands twice (a retried write
     * after a process death), the first row stands and the second is dropped. A crash on
     * a user's Send tap would be a far worse outcome than a duplicate being ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    /**
     * Insert a received message, reporting whether it was new.
     *
     * IGNORE for the same reason as [insert]. Returns -1 when the row was ignored, which
     * the repository turns into the silent duplicate-rejection path.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(message: MessageEntity): Long

    /**
     * Move a message to a new delivery state.
     *
     * The `WHERE status = :expectedFrom` clause is the whole point: it makes the
     * transition a compare-and-set, so two concurrent paths (the retry worker and the
     * transport's own callback) cannot both advance the same message and skip a state.
     *
     * The repository checks [DeliveryStatus.canTransitionTo] before calling this; that
     * check is for a clear error message, and this predicate is what actually enforces
     * it under concurrency. Belt and braces is correct here -- the alternative is a
     * duplicated message, which is user-visible.
     */
    @Query(
        """
        UPDATE messages
        SET status = :to
        WHERE message_id = :messageId AND status = :expectedFrom
        """,
    )
    suspend fun transition(
        messageId: String,
        expectedFrom: String,
        to: String,
    ): Int

    /** Record a send failure: bump the retry count and store the reason. */
    @Query(
        """
        UPDATE messages
        SET status = :to, retry_count = retry_count + 1, last_error = :error
        WHERE message_id = :messageId
        """,
    )
    suspend fun recordFailure(messageId: String, to: String, error: String): Int

    /** Write a partial update, for the draft-to-sent transition. */
    @Update
    suspend fun update(message: MessageEntity)

    /** Wipe every message. Used by Settings > Clear stored messages. */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /**
     * Trim a conversation to its most recent [keep] rows.
     *
     * Only ever called for messages that are already terminal, so this can never discard
     * something the user has not seen. The subquery is timestamp-ordered and limited,
     * which lets SQLite use the (conversation_id, timestamp) index for both halves.
     */
    @Query(
        """
        DELETE FROM messages
        WHERE conversation_id = :conversationId
          AND message_id NOT IN (
            SELECT message_id FROM messages
            WHERE conversation_id = :conversationId
            ORDER BY timestamp DESC
            LIMIT :keep
          )
        """,
    )
    suspend fun trimConversation(conversationId: String, keep: Int): Int

    companion object {
        /**
         * Messages loaded per page. 100 is a balance: enough that scrolling does not
         * visibly stall, small enough that the initial query is a few milliseconds even
         * on the low-RAM devices this app targets. 200 rows of text is roughly 40 KB.
         */
        const val DEFAULT_PAGE_SIZE = 100
    }
}
