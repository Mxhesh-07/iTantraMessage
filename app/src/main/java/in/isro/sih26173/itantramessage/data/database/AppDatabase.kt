package `in`.isro.sih26173.itantramessage.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's local store.
 *
 * One database, one table. That is not under-engineering for its own sake: the brief asks
 * for no unnecessary dependencies, and a second table would mean a second entity, a second
 * DAO, a migration path, and a second thing to test, for no requirement that needs it.
 *
 * ## Why there is no destructive migration
 *
 * `fallbackToDestructiveMigration` is not enabled, and the version is 1. If a future
 * release adds a column without a migration, Room throws at open with a message naming
 * the missing migration. That is the correct outcome: silently deleting a user's message
 * history because a developer forgot a migration is a data-loss bug that ships to
 * production and is invisible until a user complains.
 */
@Database(
    entities = [MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        /**
         * The database file name.
         *
         * Not named `itantra.db` in a way that implies anything about its contents -- the
         * table is ciphertext, and a filename that reads "messages" in a file listing on a
         * rooted device is a small, free disclosure.
         */
        const val DB_NAME = "itantra_msg_v1.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * The process-wide singleton.
         *
         * Double-checked locking rather than `by lazy`: Room's own recommendation is that
         * exactly one instance exists per process, because two instances over one file
         * hold independent caches and will disagree about the contents.
         *
         * The [fallbackToDestructiveMigration] argument is deliberately absent -- see the
         * class comment.
         */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // WAL rather than the default rollback journal: the retry worker writes
                // queue transitions while the chat screen is reading, and WAL lets those
                // proceed concurrently instead of blocking each other.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
