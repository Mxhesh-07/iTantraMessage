package `in`.isro.sih26173.itantramessage.core.neural

import java.util.LinkedHashMap

/**
 * LRU cache for language models - ensures only active model stays resident (sub-150MB target).
 */
class LanguageModelCache<K, V>(
    private val maxEntries: Int = 1
) : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean {
        return size > maxEntries
    }
}
