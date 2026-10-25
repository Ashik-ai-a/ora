package app.airbox.core.transfer

/**
 * Internal error codes. Every failure the engine reports carries one of these so the UI
 * can map it to a human message ([userMessage]) and analytics can count it without
 * ever touching file names or content.
 */
enum class ErrorCode(val userMessage: String) {
    PROTOCOL_VERSION_UNSUPPORTED("The other device runs an incompatible version of Airbox. Update both apps."),
    PROTOCOL_VIOLATION("The other device sent something unexpected. The transfer was stopped for safety."),
    FRAME_TOO_LARGE("The other device sent an oversized message. The transfer was stopped for safety."),
    PEER_NOT_TRUSTED("This device isn't paired yet. Confirm the pairing code on both screens."),
    PAIRING_FAILED("The pairing codes didn't match, so the devices were not connected."),
    PAIRING_TOKEN_INVALID("This pairing code or QR code has expired. Show a new one on the receiving device."),
    REJECTED_BY_RECEIVER("The receiver declined the transfer."),
    RECEIVER_BUSY("The receiver is busy with another transfer. Try again in a moment."),
    RATE_LIMITED("Too many requests to this device. Wait a minute and try again."),
    INSUFFICIENT_STORAGE("The receiving device doesn't have enough free space."),
    OFFER_INVALID("The file list from the sender is invalid."),
    INTEGRITY_CHECK_FAILED("A file was damaged in transit and was discarded. Retry to send it again."),
    SOURCE_FILE_CHANGED("A file changed or was deleted on the sending device before it finished sending."),
    CONNECTION_LOST("The connection was lost. The transfer can resume where it stopped."),
    CANCELLED_BY_PEER("The other device cancelled the transfer."),
    CANCELLED_LOCALLY("You cancelled the transfer."),
    STORAGE_ERROR("Airbox couldn't save a file on this device."),
    NO_TRANSPORT_AVAILABLE("No way to reach nearby devices is available right now."),
}

class AirboxException(
    val code: ErrorCode,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail?.let { "${code.name}: $it" } ?: code.name, cause)
