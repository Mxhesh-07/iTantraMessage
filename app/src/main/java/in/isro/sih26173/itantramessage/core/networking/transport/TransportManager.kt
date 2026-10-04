package `in`.isro.sih26173.itantramessage.core.networking.transport

import `in`.isro.sih26173.itantramessage.core.networking.model.Packet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adaptive transport manager. Pluggable adapters; defaults to no-op until wired.
 */
class TransportManager(
    private val transports: List<Transport> = emptyList()
) : Transport {
    private val _active = MutableStateFlow<String?>(null)
    val activeTransport: StateFlow<String?> = _active

    override suspend fun startDiscovery() {
        transports.forEach { it.startDiscovery() }
    }

    override suspend fun stopDiscovery() {
        transports.forEach { it.stopDiscovery() }
    }

    override suspend fun connect(deviceId: String) {
        // Select best available when wired
        transports.firstOrNull()?.connect(deviceId)
    }

    override suspend fun disconnect(deviceId: String) {
        transports.forEach { it.disconnect(deviceId) }
    }

    override suspend fun send(packet: Packet) {
        // Route via selected transport when wired
        transports.firstOrNull()?.send(packet)
    }

    override fun observeDevices(): Flow<List<PeerDevice>> = kotlinx.coroutines.flow.emptyFlow()

    override fun observeConnections(): Flow<List<Connection>> = kotlinx.coroutines.flow.emptyFlow()
}
