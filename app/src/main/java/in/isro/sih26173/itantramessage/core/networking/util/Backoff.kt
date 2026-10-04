package `in`.isro.sih26173.itantramessage.core.networking.util

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with jitter (production-safe).
 */
data class Backoff(
    private val initialMs: Long = 200L,
    private val maxMs: Long = 30_000L,
    private val multiplier: Double = 2.0,
    private val jitter: Double = 0.1
) {
    private var attempt: Long = 0

    fun nextDelay(): Long {
        val base = min(maxMs.toDouble(), initialMs.toDouble() * Math.pow(multiplier, attempt.toDouble()))
        val j = (Random.nextDouble(-jitter, jitter) * base).toLong()
        attempt++
        return min(maxMs, (base.toLong() + j).coerceAtLeast(initialMs))
    }

    fun reset() {
        attempt = 0
    }
}
