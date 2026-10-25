package app.airbox.core

import app.airbox.core.net.DeviceIdentity
import app.airbox.core.net.ReceiverServer
import app.airbox.core.net.TlsTransport
import app.airbox.core.protocol.FileEntry
import app.airbox.core.protocol.Hello
import app.airbox.core.protocol.MessageChannel
import app.airbox.core.protocol.Offer
import app.airbox.core.protocol.PROTOCOL_VERSION
import app.airbox.core.protocol.Platform
import app.airbox.core.security.InMemoryTrustStore
import app.airbox.core.security.PairingTokenIssuer
import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.DirectoryReceiveStorage
import app.airbox.core.transfer.ErrorCode
import app.airbox.core.transfer.OfferDecision
import app.airbox.core.transfer.OutgoingFile
import app.airbox.core.transfer.PathOutgoingFile
import app.airbox.core.transfer.ReceiveOutcome
import app.airbox.core.transfer.ReceivePolicy
import app.airbox.core.transfer.ReceivePrompt
import app.airbox.core.transfer.ReceiveStorage
import app.airbox.core.transfer.SendOutcome
import app.airbox.core.transfer.SenderAuth
import app.airbox.core.transfer.SenderSession
import app.airbox.core.transfer.TransferControl
import app.airbox.core.transfer.TransferState
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.walk
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
/** Real TLS over loopback: the same code path two phones on one Wi-Fi network use. */
class TransferEndToEndTest {
    private val tmp: Path = Files.createTempDirectory("airbox-test")
    private val outDir: Path = Files.createDirectories(tmp.resolve("received"))
    private val receiverIdentity = DeviceIdentity.generate()
    private val senderIdentity = DeviceIdentity.generate()
    private val receiverTls = TlsTransport(receiverIdentity)
    private val senderTls = TlsTransport(senderIdentity)
    private val trust = InMemoryTrustStore()
    private val tokens = PairingTokenIssuer()
    private val outcomes = LinkedBlockingQueue<ReceiveOutcome>()

    var storage: ReceiveStorage = DirectoryReceiveStorage(outDir)
    var prompt = TestPrompt()
    var receiverControl = TransferControl()

    private val server = ReceiverServer(receiverTls, 0, handler = { channel ->
        outcomes += runCatching { app.airbox.core.transfer.ReceiverSession(
            channel, hello("Receiver", Platform.IOS), storage, trust, tokens, prompt, ReceivePolicy(chunkBytes = 64 * 1024),
            receiverControl,
        ).run() }.onFailure { it.printStackTrace() }.getOrThrow()
    }, maxConnectionsPerMinutePerAddress = 100, bindAddress = "127.0.0.1").start()

    @AfterTest fun cleanup() {
        server.close()
        tmp.toFile().deleteRecursively()
    }

    class TestPrompt(
        var acceptSas: Boolean = true,
        var accept: Boolean = true,
        var remember: Boolean = false,
    ) : ReceivePrompt {
        val sasCodes = mutableListOf<String>()
        val offers = mutableListOf<Offer>()
        override fun confirmSasCode(peer: Hello, code: String): Boolean { sasCodes += code; return acceptSas }
        override fun decideOffer(peer: Hello, offer: Offer, peerTrusted: Boolean): OfferDecision {
            offers += offer
            return OfferDecision(accept, remember)
        }
    }

    private fun hello(name: String, platform: Platform) =
        Hello(PROTOCOL_VERSION, java.util.UUID.randomUUID().toString(), name, platform)

    private fun file(name: String, size: Int, seed: Int = size): Path =
        tmp.resolve("src").also { Files.createDirectories(it) }.resolve(name).also { Files.write(it, Random(seed).nextBytes(size)) }

