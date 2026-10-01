package `in`.isro.sih26173.itantramessage

import android.app.Application
import android.content.Context
import android.util.Log
import `in`.isro.sih26173.itantramessage.data.crypto.EncryptionManager
import `in`.isro.sih26173.itantramessage.data.database.AppDatabase
import `in`.isro.sih26173.itantramessage.data.database.MessageDao
import `in`.isro.sih26173.itantramessage.data.device.DeviceIdentity
import `in`.isro.sih26173.itantramessage.data.device.PeerIdentity
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyManager
import `in`.isro.sih26173.itantramessage.domain.repository.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The composition root.
 *
 * ## Why this exists
 *
 * `MessageRepository` owns the open link and the receive loop. If each ViewModel built its
 * own, there would be two repositories, two receive loops, and no way to tell which one
 * held the socket -- and the second `attach()` would close the first one's link out from
 * under it, producing a connection that appears to drop for no visible reason.
 *
 * So the repository is created once, here, and outlives every screen. That also gives it a
 * scope whose lifetime is the process, which is what lets a queued message still be
 * delivered when the user navigates back from the chat to the device list.
 *
 * ## The scope
 *
 * A [SupervisorJob] on [Dispatchers.IO]. Supervisor, not the default Job, because one
 * failing child -- a receive loop hitting a dead socket -- must not cancel the others and
 * take the queue pump down with it. That is a single point of failure that would present
 * to a user as "messages stop sending until I restart the app".
 */
class ItantraMessageApp : Application() {

    /** Process-scoped. Never cancelled except in [onTerminate], which Android never calls. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val container: AppContainer by lazy { AppContainer(this, appScope) }

    override fun onTerminate() {
        // Only reached on an emulator. Present anyway so the ordering is written down:
        // cancel the scope first, so the receive loop stops, then close the link.
        appScope.cancel()
        super.onTerminate()
    }
}

/**
 * The object graph, assembled once.
 *
 * Plain constructor-injected fields rather than a DI framework. The brief asks not to
 * introduce unnecessary dependencies, and a graph this small does not need annotation
 * processing to be navigable -- reading [AppContainer] tells a reader every dependency in
 * the app on one screen.
 *
 * Everything is `by lazy` so nothing is created until it is first needed. That matters
 * more than it looks: [AppDatabase.get] opens a file, and a user who never opens a
 * conversation should not pay for it at process start.
 */
class AppContainer(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val database: AppDatabase by lazy { AppDatabase.get(context) }

    val messageDao: MessageDao by lazy { database.messageDao() }

    val encryption: EncryptionManager by lazy { EncryptionManager() }

    val identity: DeviceIdentity by lazy { DeviceIdentity(context) }

    /** Which device id belongs to which Bluetooth address this app has met. */
    val peers: PeerIdentity by lazy { PeerIdentity(context) }

    val nearby: NearbyManager by lazy { NearbyManager(context) }

    val repository: MessageRepository by lazy {
        MessageRepository(
            dao = messageDao,
            identity = identity,
            crypto = encryption,
            scope = scope,
            peers = peers,
        )
    }

    /**
     * Warm the encryption key on first launch.
     *
     * Generating the key costs a few milliseconds and can, on a locked device, prompt for
     * nothing at all but fail. Doing it here rather than on the first Send means the cost
     * lands during idle startup instead of in the middle of a user action, and a failure
     * is discovered while there is no message at stake.
     */
    fun warmUp() {
        scope.launch {
            try {
                if (!encryption.hasKey()) {
                    val generation = encryption.ensureKey()
                    Log.i(TAG, "generated encryption key, generation $generation")
                }
            } catch (t: CancellationException) {
                throw t // Cancellation is control flow, not a failure.
            } catch (t: Throwable) {
                // Deliberately swallowed. A key that cannot be generated now will be
                // retried on the first Send, and crashing here would lose the user's
                // message history over a background optimisation.
                Log.w(TAG, "key warm-up failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "ItantraMessageApp"
    }
}
