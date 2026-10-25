package app.airbox.core.protocol

import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.ErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1

enum class Platform { ANDROID, IOS, DESKTOP }

/** Why a connection was opened. Older peers omit it, which means TRANSFER. */
enum class Purpose { TRANSFER, LINK }

/**
 * Control messages. JSON with a "type" discriminator so the iOS side can decode them with
 * plain Codable. Unknown fields are ignored so newer minor revisions stay compatible.
 */
@Serializable
sealed interface Message

@Serializable
@SerialName("hello")
data class Hello(
    val protocolVersion: Int,
    /** Rotating, per-session identifier. Never a stable hardware ID. */
    val sessionId: String,
    val deviceName: String,
    val platform: Platform,
    val maxChunkBytes: Int = FrameLimits.DEFAULT_CHUNK_BYTES,
    val purpose: Purpose = Purpose.TRANSFER,
) : Message

/** Receiver -> sender after HELLO: whether the sender's certificate is already trusted. */
@Serializable
@SerialName("auth_status")
data class AuthStatus(val trusted: Boolean) : Message

/** Proves the sender scanned the receiver's QR code / typed its one-time code. */
@Serializable
@SerialName("pair_token")
data class PairToken(val token: String) : Message

/** Short-authentication-string pairing (see security/SasPairing.kt). */
@Serializable
@SerialName("sas_commit")
data class SasCommit(val commitment: String) : Message

@Serializable
@SerialName("sas_nonce")
data class SasNonce(val nonce: String) : Message

@Serializable
@SerialName("sas_reveal")
data class SasReveal(val nonce: String) : Message

@Serializable
@SerialName("pair_result")
data class PairResult(val confirmed: Boolean) : Message

@Serializable
data class FileEntry(
    val index: Int,
    val name: String,
    val size: Long,
    val mimeType: String = "application/octet-stream",
    val lastModifiedEpochMs: Long? = null,
)

@Serializable
@SerialName("offer")
data class Offer(
    /** Stable for the life of one logical transfer; reused on resume. */
    val transferId: String,
    val files: List<FileEntry>,
) : Message {
    val totalBytes: Long get() = files.sumOf { it.size }
}

@Serializable
@SerialName("accept")
data class Accept(
    val transferId: String,
    /** Bytes already held by the receiver, per file index. Missing = start at 0. */
    val resumeOffsets: Map<Int, Long> = emptyMap(),
    val chunkBytes: Int,
) : Message

@Serializable
@SerialName("reject")
data class Reject(val transferId: String, val code: ErrorCode) : Message

@Serializable
@SerialName("file_start")
data class FileStart(val index: Int, val offset: Long) : Message

@Serializable
@SerialName("file_end")
data class FileEnd(val index: Int, val sha256: String) : Message

@Serializable
@SerialName("file_result")
data class FileResult(val index: Int, val ok: Boolean, val code: ErrorCode? = null) : Message

@Serializable
@SerialName("complete")
data object Complete : Message

/** Sender is pausing; the receiver keeps partial data for a later resume. */
@Serializable
@SerialName("pause")
data object Pause : Message

@Serializable
@SerialName("cancel")
data class Cancel(val code: ErrorCode) : Message

object MessageCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun encode(message: Message): ByteArray = json.encodeToString(Message.serializer(), message).toByteArray()

    fun decode(bytes: ByteArray): Message =
        try {
            json.decodeFromString(Message.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "malformed control message", e)
        } catch (e: IllegalArgumentException) {
            throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "malformed control message", e)
        }
}