    private fun send(
        files: List<OutgoingFile>,
        auth: SenderAuth = SenderAuth.Token(tokens.issue()),
        transferId: String = SenderSession.newTransferId(),
        control: TransferControl = TransferControl(),
        alreadyCompleted: Set<Int> = emptySet(),
        onStream: (MessageChannel) -> Unit = {},
        progress: app.airbox.core.transfer.ProgressListener? = null,
        pinned: Boolean = true,
    ): Pair<SendOutcome, ReceiveOutcome> {
        val stream = if (pinned) senderTls.connectPinned("127.0.0.1", server.port, receiverIdentity.fingerprint)
        else senderTls.connect("127.0.0.1", server.port)
        val channel = MessageChannel(stream).also(onStream)
        val sent = SenderSession(channel, hello("Sender", Platform.ANDROID), files, auth, pinned, transferId, control,
            progress = progress, alreadyCompleted = alreadyCompleted).run()
        val received = assertNotNull(outcomes.poll(10, TimeUnit.SECONDS), "receiver did not finish")
        return sent to received
    }

    private fun receivedNames() = outDir.listDirectoryEntries().filter { !it.name.startsWith(".") }.map { it.name }.toSet()

    @Test fun qrTokenPairingTransfersFileIntact() {
        val src = file("holiday.jpg", 1_500_000)
        val (sent, received) = send(listOf(PathOutgoingFile(src)))
        assertEquals(TransferState.COMPLETED, sent.state)
        assertEquals(TransferState.COMPLETED, received.state)
        assertContentEquals(src.readBytes(), outDir.resolve("holiday.jpg").readBytes())
        assertEquals("image/jpeg", prompt.offers.single().files.single().mimeType)
    }

    @Test fun pinnedConnectRefusesImpostor() {
        val e = assertFailsWith<AirboxException> {
            senderTls.connectPinned("127.0.0.1", server.port, "0".repeat(64))
        }
        assertEquals(ErrorCode.PEER_NOT_TRUSTED, e.code)
    }

    @Test fun reusedTokenIsRefused() {
        val token = tokens.issue()
        send(listOf(PathOutgoingFile(file("a.txt", 10))), SenderAuth.Token(token))
        val (sent, _) = send(listOf(PathOutgoingFile(file("b.txt", 10))), SenderAuth.Token(token))
        assertEquals(ErrorCode.PAIRING_TOKEN_INVALID, sent.error)
        assertEquals(setOf("a.txt"), receivedNames())
    }

    @Test fun sasPairingShowsSameCodeOnBothDevices() {
        var senderCode = ""
        val (sent, _) = send(listOf(PathOutgoingFile(file("doc.pdf", 4096))), SenderAuth.Sas { senderCode = it; true })
        assertEquals(TransferState.COMPLETED, sent.state)
        assertEquals(senderCode, prompt.sasCodes.single())
    }

    @Test fun sasDeclinedByReceiverSendsNothing() {
        prompt.acceptSas = false
        val (sent, received) = send(listOf(PathOutgoingFile(file("doc.pdf", 4096))), SenderAuth.Sas { true })
        assertEquals(ErrorCode.PAIRING_FAILED, sent.error)
        assertEquals(ErrorCode.PAIRING_FAILED, received.error)
        assertTrue(prompt.offers.isEmpty(), "offer must not be shown before pairing")
        assertTrue(receivedNames().isEmpty())
    }

    @Test fun untrustedSenderWithoutPairingIsRefused() {
        val (sent, _) = send(listOf(PathOutgoingFile(file("x.bin", 10))), SenderAuth.TrustedOnly)
        assertEquals(ErrorCode.PEER_NOT_TRUSTED, sent.error)
    }

    @Test fun rememberedSenderSkipsPairingNextTime() {
        prompt.remember = true
        send(listOf(PathOutgoingFile(file("1.txt", 10))))
        val (sent, _) = send(listOf(PathOutgoingFile(file("2.txt", 10))), SenderAuth.TrustedOnly)
        assertEquals(TransferState.COMPLETED, sent.state)
    }

