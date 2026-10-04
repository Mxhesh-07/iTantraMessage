package `in`.isro.sih26173.itantramessage.core.networking.model

/**
 * Generic packet envelope for offline mesh. Payload is opaque bytes (encrypted).
 * Fields align with master spec; kept minimal and non-breaking.
 */
data class Packet(
    val packetId: String,
    val messageId: String,
    val senderId: String,
    val destinationId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val hopCount: Int = 0,
    val maxHops: Int = 16,
    val packetType: PacketType = PacketType.MESSAGE,
    val priority: Priority = Priority.NORMAL,
    val payload: ByteArray,
    val ttl: Long = expiresAt - createdAt
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Packet
        return packetId == other.packetId
    }

    override fun hashCode(): Int = packetId.hashCode()
}
