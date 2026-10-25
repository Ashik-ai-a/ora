package app.airbox.core.net

import app.airbox.core.protocol.Cancel
import app.airbox.core.protocol.MessageChannel
import app.airbox.core.transfer.ErrorCode
import java.io.Closeable
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accepts incoming TLS connections and hands each to [handler]. Abuse protection: one active
 * transfer at a time, and a per-address connection budget per minute.
 */
class ReceiverServer(
    private val transport: TlsTransport,
    port: Int,
    private val handler: (MessageChannel) -> Unit,
    /** Transfers are limited to one at a time by [IncomingRouter]; links are long-lived. */
    private val maxConcurrent: Int = 4,
    private val maxConnectionsPerMinutePerAddress: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
    bindAddress: String? = null,
) : Closeable {
    private val server = transport.listen(port, bindAddress)
    val port: Int get() = server.localPort
    private val running = AtomicBoolean(true)
    private val active = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "airbox-receiver").apply { isDaemon = true } }
    private val recent = ConcurrentHashMap<String, MutableList<Long>>()

    fun start(): ReceiverServer {
        Thread({ acceptLoop() }, "airbox-accept").apply { isDaemon = true }.start()
        return this
    }

    private fun acceptLoop() {
        while (running.get()) {
            val raw = try { server.accept() } catch (e: SocketException) { break }
            pool.execute {
                val stream = try {
                    transport.handshakeAccepted(raw as javax.net.ssl.SSLSocket)
                } catch (e: Exception) {
                    runCatching { raw.close() }
                    return@execute
                }
                val channel = MessageChannel(stream)
                if (!allow(stream.remoteAddress)) {
                    runCatching { channel.send(Cancel(ErrorCode.RATE_LIMITED)) }
                    channel.close()
                    return@execute
                }
                if (active.incrementAndGet() > maxConcurrent) {
                    active.decrementAndGet()
                    runCatching { channel.send(Cancel(ErrorCode.RECEIVER_BUSY)) }
                    channel.close()
                    return@execute
                }
                try {
                    handler(channel)
                } finally {
                    active.decrementAndGet()
                    channel.close()
                }
            }
        }
    }

    private fun allow(address: String): Boolean {
        val now = clock()
        val times = recent.computeIfAbsent(address) { mutableListOf() }
        synchronized(times) {
            times.removeAll { now - it > 60_000 }
            if (times.size >= maxConnectionsPerMinutePerAddress) return false
            times += now
            return true
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        pool.shutdownNow()
    }
}
