package app.airbox.core.net

import app.airbox.core.protocol.SecureStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.ErrorCode
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Mutually-authenticated TLS 1.3 using self-signed device certificates.
 *
 * The TLS trust manager accepts any certificate: there is no CA in a peer-to-peer world.
 * TLS still proves each side holds the private key for the certificate it presented;
 * the Airbox session then decides whether that fingerprint is trusted (trust store,
 * pinned fingerprint from a QR code, or SAS confirmation). Nothing is sent before that.
 */
class TlsTransport(private val identity: DeviceIdentity) {
    private val context: SSLContext = SSLContext.getInstance("TLSv1.3").apply {
        val password = CharArray(0)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(identity.toKeyStore(password), password)
        init(kmf.keyManagers, arrayOf(AcceptAnyCertificate), null)
    }

    fun connect(host: String, port: Int, connectTimeoutMs: Int = 5_000): TlsStream {
        val raw = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), connectTimeoutMs)
            soTimeout = DEFAULT_READ_TIMEOUT_MS
        }
        val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = true
        socket.startHandshake()
        return TlsStream(socket, identity.fingerprint)
    }

    /**
     * Connects and requires the peer to present exactly [expectedFingerprint] (from a QR code,
     * deep link or the trust store). Nothing is sent to an impostor.
     */
    fun connectPinned(host: String, port: Int, expectedFingerprint: String): TlsStream {
        val stream = connect(host, port)
        if (!MessageDigest.isEqual(stream.peerFingerprint.toByteArray(), expectedFingerprint.lowercase().toByteArray())) {
            stream.close()
            throw AirboxException(ErrorCode.PEER_NOT_TRUSTED, "certificate fingerprint mismatch")
        }
        return stream
    }

    fun listen(port: Int, bindAddress: String? = null): SSLServerSocket {
        val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        server.reuseAddress = true
        server.bind(if (bindAddress != null) InetSocketAddress(bindAddress, port) else InetSocketAddress(port))
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.needClientAuth = true
        return server
    }

    fun accept(server: ServerSocket): TlsStream = handshakeAccepted(server.accept() as SSLSocket)

    /** Run off the accept thread so a slow client can't stall other connections. */
    fun handshakeAccepted(socket: SSLSocket): TlsStream {
        socket.tcpNoDelay = true
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        socket.startHandshake()
        socket.soTimeout = DEFAULT_READ_TIMEOUT_MS
        return TlsStream(socket, identity.fingerprint)
    }

    private object AcceptAnyCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = requireOne(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = requireOne(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun requireOne(chain: Array<out X509Certificate>) {
            require(chain.isNotEmpty()) { "peer presented no certificate" }
        }
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
    }
}

class TlsStream(val socket: SSLSocket, override val localFingerprint: String) : SecureStream {
    override val input: InputStream = BufferedInputStream(socket.inputStream, 64 * 1024)
    override val output: OutputStream = BufferedOutputStream(socket.outputStream, 64 * 1024)
    override val peerFingerprint: String =
        DeviceIdentity.fingerprintOf(socket.session.peerCertificates.first() as X509Certificate)

    override fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    val remoteAddress: String get() = (socket.remoteSocketAddress as InetSocketAddress).address.hostAddress

    override fun close() {
        // JDK TLS 1.3 close can wait for the peer's close_notify; don't let a peer hold us.
        runCatching { socket.soTimeout = 1_000 }
        socket.close()
    }
}
