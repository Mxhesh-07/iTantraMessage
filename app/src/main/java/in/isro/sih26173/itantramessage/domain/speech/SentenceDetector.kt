package `in`.isro.sih26173.itantramessage.domain.speech

/**
 * Simple sentence boundary detector for spoken text. Pure domain, offline.
 */
class SentenceDetector(
    private val minChars: Int = 3,
    private val endPunct: Set<Char> = setOf('.', '?', '!', '。', '？', '！', '।'),
) {
    fun isCompleteSentence(text: String): Boolean {
        if (text.length < minChars) return false
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return false
        val last = trimmed.last()
        return last in endPunct || trimmed.endsWith("\n")
    }

    fun trimToSentence(text: String): String = text.trim()
}
