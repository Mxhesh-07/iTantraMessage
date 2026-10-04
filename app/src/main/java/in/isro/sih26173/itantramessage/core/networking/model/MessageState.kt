package `in`.isro.sih26173.itantramessage.core.networking.model

enum class MessageState {
    QUEUED,
    SENDING,
    SENT_TO_RELAY,
    FORWARDED,
    DELIVERED,
    EXPIRED,
    FAILED
}
