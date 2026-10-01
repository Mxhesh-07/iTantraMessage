package `in`.isro.sih26173.itantramessage.data.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Bluetooth Classic transport: RFCOMM over SPP.
 *
 * ## Why this is the default transport, not GATT
 *
 * Three measured reasons, all from the sibling iTantra project on the same two handsets:
 *
 *  1. **GATT advertising was never observed off-device.** With the app reporting itself
 *     as `hosting` and the platform confirming `onAdvertisingSetStarted`, a PC-side
 *     scanner watching for 40 seconds saw 44 other BLE devices and zero sightings of the
 *     phone. The vendor stack appears to be starving its own advertising set -- Nearby
 *     Sharing holds 5 advertise instances and rotates its address. A transport that is
 *     sometimes invisible is not a transport to build a demo on.
 *  2. **GATT's payload ceiling is 20 usable bytes** on a default 23-byte MTU (3 bytes go
 *     to the length prefix and flags). Even after negotiating a larger MTU, vendor
 *     stacks vary in whether they honour it, so a frame reassembly layer is mandatory
 *     rather than optional.
 *  3. **RFCOMM needs no advertising at all.** Both handsets are already bonded, so each
 *     can enumerate the other from the platform's bonded list. Discovery is a local list
 *     read instead of a radio operation -- which also means it costs no battery.
 *
 * The cost of (3) is that the two phones must be paired in Android Settings before this
 * transport can work. That is a one-time user action and it is stated in the UI rather
 * than discovered at connect time.
 *
 * ## Threading
 *
 * A socket read blocks, so a dedicated thread is unavoidable; a coroutine on
 * [Dispatchers.IO] would be equivalent but with a worse failure mode, because cancelling
 * a blocked read does not unblock it. Two threads are used:
 *
 *  * a **reader** blocked in [InputStream.read], which forwards into a channel;
 *  * a **writer** fed by a [LinkedBlockingQueue], which exists so that ordering is
 *    guaranteed. Two coroutines writing the same [OutputStream] would interleave at
 *    arbitrary byte boundaries and produce frames that no peer could parse. A single
 *    writer thread with a queue is the smallest correct answer.
 *
 * The reader and writer are both daemon-style: [close] interrupts them, and neither can
 * keep the process alive.
 */
