package `in`.isro.sih26173.itantramessage.data.nearby

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * What a nearby device looks like to the user, with no Bluetooth types in it.
 *
 * This is the only shape the UI knows about. The reason is not tidiness: BLE and RFCOMM
 * report wildly different things about the same handset -- a BLE scan yields an address,
 * an RSSI and a name fragment that is often `null`; an RFCOMM bonded list yields a
 * name and an address and no signal at all. If the screens took the platform type
 * directly, every one of them would need a `when (device)`, and a new transport would
 * mean editing every screen.
 *
 * [rssiDbm] is nullable and that is load-bearing. A bonded RFCOMM peer has no meaningful
 * RSSI, and showing it a fabricated number -- 0, or -100 -- would be a measurement the
 * app never took. The UI prints "paired" instead.
 */
data class NearbyDevice(
    val address: String,
    val name: String?,
    val transport: Transport,
    val rssiDbm: Int?,
    val isBonded: Boolean,
) {
    /** What to show as this device's name. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: fallbackName()

    /**
     * A name derived from the address, for devices that report none.
     *
     * The last three octets is the convention Bluetooth itself uses in its own UI, and it
     * is enough for a user to tell two unnamed handsets apart without exposing a full
     * hardware address on screen.
     */
    private fun fallbackName(): String =
        if (address.length >= 6) "Device ${address.takeLast(6).replace(':', '-')}" else "Device"

    val subtitle: String
        get() = buildString {
            append(if (isBonded) "Paired" else "Nearby")
            rssiDbm?.let { append(" · ").append(it).append(" dBm") }
        }
}

/** Which radio path a peer is reachable over. */
enum class Transport(val label: String) {
    /** Bluetooth Low Energy, GATT. Low power, but tiny MTU and vendor-dependent. */
    GATT("Bluetooth Low Energy"),

    /** Bluetooth Classic, RFCOMM/SPP. Larger payloads, needs a paired device. */
    RFCOMM("Bluetooth Classic"),

    /** Wi-Fi Direct. Fastest, highest power, needs NEARBY_WIFI_DEVICES. */
    WIFI_DIRECT("Wi-Fi Direct"),
}

/**
 * The reason a nearby connection could not be used.
 *
 * A sealed class rather than a String, because the UI has to react differently to each:
 * a missing permission gets a button that requests it, an off radio gets one that opens
 * Settings, and a missing hardware feature gets nothing because there is nothing to do.
 * A String forces all three to be the same screen with a message.
 */
sealed interface NearbyError {
    /** The human-readable text. Every case is safe to show a user. */
    val message: String

    data object BluetoothOff : NearbyError {
        override val message = "Bluetooth is off"
    }

    data object PermissionMissing : NearbyError {
        override val message = "Nearby permission required"
    }

    data object Unsupported : NearbyError {
        override val message = "Nearby connections unavailable on this device"
    }

    data class ConnectFailed(val peer: String, val reason: String) : NearbyError {
        override val message = "Could not connect to $peer: $reason"
    }

    data class TransportFailed(val reason: String) : NearbyError {
        override val message = "Nearby transport error: $reason"
    }
}

/**
 * Thin, honest wrapper over the platform Bluetooth stack.
 *
 * ## What this class deliberately does not do
 *
 * It does not abstract "Nearby Connections". There is no such abstraction available
 * offline: `play-services-nearby` is a Google Play services API, Play services is absent
 * on de-Googled hardware, and it merges `android.permission.INTERNET` into the APK --
 * which would remove the kernel-level guarantee this project is built on. A process
 * without INTERNET is refused socket creation by the kernel; a process with it merely
 * promises not to use one. See the header comment in AndroidManifest.xml.
 *
 * So the radio is driven directly. `BluetoothAdapter`, `BluetoothGatt` and
 * `BluetoothSocket` are all in the platform since API 5 and 18 respectively, and need no
 * library at all -- which also means the transport layer costs zero dependencies.
 *
 * ## Threading
 *
 * Every method here is safe to call from any thread and does not block: platform
 * callbacks arrive on the Binder thread pool, and the flows below re-emit on whatever
 * collector context is active. The transports that own sockets are responsible for their
 * own threads; see [BleGattTransport] and [RfcommTransport].
 */
