package app.airbox.core.companion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Apple Notification Center Service (ANCS) client codec.
 *
 * An iPhone exposes ANCS over Bluetooth LE to any *bonded* accessory. This is the public,
 * documented way smartwatches get iPhone notifications, and it lets the Android app act
 * as that accessory: it shows iPhone notifications (messages, calls, any app) and can
 * trigger the notification's positive/negative action (e.g. answer / decline a call).
 * ANCS does NOT provide text replies or message history.
 *
 * Pure byte-level code so it can be unit tested off-device. The Android BLE layer feeds
 * GATT notifications in and writes the encoded commands to the Control Point.
 */
object Ancs {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    enum class EventId { ADDED, MODIFIED, REMOVED }

    enum class Category {
        OTHER, INCOMING_CALL, MISSED_CALL, VOICEMAIL, SOCIAL, SCHEDULE, EMAIL, NEWS,
        HEALTH_AND_FITNESS, BUSINESS_AND_FINANCE, LOCATION, ENTERTAINMENT,
    }

    object EventFlags {
        const val SILENT = 1 shl 0
        const val IMPORTANT = 1 shl 1
        const val PRE_EXISTING = 1 shl 2
        const val POSITIVE_ACTION = 1 shl 3
        const val NEGATIVE_ACTION = 1 shl 4
    }

    enum class NotificationAttribute(val id: Int, val needsMaxLength: Boolean = false) {
        APP_IDENTIFIER(0),
        TITLE(1, true),
        SUBTITLE(2, true),
        MESSAGE(3, true),
        MESSAGE_SIZE(4),
        DATE(5),
        POSITIVE_ACTION_LABEL(6),
        NEGATIVE_ACTION_LABEL(7);

        companion object { fun of(id: Int) = entries.firstOrNull { it.id == id } }
    }

    enum class Action(val id: Int) { POSITIVE(0), NEGATIVE(1) }

    private const val CMD_GET_NOTIFICATION_ATTRIBUTES = 0
    private const val CMD_GET_APP_ATTRIBUTES = 1
    private const val CMD_PERFORM_NOTIFICATION_ACTION = 2
    private const val APP_ATTRIBUTE_DISPLAY_NAME = 0

    /** Control Point error codes (ATT application errors). */
    val errors = mapOf(0xA0 to "Unknown command", 0xA1 to "Invalid command", 0xA2 to "Invalid parameter", 0xA3 to "Action failed")

    /** One 8-byte GATT notification from the Notification Source characteristic. */
    data class SourceEvent(
        val event: EventId,
        val flags: Int,
        val category: Category,
        val categoryCount: Int,
        /** Little-endian u32 on the wire. Kept as Long to stay unsigned. */
        val uid: Long,
    ) {
        val silent get() = flags and EventFlags.SILENT != 0
        val important get() = flags and EventFlags.IMPORTANT != 0
        val preExisting get() = flags and EventFlags.PRE_EXISTING != 0
        val hasPositiveAction get() = flags and EventFlags.POSITIVE_ACTION != 0
        val hasNegativeAction get() = flags and EventFlags.NEGATIVE_ACTION != 0
    }

    fun parseSource(bytes: ByteArray): SourceEvent {
        require(bytes.size >= 8) { "notification source packet must be 8 bytes, got ${bytes.size}" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val event = EventId.entries.getOrNull(b.get().toInt() and 0xff) ?: throw IllegalArgumentException("unknown event")
        val flags = b.get().toInt() and 0xff
        val category = Category.entries.getOrElse(b.get().toInt() and 0xff) { Category.OTHER }
        val count = b.get().toInt() and 0xff
        val uid = b.int.toLong() and 0xffffffffL
        return SourceEvent(event, flags, category, count, uid)
    }

    /** Asks for the attributes Airbox shows. Max lengths keep BLE traffic small. */
    fun getNotificationAttributes(
        uid: Long,
        attributes: List<NotificationAttribute> = listOf(
            NotificationAttribute.APP_IDENTIFIER, NotificationAttribute.TITLE, NotificationAttribute.SUBTITLE,
            NotificationAttribute.MESSAGE, NotificationAttribute.DATE,
            NotificationAttribute.POSITIVE_ACTION_LABEL, NotificationAttribute.NEGATIVE_ACTION_LABEL,
        ),
        maxLength: Int = 512,
    ): ByteArray {
        require(maxLength in 1..0xffff)
        val out = ByteArrayOutputStream()
        out.write(CMD_GET_NOTIFICATION_ATTRIBUTES)
        out.writeU32(uid)
        for (attr in attributes) {
            out.write(attr.id)
            if (attr.needsMaxLength) out.writeU16(maxLength)
        }
        return out.toByteArray()
    }

    fun getAppAttributes(appIdentifier: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_GET_APP_ATTRIBUTES)
        out.write(appIdentifier.toByteArray())
        out.write(0)
        out.write(APP_ATTRIBUTE_DISPLAY_NAME)
        return out.toByteArray()
    }

