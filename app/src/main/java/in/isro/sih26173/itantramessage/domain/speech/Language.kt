package `in`.isro.sih26173.itantramessage.domain.speech

/**
 * Supported languages (offline-capable). Order matches UI; keep stable.
 */
enum class Language(
    val code: String,
    val displayName: String,
    val ttsTag: String,
    val sttTag: String,
) {
    ENGLISH("en", "English", "en-US", "en-US"),
    HINDI("hi", "हिन्दी", "hi-IN", "hi-IN"),
    GUJARATI("gu", "ગુજરાતી", "gu-IN", "gu-IN"),
    MARATHI("mr", "मराठी", "mr-IN", "mr-IN"),
    KANNADA("kn", "ಕನ್ನಡ", "kn-IN", "kn-IN"),
    MALAYALAM("ml", "മലയാളം", "ml-IN", "ml-IN"),
    TAMIL("ta", "தமிழ்", "ta-IN", "ta-IN"),
    TELUGU("te", "తెలుగు", "te-IN", "te-IN"),
    ODIA("or", "ଓଡ଼ିଆ", "or-IN", "or-IN"),
    BENGALI("bn", "বাংলা", "bn-IN", "bn-IN");

    companion object {
        fun fromCode(code: String): Language = entries.find { it.code == code } ?: ENGLISH
    }
}