class NearbyManager(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Whether this handset has a Bluetooth radio at all. */
    val isSupported: Boolean get() = adapter != null

    /** Whether the radio is on. Distinct from [isSupported]: supported-but-off is common. */
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /**
     * The current state, as a [NearbyError] or `null` when usable.
     *
     * Checked before every scan and connect. Discovering that Bluetooth is off *after*
     * a two-minute scan produces a far more confusing failure than checking first.
     */
    fun currentBlocker(): NearbyError? = when {
        !isSupported -> NearbyError.Unsupported
        !hasRequiredPermissions() -> NearbyError.PermissionMissing
        !isEnabled -> NearbyError.BluetoothOff
        else -> null
    }

    /**
     * Whether the runtime permissions this API level needs are held.
     *
     * Two eras, because the permission model changed at API 31. Below it, the Bluetooth
     * permissions are install-time; from 31 up they are runtime and must be requested.
     * On API 23-30 a BLE scan additionally needs a location permission, which is a
     * platform policy about deriving position from radio observations, not something the
     * app wants location for -- see AndroidManifest.xml for why it is capped at 30.
     */
    fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // No location permission is needed from 31 up: BLUETOOTH_SCAN replaces it.
            return has(PERMISSION_SCAN) && has(PERMISSION_CONNECT)
        }
        return has(PERMISSION_BLUETOOTH) && has(PERMISSION_FINE_LOCATION)
    }

    /** The permissions to request on this API level, for the permission flow. */
    fun requiredRuntimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(PERMISSION_SCAN, PERMISSION_CONNECT)
        } else {
            arrayOf(PERMISSION_FINE_LOCATION)
        }

    private fun has(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Devices already bonded with this handset.
     *
     * These are the peers an RFCOMM connection can actually reach: RFCOMM requires a
     * completed pairing, and Android will not initiate one without user interaction. So
     * rather than pretending a scan can find them, this reports the bonded set and the
     * UI offers it directly.
     *
     * Bonded devices carry `rssiDbm = null`, not a sentinel. There is no signal reading
     * for a device we are connected to by name, and inventing one -- even a well-known
     * -100 -- would put a number on screen that no measurement produced.
     */
    fun bondedDevices(): List<NearbyDevice> {
        val a = adapter ?: return emptyList()
        return runCatching {
            a.bondedDevices.orEmpty().map { device ->
                NearbyDevice(
                    address = device.address,
                    name = safeName(device),
                    transport = Transport.RFCOMM,
                    rssiDbm = null,
                    isBonded = true,
                )
            }
        }.onFailure { Log.w(TAG, "bondedDevices failed: ${it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
    }

    /**
     * The name a bonded device reports, without risking a SecurityException.
     *
     * `BluetoothDevice.getName()` requires BLUETOOTH_CONNECT on API 31+. It throws rather
     * than returning null when the permission is missing, and that must not propagate out
     * of a list-rendering path.
     */
    /**
     * Open a [ByteLink] to [device] over whichever transport it was discovered on.
     *
     * ## Why this exists rather than letting the ViewModel call the transports
     *
     * The adapter is private to this class, because it is a property of the device rather
     * than of any one caller. A ViewModel that reached for it would have to ask for it, and
     * the version of this code that did so passed a literal `null` straight into
     * `RfcommTransport.connect`, which then failed with "no Bluetooth adapter" for every
     * device, on every phone, with no way for a user to tell why.
     *
     * Resolving the adapter here fixes that class of mistake structurally: there is no
     * longer a way for a caller to connect without one.
     *
     * ## Why it returns a Result and does not throw
     *
     * Connecting fails for ordinary, expected reasons -- the peer left range, the radio
     * turned off mid-connect, pairing was removed. Those are values, not exceptions: the UI
     * shows a message, the user retries. An exception here would have to be caught by every
     * caller and the one that forgot would crash the app on a Bluetooth hiccup.
     */
    suspend fun openLink(device: NearbyDevice): Result<ByteLink> {
        val a = adapter ?: return Result.failure(
            IllegalStateException("this device has no Bluetooth adapter"),
        )
        if (!a.isEnabled) {
            return Result.failure(IllegalStateException("Bluetooth is switched off"))
        }

        return when (device.transport) {
            Transport.RFCOMM -> RfcommTransport.connect(a, device.address, device.displayName)
            Transport.GATT -> BleGattTransport.connect(context, device.address, device.displayName)
            Transport.WIFI_DIRECT -> Result.failure(
                UnsupportedOperationException(
                    "Wi-Fi Direct transport is declared in the manifest but not implemented",
                ),
            )
        }
    }

    /**
     * This device's own Bluetooth address, or null where the platform will not say.
     *
     * Needed only to hide this phone from its own device list -- a phone is always within
     * range of itself, and offering "connect to myself" would produce a conversation with a
     * peer that is the same keystore and the same screen.
     *
     * Returns null on API 31+ and the result is that this phone's own entry stays in the
     * list. That is a cosmetic miss, and it is a miss on purpose: `BluetoothAdapter.getAddress()`
     * is a hidden API there, and the supported route -- reading `Settings.Secure.ANDROID_ID`
     * -- is emphatically *not* a Bluetooth address. Substituting one for the other would
     * put a different identifier on screen under the same label, so the filter simply does
     * not apply. See docs/LIMITATIONS.md.
     */
    fun localBluetoothAddress(): String? = runCatching { adapter?.address }.getOrNull()
    private fun safeName(device: BluetoothDevice): String? = runCatching { device.name }
        .getOrNull()

    /**
     * Scan for BLE devices until the collector goes away.
     *
     * `callbackFlow` rather than a callback registered in init, for the reason the brief
     * cares about: the scan lives exactly as long as something is collecting it. When the
     * Home screen stops collecting -- screen off, navigation away, app backgrounded --
     * [awaitClose] runs and stops the scan. There is no path that leaves the radio on.
     */
    fun scanBle(): Flow<ScanEvent> = callbackFlow {
        val a = adapter
        if (a == null) {
            trySend(ScanEvent.Failed(NearbyError.Unsupported))
            close()
            return@callbackFlow
        }
        if (!a.isEnabled) {
            trySend(ScanEvent.Failed(NearbyError.BluetoothOff))
            close()
            return@callbackFlow
        }

        val scanner = BleScanner(a) { event -> trySend(event) }
        val started = scanner.start()
        if (!started) {
            trySend(ScanEvent.Failed(NearbyError.PermissionMissing))
            close()
            return@callbackFlow
        }

        awaitClose { scanner.stop() }
    }

    sealed interface ScanEvent {
        data class Found(val device: NearbyDevice) : ScanEvent
        data class Failed(val error: NearbyError) : ScanEvent
    }

    private companion object {
        const val TAG = "NearbyManager"
        const val PERMISSION_BLUETOOTH = "android.permission.BLUETOOTH"
        const val PERMISSION_SCAN = "android.permission.BLUETOOTH_SCAN"
        const val PERMISSION_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val PERMISSION_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    }
}
