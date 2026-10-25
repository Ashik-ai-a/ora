package app.airbox.core.companion

import app.airbox.core.protocol.*

import app.airbox.core.protocol.AuthStatus
import app.airbox.core.protocol.Cancel
import app.airbox.core.protocol.FrameLimits
import app.airbox.core.protocol.Hello
import app.airbox.core.protocol.Message
import app.airbox.core.protocol.MessageChannel
import app.airbox.core.protocol.PROTOCOL_VERSION
import app.airbox.core.protocol.Purpose
import app.airbox.core.security.TrustStore
import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.ErrorCode
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Callbacks run on the link's reader thread; hand off to the UI thread as needed. */
interface LinkListener {
    fun onMessage(link: CompanionLink, message: Message)
    fun onDisconnected(link: CompanionLink, error: ErrorCode?) {}
}

/**
 * A long-lived, bidirectional connection between two devices that have ALREADY paired in
 * both directions. It never pairs: an untrusted peer is refused, so a stranger can't push
 * notifications, clipboard text or links onto your phone.
 *
 * Runs over any [app.airbox.core.protocol.SecureStream]: TLS over Wi-Fi, or TLS over a
 * Bluetooth LE L2CAP channel, which is what keeps the link alive while the iPhone app is
 * in the background (iOS "bluetooth-central" background mode).
 */
class CompanionLink private constructor(
    private val channel: MessageChannel,
    val peer: Hello,
    /** Features both sides support. */
    val features: Set<LinkFeature>,
    private val listener: LinkListener,
    pingIntervalMs: Long,
    peerPingIntervalMs: Long,
) : Closeable {
    private val open = AtomicBoolean(true)
    private val pinger: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "airbox-link-ping").apply { isDaemon = true } }

    init {
        // Time out on the PEER's keepalive cadence, not ours.
        channel.stream.setReadTimeout((peerPingIntervalMs.coerceIn(100, 120_000) * 3).toInt())
        pinger.scheduleAtFixedRate({ runCatching { send(LinkPing) }.onFailure { shutdown(ErrorCode.CONNECTION_LOST) } },
            pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS)
        Thread({ readLoop() }, "airbox-link-reader").apply { isDaemon = true }.start()
    }

    val isOpen: Boolean get() = open.get()

    /** Throws if the feature wasn't negotiated, so callers can't silently send into the void. */
    fun send(message: Message) {
        val feature = message.linkFeature()
        check(feature == null || feature in features) { "feature $feature not enabled on this link" }
        if (!open.get()) throw AirboxException(ErrorCode.CONNECTION_LOST)
        val bytes = app.airbox.core.protocol.MessageCodec.encode(message)
        require(bytes.size <= FrameLimits.MAX_CONTROL_BYTES) { "message too large" }
        synchronized(channel) { channel.send(message) }
    }

    private fun readLoop() {
        try {
            while (open.get()) {
                val message = channel.receive()
                if (message is LinkPing) continue
                val feature = message.linkFeature()
                // Drop anything outside the negotiated features instead of trusting the peer.
                if (feature != null && feature !in features) continue
                if (feature == null) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "unexpected ${message::class.simpleName}")
                listener.onMessage(this, message)
            }
        } catch (e: AirboxException) {
            shutdown(e.code)
        } catch (e: Exception) {
            shutdown(ErrorCode.CONNECTION_LOST)
        }
    }

    private fun shutdown(error: ErrorCode?) {
        if (!open.compareAndSet(true, false)) return
        pinger.shutdownNow()
        runCatching { channel.close() }
        listener.onDisconnected(this, error)
    }

    override fun close() = shutdown(null)

    companion object {
        const val DEFAULT_PING_MS = 15_000L

        /**
         * Opens a link to a trusted device. [channel] MUST come from
         * [app.airbox.core.net.TlsTransport.connectPinned] with the fingerprint stored at pairing.
         */
        fun connect(
            channel: MessageChannel,
            localHello: Hello,
            supported: Set<LinkFeature>,
            listener: LinkListener,
            pingIntervalMs: Long = DEFAULT_PING_MS,
        ): CompanionLink {
            try {
                channel.send(localHello.copy(purpose = Purpose.LINK))
                val peer = channel.expect<Hello>()
                if (peer.protocolVersion != PROTOCOL_VERSION) throw AirboxException(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED)
                if (!channel.expect<AuthStatus>().trusted) throw AirboxException(ErrorCode.PEER_NOT_TRUSTED)
                channel.send(LinkHello(supported, pingIntervalMs))
                val theirs = channel.expect<LinkHello>()
                return CompanionLink(channel, peer, supported intersect theirs.features, listener, pingIntervalMs, theirs.pingIntervalMs)
            } catch (e: Exception) {
                runCatching { channel.close() }
                throw e
            }
        }

        /** Server side, called by [app.airbox.core.net.IncomingRouter] after reading a LINK hello. */
        fun accept(
            channel: MessageChannel,
            peerHello: Hello,
            localHello: Hello,
            trustStore: TrustStore,
            supported: Set<LinkFeature>,
            listener: LinkListener,
            pingIntervalMs: Long = DEFAULT_PING_MS,
        ): CompanionLink {
            try {
                channel.send(localHello)
                val trusted = trustStore.isTrusted(channel.stream.peerFingerprint)
                channel.send(AuthStatus(trusted))
                if (!trusted) {
                    channel.send(Cancel(ErrorCode.PEER_NOT_TRUSTED))
                    throw AirboxException(ErrorCode.PEER_NOT_TRUSTED)
                }
                val theirs = channel.expect<LinkHello>()
                channel.send(LinkHello(supported, pingIntervalMs))
                return CompanionLink(channel, peerHello, supported intersect theirs.features, listener, pingIntervalMs, theirs.pingIntervalMs)
            } catch (e: Exception) {
                runCatching { channel.close() }
                throw e
            }
        }
    }
}
