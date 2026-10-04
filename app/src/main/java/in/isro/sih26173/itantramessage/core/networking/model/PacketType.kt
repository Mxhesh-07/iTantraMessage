package `in`.isro.sih26173.itantramessage.core.networking.model

enum class PacketType {
    MESSAGE,
    ACK,
    DELIVERY_RECEIPT,
    DISCOVERY,
    ROUTE_REQUEST,
    ROUTE_RESPONSE,
    EMERGENCY,
    CANCEL
}
