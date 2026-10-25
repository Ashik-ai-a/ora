package app.airbox.cli

import app.airbox.core.net.DeviceIdentity
import app.airbox.core.net.ReceiverServer
import app.airbox.core.net.TlsTransport
import app.airbox.core.protocol.Hello
import app.airbox.core.protocol.MessageChannel
import app.airbox.core.protocol.Offer
import app.airbox.core.protocol.PROTOCOL_VERSION
import app.airbox.core.protocol.Platform
import app.airbox.core.security.FileTrustStore
import app.airbox.core.security.PairingLink
import app.airbox.core.security.PairingTokenIssuer
import app.airbox.core.transfer.DirectoryReceiveStorage
import app.airbox.core.transfer.OfferDecision
import app.airbox.core.transfer.PathOutgoingFile
import app.airbox.core.transfer.ProgressListener
import app.airbox.core.transfer.ReceivePrompt
import app.airbox.core.transfer.ReceiverSession
import app.airbox.core.transfer.SenderAuth
import app.airbox.core.transfer.SenderSession
import app.airbox.core.transfer.TransferState
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID
import kotlin.system.exitProcess

private val home: Path = Paths.get(System.getProperty("user.home"), ".airbox")
private val deviceName: String = System.getenv("AIRBOX_NAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("Airbox desktop")

private const val USAGE = """Airbox desktop reference client

  airbox receive [--dir DIR] [--port PORT]
      Wait for one incoming transfer. Prints a pairing link to scan/paste on the sender.

  airbox send --link 'airbox://pair?...' FILE...
      Send to the receiver that printed the link (its identity is pinned by the link).

  airbox send --to HOST:PORT FILE...
      Send by address. You'll confirm a 6-digit code shown on both devices.

  airbox trusted            List remembered devices.
  airbox forget FINGERPRINT Remove a remembered device."""

fun main(args: Array<String>) {
    val identity = DeviceIdentity.loadOrCreate(home.resolve("identity.p12"), CharArray(0))
    val trust = FileTrustStore(home.resolve("trusted.json"))
    when (args.firstOrNull()) {
        "receive" -> receive(identity, trust, args.drop(1))
        "send" -> send(identity, args.drop(1))
        "trusted" -> trust.all().forEach { println("${it.fingerprint}  ${it.name}") }
        "forget" -> trust.revoke(args.getOrNull(1) ?: usage())
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(USAGE)
    exitProcess(2)
}

private fun option(args: List<String>, name: String): String? =
    args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) ?: usage() }

private fun hello() = Hello(PROTOCOL_VERSION, UUID.randomUUID().toString(), deviceName, Platform.DESKTOP)

private fun ask(question: String): Boolean {
    print("$question [y/N] ")
    return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
}

private fun lanAddress(): String =
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: "127.0.0.1"

private fun progressPrinter(): ProgressListener {
    var last = 0L
    return ProgressListener { p ->
        val now = System.currentTimeMillis()
        if (now - last < 250 && p.transferredBytes != p.totalBytes) return@ProgressListener
        last = now
        val pct = if (p.totalBytes == 0L) 100 else (p.transferredBytes * 100 / p.totalBytes)
        val speed = "%.1f MB/s".format(p.bytesPerSecond / 1_000_000)
        val eta = p.etaSeconds?.let { " | ${it}s left" } ?: ""
        print("\r  $pct% | ${p.transferredBytes / 1024} / ${p.totalBytes / 1024} KiB | $speed$eta      ")
    }
}