@SuppressLint("MissingPermission") // Callers check NearbyManager.hasRequiredPermissions() first.
class RfcommTransport private constructor(
    private val adapter: BluetoothAdapter?,
    private val device: BluetoothDevice,
    private val deviceName: String,
) : ByteLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null
    private var writerJob: Job? = null

    private val outbox = LinkedBlockingQueue<ByteArray>()

    private val _open = MutableStateFlow(false)
    override val isOpen: Boolean get() = _open.value

    private val incomingBytes = Channel<ByteArray>(capacity = Channel.BUFFERED)

    override val incoming: Flow<ByteArray> = flow {
        for (bytes in incomingBytes) emit(bytes)
    }.flowOn(Dispatchers.IO)

    override val peerLabel: String get() = deviceName

    /**
     * The peer's radio address.
     *
     * `getAddress()` is behind a hidden-API restriction from Android 12, where it can return
     * null even for a device we hold a `BluetoothDevice` handle on. Rather than treat that as
     * a crash or coerce it to an empty string, the value is passed through as-is: null
     * disables the identification handshake and the UI says so, which is honest. An empty
     * string would silently key a lookup that never matches.
     */
    override val peerAddress: String? = runCatching { device.address }.getOrNull()

    /**
     * Open a socket to [device].
     *
     * The steps are in this order for reasons that are not obvious:
     *
     *  1. **cancel discovery first.** Android will not settle a classic connection while
     *     a discovery scan is running: the adapter stays in a scanning state and the
     *     connect blocks until discovery stops. On the app's own scan path this is the
     *     difference between connecting in a second and never connecting.
     *  2. **createRfcommSocketToServiceRecord**, not the reflective
     *     `createRfcommSocket(int channel)` form. The latter is hidden API and throws on
     *     any device with a non-standard SPP channel; the documented SPP UUID is portable.
     *  3. **cancel discovery again afterwards**, because a failed connect can leave the
     *     adapter scanning.
     */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        // Named differently from the constructor property on purpose: inside withContext
        // `this` is the coroutine scope, so a local called `adapter` would shadow the
        // field and the initialiser would read itself.
        val bt = adapter
            ?: return@withContext Result.failure(IOException("no Bluetooth adapter on this device"))
        if (!bt.isEnabled) {
            return@withContext Result.failure(IOException("Bluetooth is off"))
        }

        runCatching { bt.cancelDiscovery() }
            .onFailure { Log.w(TAG, "cancelDiscovery (pre): ${it.javaClass.simpleName}") }

        // Two nested outcomes: withTimeoutOrNull distinguishes "timed out" (null) from
        // "returned a Result", and the inner runCatching captures the IOException that
        // BluetoothSocket.connect throws on refusal. Both are reported distinctly because
        // they mean different things to a user: a timeout is a peer that is not answering,
        // a throw is a peer that actively refused.
        val opened = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { openSocket() }
        }

        // Always stop discovery afterwards, including on the failure paths. A failed
        // connect can leave the adapter scanning, and a scan left running is a battery
        // drain with no symptom.
        runCatching { bt.cancelDiscovery() }
            .onFailure { Log.w(TAG, "cancelDiscovery (post): ${it.javaClass.simpleName}") }

        if (opened == null) {
            return@withContext Result.failure(
                IOException(
                    "connect timed out after ${CONNECT_TIMEOUT_MS}ms " +
                        "(the other phone may be out of range, off, or not paired with this one)",
                ),
            )
        }

        val failure = opened.exceptionOrNull()
        if (failure != null) {
            return@withContext Result.failure(
                IOException(describe(failure), failure),
            )
        }

        startReader()
        startWriter()
        _open.value = true
        Log.i(TAG, "RFCOMM connected to $deviceName")
        Result.success(Unit)
    }

    /**
     * Open the socket and hand it back, without closing it.
     *
     * Split out of [connect] because `use { }` here would close the socket the instant
     * the block returns, which is the single easiest way to get a "connected then instantly
     * read ret: -1" bug. The socket is closed by [close], not by a scope.
     */
    @SuppressLint("MissingPermission") // Caller checked permissions before constructing.
    private fun openSocket(): BluetoothSocket {
        val candidate = device.createRfcommSocketToServiceRecord(SPP_UUID)

        // NOTE: no SDP discovery call is made here. `BluetoothSocket.startServiceDiscovery()`
        // does not exist in the public SDK -- an early draft of this class called it and
        // it does not compile, which is the honest reason it is absent rather than
        // "considered and skipped". Android performs the SDP exchange as part of
        // connect() for a service-record socket, so an explicit call is neither possible
        // nor needed. See docs/NETWORK_PROTOCOL.md for the wire-level detail.

        try {
            candidate.connect()
        } catch (t: Throwable) {
            // connect() throws on refusal, and the socket must be closed or the adapter
            // keeps a dangling reference that blocks the next attempt.
            runCatching { candidate.close() }
            throw t
        }

        socket = candidate
        return candidate
    }

    /**
     * The reader loop.
     *
     * A 1024-byte buffer rather than a larger one: RFCOMM delivers what the peer wrote,
     * and a buffer larger than the largest frame only costs memory. [Reassembler] handles
     * the rest -- it is fed every chunk and yields whole frames regardless of how they
     * were split.
     */
    private fun startReader() {
        val input = socket?.inputStream ?: return
        readerJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            try {
                while (isActive) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        // End of stream: the peer closed cleanly. Closing the channel is
                        // what makes the collector above see the link end, and it closes
                        // the link so the queue stops trying to write into a dead socket.
                        break
                    }
                    if (count == 0) continue
                    incomingBytes.send(buffer.copyOf(count))
                }
            } catch (e: IOException) {
                Log.i(TAG, "reader stopped: ${e.javaClass.simpleName}")
            } finally {
                incomingBytes.close()
                _open.value = false
            }
        }
    }

    /**
     * The writer loop.
     *
     * Draining [outbox] on a single thread is what preserves message order. Ordering is
     * not cosmetic: the peer deduplicates by message id and shows a conversation in
     * timestamp order, and two threads interleaving into one stream would produce frames
     * whose headers are split across two writes -- unparseable on the far end, with no
     * error to explain it.
     */
    private fun startWriter() {
        writerJob = scope.launch {
            val output = socket?.outputStream ?: return@launch
            try {
                while (isActive) {
                    // The timed poll rather than a blocking take() is deliberate:
                    // close() puts a poison pill in the queue, and a blocked take() cannot
                    // be woken by a thread interrupt on every Android version.
                    val frame = outbox.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS)
                        ?: continue
                    output.write(frame)
                    output.flush()
                }
            } catch (e: IOException) {
                Log.i(TAG, "writer stopped: ${e.javaClass.simpleName}")
            } finally {
                _open.value = false
            }
        }
    }

    /**
     * Enqueue [bytes] for sending.
     *
     * Returns false rather than throwing when the link is closed: the caller is the retry
     * queue, and "the peer is not there" is an expected condition it handles by waiting.
     */
    override suspend fun send(bytes: ByteArray): Boolean {
        if (!_open.value) return false
        // offer, not put: a full queue means the peer is not draining, and blocking the
        // caller's coroutine would stall the whole message loop.
        return outbox.offer(bytes, SEND_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Close the link and release the radio.
     *
     * Safe to call twice, and safe to call from a failed connect where [socket] is still
     * null. The order matters: mark closed first so no new work is enqueued, then wake the
     * writer (its poll times out at most [WRITER_POLL_MS] later), then close the socket
     * so the blocked read throws and the reader exits.
     */
    override fun close() {
        if (!_open.value && socket == null) return
        _open.value = false

        // Poison pill: a frame that the writer will pick up and fail to write, which is
        // enough to notice the queue is no longer being drained.
        outbox.offer(POISON)

        runCatching { socket?.close() }
            .onFailure { Log.w(TAG, "socket close: ${it.javaClass.simpleName}") }
        socket = null

        // Both jobs are on Dispatchers.IO and will notice within a poll interval; joining
        // here is best-effort so close() stays non-suspending, as the interface requires.
        readerJob?.cancel()
        writerJob?.cancel()
        readerJob = null
        writerJob = null
        incomingBytes.close()
        Log.i(TAG, "RFCOMM closed for $deviceName")
    }

    /**
     * Turn a connect failure into something a user can act on.
     *
     * The raw exception messages from Android's Bluetooth stack are actively unhelpful --
     * `read failed, socket might closed or timeout, read ret: -1` describes a symptom, not
     * a cause, and appears identically whether the peer is off, the bond is stale, or the
     * peer simply does not implement SPP. So the most likely causes are named alongside
     * the raw text, and the raw text is kept because it is the only part that helps a
     * developer.
     */
    private fun describe(t: Throwable?): String {
        val raw = t?.message ?: t?.javaClass?.simpleName ?: "unknown error"
        return "$raw (check: both phones are on, Bluetooth is on for both, " +
            "and the two devices are paired with each other in Settings > Bluetooth)"
    }

    companion object {
        private const val TAG = "RfcommTransport"

        /**
         * The Serial Port Profile UUID, `00001101-0000-1000-8000-00805f9b34fb`.
         *
         * This is the well-known SPP identifier. It is a *convention*, not an
         * guarantee: a peer that has never installed an SPP service will accept nothing
         * on this channel. Every generic Bluetooth headset, speaker and watch on a
         * bonded list -- which is exactly what a phone's bonded list is full of -- will
         * refuse it. That is a property of Bluetooth, not a bug, and the UI says so.
         */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_BUFFER_BYTES = 1024
        private const val WRITER_POLL_MS = 200L
        private const val SEND_QUEUE_TIMEOUT_MS = 2_000L

        private val POISON = ByteArray(0)

        /**
         * Open a link to [address].
         *
         * [name] is passed in rather than read from the device, because reading
         * `BluetoothDevice.getName()` needs BLUETOOTH_CONNECT and throws without it --
         * and the caller already has the name from the bonded list it just rendered.
         */
        suspend fun connect(
            adapter: BluetoothAdapter?,
            address: String,
            name: String,
        ): Result<RfcommTransport> = withContext(Dispatchers.IO) {
            if (adapter == null) {
                return@withContext Result.failure(IOException("no Bluetooth adapter"))
            }
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                ?: return@withContext Result.failure(
                    IOException("unknown Bluetooth address $address"),
                )

            val transport = RfcommTransport(adapter, device, name)
            transport.connect().map { transport }
        }
    }
}
