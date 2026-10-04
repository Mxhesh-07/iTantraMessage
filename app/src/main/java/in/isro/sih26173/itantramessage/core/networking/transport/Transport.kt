package `in`.isro.sih26173.itantramessage.core.networking.transport

import `in`.isro.sih26173.itantramessage.core.networking.model.Packet
import kotlinx.coroutines.flow.Flow

/**
 * Transport abstraction. Existing implementations can be wrapped later.
 * Keep non-breaking - not wired yet.
 */
interface Transport {
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun connect(deviceId: String)
    suspend fun disconnect(deviceId: String)
    suspend fun send(packet: Packet)
    fun observeDevices(): Flow<List<PeerDevice>>
    fun observeConnections(): Flow<List<Connection>>
}

data class PeerDevice(
    val id: String,
    val name: String,
    val address: String? = null,
    val transport: String = "UNKNOWN"
)

data class Connection(
    val deviceId: String,
    val transport: String,
    val connected: Boolean
)
