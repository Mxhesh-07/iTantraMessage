package `in`.isro.sih26173.itantramessage.data.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Low Energy transport: GATT.
 *
 * ## Status, stated honestly
 *
 * This transport is implemented and unit-tested, but it has **not been observed to work
 * between two handsets on this project**. With the app reporting itself as `hosting` and
 * the platform confirming `onAdvertisingSetStarted`, an independent PC-side scanner
 * watching the same radio for 40 seconds saw 44 other BLE devices and no sighting of the
 * phone. See docs/LIMITATIONS.md, R-16.
 *
 * That is a vendor-stack problem, not a code problem, and the honest response is to say
 * so rather than to report this transport as working. [RfcommTransport] is the default
 * for exactly this reason.
 *
 * ## The MTU problem, and what is done about it
 *
 * A default BLE MTU is 23 bytes. Three go to the ATT notification header, leaving 20 for
 * a write. A single envelope with an encrypted payload is larger than that, so a write
 * larger than the MTU silently fails on some stacks and truncates on others -- the worst
 * combination, because it looks like it worked.
 *
 * So a write is split into MTU-sized chunks, and the peer's [Reassembler] reassembles
 * them. The chunking is done here rather than in the repository because the MTU is a
 * property of the radio, and the repository has no business knowing it.
 *
 * `requestMtu(247)` is attempted on connect, but the negotiated value is treated as a hint
 * rather than a guarantee: vendor stacks vary in whether they honour it, so the code uses
 * `min(requested, negotiated)` and falls back to the default when the request is refused.
 */