    @Test fun senderStillVerifiesReceiverWhenConnectedByAddress() {
        // The receiver already trusts us, but we reached it by IP: a MITM could claim that trust.
        prompt.remember = true
        send(listOf(PathOutgoingFile(file("1.txt", 10))))
        var senderCode = ""
        val (sent, _) = send(listOf(PathOutgoingFile(file("2.txt", 10))), SenderAuth.Sas { senderCode = it; true }, pinned = false)
        assertEquals(TransferState.COMPLETED, sent.state)
        assertEquals(senderCode, prompt.sasCodes.single(), "SAS must run even though the receiver trusts the sender")

        val (refused, _) = send(listOf(PathOutgoingFile(file("3.txt", 10))), SenderAuth.TrustedOnly, pinned = false)
        assertEquals(ErrorCode.PEER_NOT_TRUSTED, refused.error)
    }

    @Test fun receiverRejectsOffer() {
        prompt.accept = false
        val (sent, received) = send(listOf(PathOutgoingFile(file("no.mov", 1000))))
        assertEquals(ErrorCode.REJECTED_BY_RECEIVER, sent.error)
        assertEquals(TransferState.CANCELLED, sent.state)
        assertEquals(TransferState.CANCELLED, received.state)
        assertTrue(receivedNames().isEmpty())
    }

    @Test fun multipleFilesAndDuplicatesNeverOverwrite() {
        Files.writeString(outDir.resolve("notes.txt"), "keep me")
        val files = listOf(file("notes.txt", 100, seed = 1), file("song.mp3", 300_000), file("empty.csv", 0))
        val (sent, received) = send(files.map { PathOutgoingFile(it) })
        assertEquals(TransferState.COMPLETED, sent.state)
        assertEquals(3, received.received.size)
        assertEquals("keep me", Files.readString(outDir.resolve("notes.txt")))
        assertContentEquals(files[0].readBytes(), outDir.resolve("notes (1).txt").readBytes())
        assertEquals(0, Files.size(outDir.resolve("empty.csv")))
    }

    @Test fun hostileFileNameIsSanitized() {
        val src = file("plain.bin", 50)
        val evil = object : OutgoingFile by PathOutgoingFile(src) { override val name = "../../.bashrc" }
        send(listOf(evil))
        assertEquals(setOf("bashrc"), receivedNames())
        assertTrue(tmp.walk().none { it.name == ".bashrc" })
    }

    @Test fun resumesAfterConnectionDrop() {
        val src = file("video.mp4", 3_000_000)
        val transferId = SenderSession.newTransferId()
        var channel: MessageChannel? = null
        // Simulate walking out of range: the link dies after ~1 MB has been read.
        val dropping = object : OutgoingFile by PathOutgoingFile(src) {
            override fun open(): InputStream = object : FilterInputStream(Files.newInputStream(src)) {
                var count = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (count > 1_000_000) channel!!.stream.close()
                    return super.read(b, off, len).also { if (it > 0) count += it }
                }
            }
        }
        val (first, firstRx) = send(listOf(dropping), transferId = transferId, onStream = { channel = it })
        assertEquals(ErrorCode.CONNECTION_LOST, first.error)
        assertTrue(first.resumable)
        assertEquals(TransferState.FAILED, firstRx.state)
        assertTrue(receivedNames().isEmpty(), "incomplete file must not be visible")
        val kept = Files.size(outDir.walk().single { it.name.endsWith(".part") })
        assertTrue(kept > 0, "partial data must be kept for resume")

