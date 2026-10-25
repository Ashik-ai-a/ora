package app.airbox.core.protocol

import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.ErrorCode
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A bidirectional, already-encrypted and authenticated byte stream to one peer. The
 * transfer engine is transport-agnostic: TLS sockets, Wi-Fi Aware sockets and Nearby
 * stream payloads all adapt to this.
 */
interface SecureStream : Closeable {
    val input: InputStream
    val output: OutputStream

    /** SHA-256 of the peer's certificate (DER), lowercase hex. */
    val peerFingerprint: String

    /** SHA-256 of our own certificate (DER), lowercase hex. */
    val localFingerprint: String

    /** Longer waits are needed while a person decides whether to accept. 0 = forever. */
    fun setReadTimeout(millis: Int) {}
}

/** Framed message channel over a [SecureStream]. */
class MessageChannel(val stream: SecureStream, maxDataBytes: Int = FrameLimits.MAX_DATA_BYTES) : Closeable {
    private val reader = FrameReader(stream.input, maxDataBytes)
    private val writer = FrameWriter(stream.output)

    fun send(message: Message) {
        writer.write(FrameType.CONTROL, MessageCodec.encode(message))
        writer.flush()
    }

    fun sendData(buffer: ByteArray, length: Int) = writer.write(FrameType.DATA, buffer, 0, length)

    fun flush() = writer.flush()

    fun readFrame(): Frame = reader.read()

    fun receive(): Message {
        val frame = reader.read()
        if (frame.type != FrameType.CONTROL) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "expected control frame")
        val message = MessageCodec.decode(frame.payload)
        if (message is Cancel) throw AirboxException(ErrorCode.CANCELLED_BY_PEER, message.code.name)
        return message
    }

    inline fun <reified T : Message> expect(): T {
        val message = receive()
        if (message is Reject) throw AirboxException(message.code, "peer rejected")
        return message as? T
            ?: throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "expected ${T::class.simpleName}, got ${message::class.simpleName}")
    }

    override fun close() = stream.close()
}
