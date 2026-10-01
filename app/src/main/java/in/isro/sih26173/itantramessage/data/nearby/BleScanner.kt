package `in`.isro.sih26173.itantramessage.data.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE discovery, wrapped so its lifetime is explicit.
 *
 * A [BluetoothLeScanner] holds the radio. Every instance here is created by
 * [NearbyManager.scanBle] and destroyed by that flow's `awaitClose`, so the scan is
 * running if and only if something is collecting the flow. This is the whole reason the
 * class exists rather than a bare `startScan` call in the ViewModel: a scan left running
 * is the single most reliable way to drain a small device's battery, and it is invisible
 * -- nothing crashes, nothing reports an error, the phone is just warm by lunchtime.
 *
 * ## Scan mode
 *
 * [ScanSettings.SCAN_MODE_LOW_LATENCY] while the user is actively looking for devices, and
 * nothing at all the rest of the time. There is no periodic background rescan. The brief
 * asks for discovery not to run continuously, and "no rescan" is the only way to actually
 * achieve that rather than approximately achieve it.
 */
internal class BleScanner(
    private val adapter: BluetoothAdapter,
    private val onEvent: (NearbyManager.ScanEvent) -> Unit,
) {
    private val scanner: BluetoothLeScanner? = runCatching { adapter.bluetoothLeScanner }.getOrNull()
    private val seen = ConcurrentHashMap<String, NearbyDevice>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            emit(result)
        }

        // Batch form. Some devices deliver results this way instead of one at a time; a
        // callback that only handled onScanResult would silently miss every result on
        // those handsets, which is a bug that appears only on certain hardware.
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { emit(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed, code=$errorCode")
            onEvent(
                NearbyManager.ScanEvent.Failed(
                    if (errorCode == SCAN_FAILED_INTERNAL_ERROR) {
                        NearbyError.PermissionMissing
                    } else {
                        NearbyError.TransportFailed("Bluetooth scan error $errorCode")
                    },
                ),
            )
        }
    }

    private fun emit(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return

        val advertisedName = runCatching { result.scanRecord?.deviceName }.getOrNull()
        val recordName = advertisedName?.takeIf { it.isNotBlank() }
        // A BLE scan record often carries no name at all. The address-derived fallback in
        // NearbyDevice covers that, so a null here is normal traffic and not a warning.
        val deviceName = recordName ?: runCatching { device.name }.getOrNull()

        val previous = seen.put(
            address,
            NearbyDevice(
                address = address,
                name = deviceName,
                transport = Transport.GATT,
                rssiDbm = result.rssi,
                isBonded = false,
            ),
        )

        // Re-emit only when something a user can see actually changed. A scan fires
        // roughly 10 times a second per device and the RSSI jitters every time; forwarding
        // all of it would recompose the device list constantly for no visible benefit.
        if (previous?.displayName != deviceName) {
            onEvent(
                NearbyManager.ScanEvent.Found(
                    seen[address]!!,
                ),
            )
        }
    }

    /**
     * Begin scanning, reporting whether the platform accepted the request.
     *
     * A false return means the scan did not start -- typically a missing runtime
     * permission, where the platform calls back with `onScanFailed` instead of throwing.
     */
    @SuppressLint("MissingPermission") // Guarded by NearbyManager.hasRequiredPermissions().
    fun start(): Boolean {
        val s = scanner ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            // The service UUID is the one iTantra advertises, so a scan only wakes the app
            // for a peer that can actually talk to it. A filter that matches nothing means
            // this handset never appears, which is a correctness problem, not an
            // optimisation -- so it is deliberately left off until the advertised UUID is
            // confirmed present on both ends. See docs/NETWORK_PROTOCOL.md.
            //
            // .setScanFilter(listOf(ScanFilter.Builder().setServiceUuid(OUR_UUID).build()))
            .build()

        return runCatching { s.startScan(null, settings, callback) }.isSuccess
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { scanner?.stopScan(callback) }
            .onFailure { Log.w(TAG, "stopScan failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "BleScanner"
        const val SCAN_FAILED_INTERNAL_ERROR = 1
    }
}