        val firstProgress = mutableListOf<app.airbox.core.transfer.TransferProgress>()
        val (second, _) = send(listOf(PathOutgoingFile(src)), transferId = transferId,
            progress = { if (firstProgress.isEmpty()) firstProgress += it })
        assertEquals(TransferState.COMPLETED, second.state)
        assertContentEquals(src.readBytes(), outDir.resolve("video.mp4").readBytes())
        // The first chunk sent on reconnect starts at the kept offset, not at byte 0.
        assertTrue(firstProgress.single().currentFileBytes > kept)
        assertTrue(firstProgress.single().currentFileBytes <= kept + 64 * 1024)
        assertTrue(outDir.walk().none { it.name.endsWith(".part") })
    }

    @Test fun corruptedPartialIsDetectedAndResentFromScratch() {
        val src = file("archive.zip", 2_000_000)
        val transferId = SenderSession.newTransferId()
        val control = TransferControl()
        val pausing = object : OutgoingFile by PathOutgoingFile(src) {
            override fun open(): InputStream = object : FilterInputStream(Files.newInputStream(src)) {
                var count = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (count > 500_000) control.pause()
                    return super.read(b, off, len).also { if (it > 0) count += it }
                }
            }
        }
        val (paused, pausedRx) = send(listOf(pausing), transferId = transferId, control = control)
        assertEquals(TransferState.PAUSED, paused.state)
        assertEquals(TransferState.PAUSED, pausedRx.state)

        // Flip a byte in the kept partial (disk fault / tampering).
        val part = outDir.walk().single { it.name.endsWith(".part") }
        val bytes = part.readBytes().also { it[1234] = (it[1234] + 1).toByte() }
        Files.write(part, bytes)

        val (resumed, _) = send(listOf(PathOutgoingFile(src)), transferId = transferId)
        assertEquals(TransferState.COMPLETED, resumed.state)
        assertContentEquals(src.readBytes(), outDir.resolve("archive.zip").readBytes())
    }

    @Test fun insufficientStorageIsRejectedUpFront() {
        val real = DirectoryReceiveStorage(outDir)
        storage = object : ReceiveStorage by real { override fun freeBytes() = 300L * 1024 * 1024 }
        val big = object : OutgoingFile {
            override val name = "huge.mkv"; override val size = 100L * 1024 * 1024; override val mimeType = "video/x-matroska"
            override val lastModifiedEpochMs: Long? = null
            override fun open(): InputStream = error("must not be read")
        }
        val (sent, _) = send(listOf(big))
        assertEquals(ErrorCode.INSUFFICIENT_STORAGE, sent.error)
        assertTrue(prompt.offers.isEmpty())
    }

    @Test fun senderCancelCleansUpPartials() {
        val src = file("big.bin", 2_000_000)
        val control = TransferControl()
        val cancelling = object : OutgoingFile by PathOutgoingFile(src) {
            override fun open(): InputStream = object : FilterInputStream(Files.newInputStream(src)) {
                var count = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (count > 400_000) control.cancel()
                    return super.read(b, off, len).also { if (it > 0) count += it }
                }
            }
        }
        val (sent, received) = send(listOf(cancelling), control = control)
        assertEquals(TransferState.CANCELLED, sent.state)
        assertEquals(ErrorCode.CANCELLED_BY_PEER, received.error)
        assertTrue(outDir.walk().none { it.name.endsWith(".part") }, "partials must be deleted on cancel")
    }

    @Test fun fileShrinkingDuringSendFailsSafely() {
        val src = file("live.log", 200_000)
        val liar = object : OutgoingFile by PathOutgoingFile(src) { override val size = 300_000L }
        val (sent, _) = send(listOf(liar))
        assertEquals(ErrorCode.SOURCE_FILE_CHANGED, sent.error)
        assertTrue(receivedNames().isEmpty())
    }

    @Test fun unknownOfferEntriesAreValidated() {
        // A hostile sender announcing out-of-order indexes is rejected before the user is asked.
        val stream = senderTls.connectPinned("127.0.0.1", server.port, receiverIdentity.fingerprint)
        val channel = MessageChannel(stream)
        channel.send(hello("Evil", Platform.ANDROID))
        channel.expect<Hello>()
        channel.expect<app.airbox.core.protocol.AuthStatus>()
        channel.send(app.airbox.core.protocol.PairToken(tokens.issue()))
        channel.expect<app.airbox.core.protocol.PairResult>()
        channel.send(Offer("abcdefgh123", listOf(FileEntry(5, "x", 1))))
        val e = assertFailsWith<AirboxException> { channel.expect<app.airbox.core.protocol.Accept>() }
        assertEquals(ErrorCode.OFFER_INVALID, e.code)
        assertEquals(ErrorCode.OFFER_INVALID, outcomes.poll(5, TimeUnit.SECONDS)!!.error)
    }
}
