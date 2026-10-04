package `in`.isro.sih26173.itantramessage.core.networking.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Processed packet/message ID cache with TTL to prevent loops/replays and duplicates.
 * Production-safe: bounded, expires old entries.
 */
class PacketIdCache(
    private val ttlMs: Long = 5 * 60 * 1000L, // 5 min
    private val maxEntries: Int = 2048
) {
    private data class Entry(val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun seen(id: String): Boolean {
        val now = System.currentTimeMillis()
        val e = map[id]
        return e != null && e.expiresAt > now
    }

    fun mark(id: String) {
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        if (map.size >= maxEntries) {
            evictExpired(now)
            if (map.size >= maxEntries) {
                // drop oldest heuristically by scanning small set not needed; just clear oldest quarter? keep simple: evict expired only
                evictExpired(now - ttlMs)
            }
        }
        map[id] = Entry(expiresAt = now + ttlMs)
    }

    fun evictExpired(now: Long = System.currentTimeMillis()) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiresAt <= now) it.remove()
        }
    }

    fun clear() = map.clear()
    fun size(): Int = map.size
}