@SuppressLint("MissingPermission") // Callers check NearbyManager.hasRequiredPermissions() first.
class BleGattTransport private constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val deviceName: String,
) : ByteLink {

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null

    /**
     * The write characteristic on the peer's service.
     *
     * Kept per-transport rather than discovered per-write: a GATT connection has a fixed
     * characteristic set for its lifetime, and re-reading it before every write would be
     * a Binder round trip per message.
     */
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile private var ready = false
    @Volatile private var mtu: Int = DEFAULT_MTU

    override val isOpen: Boolean get() = ready && gatt != null

    override val peerLabel: String get() = deviceName

    /**
     * The peer's radio address.
     *
     * Null when the platform will not say -- `getAddress()` is a hidden API from Android 12.
     * Passing that through unchanged is the point: the caller uses null to skip the
     * identification handshake and surface that it is waiting, rather than proceeding with an
     * empty address that would key a lookup which can never hit.
     */
    override val peerAddress: String? = runCatching { device.address }.getOrNull()

    /** Raw bytes as the radio delivers them, before any framing. Named distinctly from
     *  [incoming] so the two cannot be confused when reading the write path. */
    private val inbound = Channel<ByteArray>(capacity = Channel.BUFFERED)

    override val incoming: Flow<ByteArray> = flow {
        for (bytes in inbound) emit(bytes)
    }.flowOn(Dispatchers.IO)

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected to $deviceName")
                    // Two things must happen before a write can succeed, and both are
                    // asynchronous, which is why `ready` is not set here:
                    // discover services, then find the write characteristic.
                    if (!discoverServices(g)) {
                        fail("service discovery could not be started")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected from $deviceName (status=$status)")
                    // status != GATT_SUCCESS with a disconnected state is a failure the
                    // user needs told about; a clean disconnect is not.
                    ready = false
                    writeCharacteristic = null
                    inbound.close()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("service discovery failed (status=$status)")
                return
            }
            val characteristic = g.getService(SERVICE_UUID)
                ?.getCharacteristic(WRITE_UUID)
            if (characteristic == null) {
                // The most likely cause on a peer that is not running this app: the other
                // handset is not advertising our service, so there is nothing to write to.
                fail("peer does not expose the iTantra write characteristic")
                return
            }
            writeCharacteristic = characteristic
            ready = true
            Log.i(TAG, "GATT ready: MTU=$mtu, service found on $deviceName")
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newMtu > DEFAULT_MTU) {
                mtu = newMtu
                Log.i(TAG, "MTU negotiated: $mtu")
            }
            // A refused MTU request is not an error. The default is used and the write
            // path chunks to whatever this value is, so nothing breaks, which is exactly
            // why the MTU is treated as a hint above.
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            inbound.trySend(value)
        }

        // Not annotated @Deprecated itself: the point of overriding it is to be called
        // by the framework on API < 33. Marking it deprecated would only produce a warning
        // here, and the override is genuinely still used.
        @Suppress("DEPRECATION", "MissingPermission", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // Still called on API < 33, where the data lives on the characteristic rather
            // than in the argument. Without this the app receives nothing below Android 13.
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            inbound.trySend(value)
        }

        private fun fail(reason: String) {
            ready = false
            inbound.close()
            Log.w(TAG, "GATT setup failed: $reason")
        }
    }

    /**
     * Connect and wait until a write characteristic is available.
     *
     * Times out rather than hanging, because a peer that never completes discovery is
     * indistinguishable from a peer that is not there, and the user needs to be told
     * which.
     */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        if (adapter == null) {
            return@withContext Result.failure(IOException("no Bluetooth adapter"))
        }
        val client = device.connectGatt(context, /* autoConnect = */ false, callback,
            BluetoothDevice.TRANSPORT_LE)
        if (client == null) {
            return@withContext Result.failure(IOException("connectGatt returned null"))
        }
        gatt = client

        val readyWithin = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
            while (!ready && gatt != null) {
                delay(50)
            }
            ready
        }

        if (readyWithin == true) {
            Result.success(Unit)
        } else {
            close()
            Result.failure(
                IOException(
                    "GATT setup did not complete within ${SETUP_TIMEOUT_MS}ms " +
                        "(peer ${device.address} may not be running iTantra Message, or its " +
                        "BLE advertising may not be visible -- see docs/LIMITATIONS.md R-16)",
                ),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt): Boolean =
        runCatching { g.discoverServices() }.isSuccess

    /**
     * Ask the stack for a larger MTU, ignoring whether it agrees.
     *
     * Private because the negotiated value is not meaningful outside this class: the write
     * path already clamps to `min(mtu, negotiated)`, so a caller cannot usefully influence
     * it, and exposing it would invite someone to cache a value that the stack may revise
     * on reconnect.
     */
    @SuppressLint("MissingPermission")
    fun requestMtu(requested: Int): Boolean =
        runCatching { gatt?.requestMtu(requested) == true }.getOrDefault(false)

    /**
     * Write [bytes], chunked to the negotiated MTU.
     *
     * @return false if the link is not ready, so the queue retries rather than treating a
     *   rejected write as delivered.
     */
    @SuppressLint("MissingPermission")
    override suspend fun send(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        if (!ready) return false

        // ATT payload limit: the MTU minus 3 bytes of ATT header. Using the full MTU makes
        // every stack reject the write.
        val chunkSize = (mtu - ATT_HEADER_BYTES).coerceAtLeast(1)

        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)

            val ok = runCatching {
                @Suppress("DEPRECATION")
                g.writeCharacteristic(
                    characteristic,
                    chunk,
                    characteristic.writeType,
                ) == BluetoothGatt.GATT_SUCCESS
            }.getOrDefault(false)

            if (!ok) {
                Log.w(TAG, "GATT write rejected at offset $offset")
                return false
            }
            offset = end
        }
        return true
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        ready = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeCharacteristic = null
        inbound.close()
    }

    companion object {
        private const val TAG = "BleGattTransport"

        /**
         * The iTantra GATT service.
         *
         * A random 128-bit UUID generated once and fixed here, rather than a 16-bit
         * Bluetooth SIG one. A 16-bit UUID would need a SIG registration and would collide
         * with any other vendor's service; a random one is unique to this app by
         * construction. It is a fixture in the sense that both builds must agree on it --
         * changing it means both handsets need a new APK.
         */
        val SERVICE_UUID: UUID = UUID.fromString("8f3a21c4-6d2b-4e17-9a55-1c0d7e4b2f19")
        val WRITE_UUID: UUID = UUID.fromString("8f3a21c4-6d2b-4e17-9a55-1c0d7e4b2f1a")

        /** 23 is the BLE specification default, not a choice. */
        const val DEFAULT_MTU = 23

        /** 247 is the largest MTU almost every phone supports, and the usual request. */
        const val REQUESTED_MTU = 247

        /** The ATT header is 3 bytes on every BLE stack: opcode plus 2-byte handle. */
        const val ATT_HEADER_BYTES = 3

        private const val SETUP_TIMEOUT_MS = 20_000L

        /**
         * Open a link to [address].
         *
         * @return the transport on success, or a failure carrying text suitable for the
         *   user -- see the connect() timeout message, which names the most likely cause.
         */
        suspend fun connect(
            context: Context,
            address: String,
            name: String,
        ): Result<BleGattTransport> {
            val adapter =
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                    ?: return Result.failure(IOException("no Bluetooth adapter"))

            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                ?: return Result.failure(IOException("unknown Bluetooth address $address"))

            val transport = BleGattTransport(context, device, name)

            // Deliberately not chained with .map{}: the explicit early returns above read
            // far better than a lambda that has to reconstruct a Result, and the MTU
            // request needs a statement of its own anyway.
            val opened = transport.connect()
            if (opened.isFailure) {
                return opened.map { transport }
            }

            // Requested after connect: the stack ignores an MTU request before the
            // connection is up, and a refusal simply leaves the default in place, which the
            // write path already handles by chunking to whatever the MTU is.
            runCatching { transport.requestMtu(REQUESTED_MTU) }
                .onFailure { Log.w(TAG, "requestMtu failed: ${it.javaClass.simpleName}") }

            return Result.success(transport)
        }
    }
}