private fun receive(identity: DeviceIdentity, trust: FileTrustStore, args: List<String>) {
    val dir = Paths.get(option(args, "--dir") ?: Paths.get(System.getProperty("user.home"), "Downloads", "Airbox").toString())
    Files.createDirectories(dir)
    val storage = DirectoryReceiveStorage(dir).also { it.purgeStale(Duration.ofDays(2)) }
    val tokens = PairingTokenIssuer(ttlMillis = 10 * 60_000)
    val done = java.util.concurrent.CountDownLatch(1)

    val prompt = object : ReceivePrompt {
        override fun confirmSasCode(peer: Hello, code: String): Boolean {
            println("\n\"${peer.deviceName}\" wants to pair. Code: ${code.chunked(3).joinToString(" ")}")
            return ask("Does the sender show the same code?")
        }

        override fun decideOffer(peer: Hello, offer: Offer, peerTrusted: Boolean): OfferDecision {
            println("\n\"${peer.deviceName}\" (${peer.platform.name.lowercase()}) wants to send ${offer.files.size} file(s), ${offer.totalBytes / 1024} KiB:")
            offer.files.take(20).forEach { println("  - ${it.name}  (${it.size / 1024} KiB)") }
            if (offer.files.size > 20) println("  ... and ${offer.files.size - 20} more")
            val accept = ask("Accept?")
            val remember = accept && !peerTrusted && ask("Remember this device and skip pairing next time?")
            return OfferDecision(accept, remember)
        }
    }

    val transport = TlsTransport(identity)
    val server = ReceiverServer(transport, option(args, "--port")?.toInt() ?: 0, handler = { channel: MessageChannel ->
        val outcome = ReceiverSession(channel, hello(), storage, trust, tokens, prompt, progress = progressPrinter()).run()
        println()
        when (outcome.state) {
            TransferState.COMPLETED -> {
                println("Received ${outcome.received.size} file(s) into $dir:")
                outcome.received.forEach { println("  + ${it.savedAs}") }
                done.countDown()
            }
            else -> println("Transfer ${outcome.state.name.lowercase()}: ${outcome.error?.userMessage ?: ""}")
        }
    }).start()

    val link = PairingLink(lanAddress(), server.port, identity.fingerprint, tokens.issue(), deviceName)
    println("Receiving as \"$deviceName\" on ${lanAddress()}:${server.port}")
    println("Fingerprint: ${identity.fingerprint.chunked(4).take(4).joinToString(" ")} ...")
    println("\nOn the sending device, open/scan this link (valid 10 minutes, single use):\n\n  ${link.toUri()}\n")
    println("Or send by address and compare the 6-digit code. Waiting... (Ctrl+C to stop)")
    done.await()
    server.close()
}

private fun send(identity: DeviceIdentity, args: List<String>) {
    val linkArg = option(args, "--link")
    val toArg = option(args, "--to")
    val files = args.filterIndexed { i, a -> !a.startsWith("--") && (i == 0 || !args[i - 1].startsWith("--")) }
        .map { Paths.get(it) }
    if (files.isEmpty() || (linkArg == null) == (toArg == null)) usage()
    files.firstOrNull { !Files.isRegularFile(it) }?.let { System.err.println("Not a readable file: $it"); exitProcess(1) }

    val transport = TlsTransport(identity)
    val pinned = linkArg != null
    val (stream, auth) = if (linkArg != null) {
        val link = PairingLink.parse(linkArg)
        transport.connectPinned(link.host, link.port, link.fingerprint) to SenderAuth.Token(link.token)
    } else {
        val host = toArg!!.substringBeforeLast(':')
        val port = toArg.substringAfterLast(':').toIntOrNull() ?: usage()
        transport.connect(host, port) to SenderAuth.Sas { code ->
            println("Pairing code: ${code.chunked(3).joinToString(" ")}")
            ask("Does the receiver show the same code?")
        }
    }
    println("Connected. Waiting for the receiver to accept...")
    val outcome = SenderSession(MessageChannel(stream), hello(), files.map { PathOutgoingFile(it) }, auth,
        receiverVerified = pinned, progress = progressPrinter()).run()
    println()
    if (outcome.state == TransferState.COMPLETED) {
        println("Sent ${files.size} file(s). All verified by the receiver.")
    } else {
        println("Transfer ${outcome.state.name.lowercase()}: ${outcome.error?.userMessage ?: ""}")
        exitProcess(1)
    }
}
