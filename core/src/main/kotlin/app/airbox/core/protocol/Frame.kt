package app.airbox.core.protocol

import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.ErrorCode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wire framing, shared by every transport (TLS over TCP, Wi-Fi Aware data paths,
 * Nearby stream payloads). Layout, big-endian:
 *
 *   +--------+----------------+---------------------+
 *   | type 1 | length 4 (u32) | payload (length)    |
 *   +--------+----------------+---------------------+
 *
 * CONTROL payloads are UTF-8 JSON ([Message]); DATA payloads are raw file bytes for the
 * file announced by the last FILE_START.
 */
enum class FrameType(val wire: Int) {
    CONTROL(1),
    DATA(2);

    companion object {
        fun fromWire(value: Int): FrameType =
            entries.firstOrNull { it.wire == value }
                ?: throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "unknown frame type $value")
    }
}

class Frame(val type: FrameType, val payload: ByteArray, val length: Int = payload.size)

object FrameLimits {
    const val MAX_CONTROL_BYTES = 1 shl 20 // 1 MiB: a 10k-file offer fits comfortably.
    const val MAX_DATA_BYTES = 4 shl 20 // 4 MiB upper bound for negotiated chunk size.
    const val DEFAULT_CHUNK_BYTES = 256 * 1024
}

class FrameWriter(output: OutputStream) {
    private val out = DataOutputStream(output)

    @Synchronized
    fun write(type: FrameType, payload: ByteArray, offset: Int = 0, length: Int = payload.size) {
        out.writeByte(type.wire)
        out.writeInt(length)
        out.write(payload, offset, length)
    }

    @Synchronized
    fun flush() = out.flush()
}

/**
 * Reads frames, enforcing size limits before allocating so a hostile peer cannot make
 * us allocate gigabytes. DATA frames are read into a reused buffer.
 */
class FrameReader(input: InputStream, private val maxDataBytes: Int = FrameLimits.MAX_DATA_BYTES) {
    private val inp = DataInputStream(input)
    private var dataBuffer = ByteArray(0)

    fun read(): Frame {
        val typeByte = inp.read()
        if (typeByte < 0) throw EOFException("peer closed the connection")
        val type = FrameType.fromWire(typeByte)
        val length = inp.readInt()
        val limit = if (type == FrameType.CONTROL) FrameLimits.MAX_CONTROL_BYTES else maxDataBytes
        if (length < 0 || length > limit) {
            throw AirboxException(ErrorCode.FRAME_TOO_LARGE, "$type frame of $length bytes")
        }
        return if (type == FrameType.CONTROL) {
            Frame(type, ByteArray(length).also { inp.readFully(it) })
        } else {
            if (dataBuffer.size < length) dataBuffer = ByteArray(maxOf(length, FrameLimits.DEFAULT_CHUNK_BYTES))
            inp.readFully(dataBuffer, 0, length)
            Frame(type, dataBuffer, length)
        }
    }
}
