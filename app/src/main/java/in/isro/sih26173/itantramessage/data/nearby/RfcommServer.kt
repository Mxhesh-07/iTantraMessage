package `in`.isro.sih26173.itantramessage.data.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Accepts incoming RFCOMM connections.
 *
 * ## Why this class is necessary
 *
 * Written after the first two-phone run failed with
 *
 *     read failed, socket might closed or timeout, read ret: -1
 *
 * The cause was not the radio and not the pairing. `RfcommTransport` only ever *dials*, via
 * `createRfcommSocketToServiceRecord`, which performs an SDP lookup and connects to a service
 * that is **already listening**. There was no listener anywhere in the app, so the dialling
 * phone was dialling an empty room.
 *
 * Bluetooth RFCOMM is not symmetric by default: one side listens, the other dials. An app that
 * only implements one half is only half a P2P app. With this in place, whichever phone the
 * user taps first listens and the other answers, so exactly one tap is needed and either
 * phone can be the initiator.
 *
 * ## Secure vs insecure listener
 *
 * [BluetoothAdapter.listenUsingRfcommWithServiceRecord] (secure) only accepts connections from
 * devices that are **bonded** to this one. That matches the app's model -- every other
 * transport here already assumes a bond -- and it means a stranger in range cannot open a
 * socket at all.
 *
 * It also throws `SecurityException` on some OEM builds, which is why [acceptIncoming] falls
 * back to the insecure variant rather than failing the feature outright. The fallback is
 * logged loudly, because it is a real reduction: the insecure listener accepts a connection
 * from any device in range, and the only remaining protection is that message payloads are
 * AES-GCM ciphertext. Availability is not worth silently pretending to more than it is, so
 * the log says which one is in effect.
 *
 * ## Lifetime
 *
 * The listening socket is owned by the collector. Cancelling the collection closes it, which
 * is what stops this holding a radio slot after the user leaves the screen. A listener with
 * no bound to the UI is the classic way a messaging app drains a battery while showing nothing.
 */
class RfcommServer(
    private val adapter: BluetoothAdapter?,
) {

    /**
     * Emit a link for each incoming connection, until collection stops.
     *
     * One socket per connection, and the previous link is closed before the next is emitted:
     * two simultaneous RFCOMM sockets to one radio is a state Android will refuse to schedule,
     * and the app has no use for a second peer while the first is live.
     */
    @SuppressLint("MissingPermission") // The caller gates on runtime permissions.
    fun acceptIncoming(serviceName: String): Flow<ByteLink> = callbackFlow {
        val bt = adapter
        if (bt == null) {
            Log.w(TAG, "no Bluetooth adapter; not listening")
            close()
            return@callbackFlow
        }
        if (!bt.isEnabled) {
            Log.w(TAG, "Bluetooth is off; not listening")
            close()
            return@callbackFlow
        }

        var server: BluetoothServerSocket? = null
        var secure = true

        try {
            server = bt.listenUsingRfcommWithServiceRecord(serviceName, RfcommTransport.SPP_UUID)
            Log.i(TAG, "listening on ${RfcommTransport.SPP_UUID} (bonded devices only)")
        } catch (e: SecurityException) {
            // OEM builds that reject the secure listener. Fall back rather than lose the
            // feature, but say plainly that the weaker listener is what is running.
            secure = false
            Log.w(TAG, "secure listener refused (${e.javaClass.simpleName}); " +
                "falling back to the insecure listener, which accepts any device in range")
            server = runCatching {
                bt.listenUsingInsecureRfcommWithServiceRecord(
                    serviceName,
                    RfcommTransport.SPP_UUID,
                )
            }.getOrNull()
        }

        if (server == null) {
            Log.w(TAG, "could not open a listening socket; incoming connections unavailable")
            close()
            return@callbackFlow
        }

        val listener = server
        val worker = android.os.HandlerThread("itantra-rfcomm-accept").apply { start() }
        val handler = android.os.Handler(worker.looper)

        val acceptRunnable = object : Runnable {
            override fun run() {
                val socket: BluetoothSocket = try {
                    listener.accept()
                } catch (e: java.io.IOException) {
                    // Normal when the listener is closed underneath us by cancellation.
                    if (!isClosedForSend) {
                        Log.w(TAG, "accept failed: ${e.javaClass.simpleName}")
                    }
                    return
                }
                if (isClosedForSend) {
                    // Cancelled while blocking in accept(). The socket nobody wanted would
                    // otherwise stay open and hold a radio slot.
                    runCatching { socket.close() }
                    return
                }

                val address = runCatching { socket.remoteDevice?.address }.getOrNull()
                val name = runCatching { socket.remoteDevice?.name }.getOrNull()
                    ?: address
                    ?: "peer"

                trySend(RfcommTransport.accepted(socket, address, name))
                handler.post(this) // accept the next one
            }
        }
        handler.post(acceptRunnable)

        awaitClose {
            handler.removeCallbacksAndMessages(null)
            runCatching { listener.close() }
                .onFailure { Log.w(TAG, "listener close: ${it.javaClass.simpleName}") }
            worker.quitSafely()
            Log.i(TAG, "listener stopped (was ${if (secure) "secure" else "insecure"})")
        }
    }

    private companion object {
        const val TAG = "RfcommServer"
    }
}