    fun performAction(uid: Long, action: Action): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_PERFORM_NOTIFICATION_ACTION)
        out.writeU32(uid)
        out.write(action.id)
        return out.toByteArray()
    }

    sealed interface DataResponse
    data class NotificationAttributes(val uid: Long, val values: Map<NotificationAttribute, String>) : DataResponse {
        val appIdentifier get() = values[NotificationAttribute.APP_IDENTIFIER]
        val title get() = values[NotificationAttribute.TITLE]
        val message get() = values[NotificationAttribute.MESSAGE]

        /** ANCS dates are local time "yyyyMMdd'T'HHmmSS". */
        val date: LocalDateTime?
            get() = values[NotificationAttribute.DATE]?.let {
                runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")) }.getOrNull()
            }
    }
    data class AppAttributes(val appIdentifier: String, val displayName: String?) : DataResponse

    /**
     * Data Source responses can span several GATT notifications (each ≤ MTU-3 bytes).
     * Feed every chunk in; a complete response comes out once all expected attributes
     * have arrived. Only one request may be outstanding at a time, as the spec requires.
     */
    class DataSourceAssembler {
        private val buffer = ByteArrayOutputStream()
        private var expectedNotificationAttrs: Int = -1
        private var expectingApp = false

        fun expectNotificationAttributes(count: Int) { reset(); expectedNotificationAttrs = count }
        fun expectAppAttributes() { reset(); expectingApp = true }

        fun reset() { buffer.reset(); expectedNotificationAttrs = -1; expectingApp = false }

        /** Returns a response when complete, else null. */
        fun feed(chunk: ByteArray): DataResponse? {
            buffer.write(chunk)
            val data = buffer.toByteArray()
            val parsed = when {
                expectedNotificationAttrs >= 0 -> tryParseNotification(data, expectedNotificationAttrs)
                expectingApp -> tryParseApp(data)
                else -> throw IllegalStateException("no request outstanding")
            }
            if (parsed != null) reset()
            return parsed
        }

        private fun tryParseNotification(data: ByteArray, count: Int): NotificationAttributes? {
            if (data.size < 5) return null
            require(data[0].toInt() == CMD_GET_NOTIFICATION_ATTRIBUTES) { "unexpected command in data source" }
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            b.get()
            val uid = b.int.toLong() and 0xffffffffL
            val values = linkedMapOf<NotificationAttribute, String>()
            repeat(count) {
                if (b.remaining() < 3) return null
                val id = b.get().toInt() and 0xff
                val len = b.short.toInt() and 0xffff
                if (b.remaining() < len) return null
                val bytes = ByteArray(len).also { b.get(it) }
                NotificationAttribute.of(id)?.let { values[it] = bytes.decodeToString() }
            }
            return NotificationAttributes(uid, values)
        }

        private fun tryParseApp(data: ByteArray): AppAttributes? {
            if (data.isEmpty()) return null
            require(data[0].toInt() == CMD_GET_APP_ATTRIBUTES) { "unexpected command in data source" }
            val nul = (1 until data.size).firstOrNull { data[it].toInt() == 0 } ?: return null
            val appId = data.copyOfRange(1, nul).decodeToString()
            val b = ByteBuffer.wrap(data, nul + 1, data.size - nul - 1).order(ByteOrder.LITTLE_ENDIAN)
            if (b.remaining() < 3) return null
            b.get() // attribute id (display name)
            val len = b.short.toInt() and 0xffff
            if (b.remaining() < len) return null
            val name = ByteArray(len).also { b.get(it) }.decodeToString()
            return AppAttributes(appId, name.ifEmpty { null })
        }
    }

    private fun ByteArrayOutputStream.writeU32(v: Long) {
        for (i in 0 until 4) write(((v shr (8 * i)) and 0xff).toInt())
    }

    private fun ByteArrayOutputStream.writeU16(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff)
    }
}
