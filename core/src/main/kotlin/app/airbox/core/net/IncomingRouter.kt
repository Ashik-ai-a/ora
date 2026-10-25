package app.airbox.core.net

import app.airbox.core.companion.CompanionLink
import app.airbox.core.companion.LinkListener
import app.airbox.core.protocol.Cancel
import app.airbox.core.protocol.Hello
import app.airbox.core.protocol.LinkFeature
import app.airbox.core.protocol.MessageChannel
import app.airbox.core.protocol.Purpose
import app.airbox.core.security.TrustStore
import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.ErrorCode
import java.util.concurrent.Semaphore

/**
 * Reads the peer's hello and routes the connection: file transfers (one at a time) or a
 * long-lived companion link with a trusted device. Pass [handle] as the server handler.
 */
class IncomingRouter(
    private val localHello: Hello,
    private val trustStore: TrustStore,
    private val linkFeatures: Set<LinkFeature>,
    private val linkListener: LinkListener,
    private val onLink: (CompanionLink) -> Unit,
    private val runTransfer: (MessageChannel, Hello) -> Unit,
) {
    private val transferSlot = Semaphore(1)

    fun handle(channel: MessageChannel) {
        val peer = try { channel.expect<Hello>() } catch (e: Exception) { return }
        when (peer.purpose) {
            Purpose.LINK -> {
                val link = try {
                    CompanionLink.accept(channel, peer, localHello, trustStore, linkFeatures, linkListener)
                } catch (e: AirboxException) {
                    return
                }
                onLink(link)
                // Keep the server's worker thread (and the socket) alive for the link's lifetime.
                while (link.isOpen) Thread.sleep(500)
            }
            Purpose.TRANSFER -> {
                if (!transferSlot.tryAcquire()) {
                    runCatching { channel.send(Cancel(ErrorCode.RECEIVER_BUSY)) }
                    return
                }
                try { runTransfer(channel, peer) } finally { transferSlot.release() }
            }
        }
    }
}
