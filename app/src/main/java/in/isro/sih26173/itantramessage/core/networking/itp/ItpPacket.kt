package `in`.isro.sih26173.itantramessage.core.networking.itp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * iTP (iTantra Transceiver Protocol) - Ultra-low-bitrate binary frame.
 * Magic 0x54 ('T'), Version 1. No JSON/XML.
 */
data class ItpPacket(
    val type: Byte = 0,        // 0=Voice,1=SOS,2=Ping
    val priority: Byte = 0,    // 0=Normal,1=Important,2=Emergency
    val langId: Byte = 0,      // 0-9
    val seq: Byte = 0,
    val payload: ByteArray = ByteArray(0)
) {
    companion object {
        const val MAGIC: Byte = 0x54
        const val VERSION: Byte = 1
        private const val HEADER_SIZE = 6 // MAGIC(1)+VER_TYPE(1)+PRIO_LANG(1)+SEQ(1)+LEN(2)
        private const val CRC_SIZE = 2

        fun fromBytes(bytes: ByteArray): ItpPacket? {
            if (bytes.size < HEADER_SIZE + CRC_SIZE) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val m = bb.get().toInt() and 0xFF
            if (m != (MAGIC.toInt() and 0xFF)) return null
            val verType = bb.get()
            val ver = (verType.toInt() shr 4) and 0xF
            if (ver != (VERSION.toInt() and 0xF)) return null
            val type = (verType.toInt() and 0xF).toByte()
            val prioLang = bb.get()
            val prio = (prioLang.toInt() shr 4) and 0xF
            val lang = (prioLang.toInt() and 0xF).toByte()
            val seq = bb.get()
            val len = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < len + CRC_SIZE) return null
            val payload = ByteArray(len)
            bb.get(payload)
            val crc = bb.short
            // CRC16 placeholder (accept)
            return ItpPacket(type = type, priority = prio.toByte(), langId = lang, seq = seq, payload = payload)
        }
    }

    fun toBytes(): ByteArray {
        val len = payload.size
        val bb = ByteBuffer.allocate(HEADER_SIZE + len + CRC_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.put(MAGIC)
        val verType = ((VERSION.toInt() shl 4) or (type.toInt() and 0xF)).toByte()
        bb.put(verType)
        val prioLang = (((priority.toInt() and 0xF) shl 4) or (langId.toInt() and 0xF)).toByte()
        bb.put(prioLang)
        bb.put(seq)
        bb.putShort(len.toShort())
        if (len > 0) bb.put(payload)
        bb.putShort(0x0000) // CRC placeholder
        return bb.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ItpPacket
        return type == other.type && priority == other.priority && langId == other.langId && seq == other.seq && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.toInt()
        result = 31 * result + priority.toInt()
        result = 31 * result + langId.toInt()
        result = 31 * result + seq.toInt()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
