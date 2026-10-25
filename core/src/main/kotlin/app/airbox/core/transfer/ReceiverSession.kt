package app.airbox.core.transfer

import app.airbox.core.protocol.Accept
import app.airbox.core.protocol.AuthStatus
import app.airbox.core.protocol.Cancel
import app.airbox.core.protocol.Complete
import app.airbox.core.protocol.FileEnd
import app.airbox.core.protocol.FileEntry
import app.airbox.core.protocol.FileResult
import app.airbox.core.protocol.FileStart
import app.airbox.core.protocol.FrameLimits
import app.airbox.core.protocol.FrameType
import app.airbox.core.protocol.Hello
import app.airbox.core.protocol.Message
import app.airbox.core.protocol.MessageChannel
import app.airbox.core.protocol.MessageCodec
import app.airbox.core.protocol.Offer
import app.airbox.core.protocol.PROTOCOL_VERSION
import app.airbox.core.protocol.PairResult
import app.airbox.core.protocol.PairToken
import app.airbox.core.protocol.Pause
import app.airbox.core.protocol.Reject
import app.airbox.core.protocol.SasCommit
import app.airbox.core.protocol.SasNonce
import app.airbox.core.protocol.SasReveal
import app.airbox.core.security.PairingTokenIssuer
import app.airbox.core.security.SasPairing
import app.airbox.core.security.TrustStore
import app.airbox.core.security.TrustedDevice
import app.airbox.core.security.fromBase64Url
import app.airbox.core.security.toBase64Url
import app.airbox.core.security.toHex
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest

/** What the person on the receiving device decides. Implemented by the UI layer. */
interface ReceivePrompt {
    /** Show the 6-digit code; return true only if the user confirms it matches the sender's screen. */
    fun confirmSasCode(peer: Hello, code: String): Boolean

    /** Show sender, file names, count and size; return the decision. */
    fun decideOffer(peer: Hello, offer: Offer, peerTrusted: Boolean): OfferDecision
}

data class OfferDecision(val accept: Boolean, val rememberSender: Boolean = false)

data class ReceivePolicy(
    val maxFiles: Int = 10_000,
    val maxTotalBytes: Long = 512L * 1024 * 1024 * 1024,
    /** Free space kept untouched after the transfer, so we never fill the device to 0. */
    val storageReserveBytes: Long = 256L * 1024 * 1024,
    val chunkBytes: Int = FrameLimits.DEFAULT_CHUNK_BYTES,
    /** Unknown senders must pair (token or SAS) before an offer is even shown. */
    val allowUnpairedSenders: Boolean = true,
    val decisionTimeoutMs: Int = 120_000,
)

data class ReceivedFile(val entry: FileEntry, val savedAs: String)

data class ReceiveOutcome(
    val transferId: String?,
    val state: TransferState,
    val received: List<ReceivedFile>,
    val error: ErrorCode? = null,
)

/**
 * Receiver half of one connection. Blocking; run it on a worker thread / foreground service.
 */
class ReceiverSession(
    private val channel: MessageChannel,
    private val localHello: Hello,
    private val storage: ReceiveStorage,
    private val trustStore: TrustStore,
    private val tokens: PairingTokenIssuer,
    private val prompt: ReceivePrompt,
    private val policy: ReceivePolicy = ReceivePolicy(),
    private val control: TransferControl = TransferControl(),
    private val progress: ProgressListener? = null,
) {
    val stateMachine = TransferStateMachine(TransferState.CONNECTING)
    private val peerFp = channel.stream.peerFingerprint
    private val received = mutableListOf<ReceivedFile>()
    private var offer: Offer? = null
    private var paused = false

    fun run(): ReceiveOutcome {
        try {
            val peer = channel.expect<Hello>()
            if (peer.protocolVersion != PROTOCOL_VERSION) {
                channel.send(Cancel(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED))
                throw AirboxException(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED)
            }
            channel.send(localHello)

            val trusted = trustStore.isTrusted(peerFp)
            channel.send(AuthStatus(trusted))
            // Even when we trust the sender, the sender may not yet have verified us
            // (e.g. it connected by address), so it may still start pairing.
            var next = channel.receive()
            if (next !is Offer) {
                stateMachine.moveTo(TransferState.PAIRING)
                authenticate(peer, next)
                next = channel.receive()
            } else if (!trusted) {
                channel.send(Cancel(ErrorCode.PEER_NOT_TRUSTED))
                throw AirboxException(ErrorCode.PEER_NOT_TRUSTED)
            }

            stateMachine.moveTo(TransferState.AWAITING_ACCEPTANCE)
            val offer = (next as? Offer ?: throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "expected offer"))
                .also { this.offer = it }
            validate(offer)
            val resume = resumeOffsets(offer)
            val needed = offer.totalBytes - resume.values.sum()
            if (storage.freeBytes() - policy.storageReserveBytes < needed) {
                reject(offer, ErrorCode.INSUFFICIENT_STORAGE)
            }
            channel.stream.setReadTimeout(0)
            val decision = prompt.decideOffer(peer, offer, trusted)
            channel.stream.setReadTimeout(30_000)
            if (!decision.accept) {
                storage.discard(offer.transferId)
                reject(offer, ErrorCode.REJECTED_BY_RECEIVER)
            }
            if (decision.rememberSender) {
                trustStore.trust(TrustedDevice(peerFp, peer.deviceName, System.currentTimeMillis()))
            }
            val chunk = minOf(policy.chunkBytes, peer.maxChunkBytes, localHello.maxChunkBytes)
            channel.send(Accept(offer.transferId, resume, chunk))
            stateMachine.moveTo(TransferState.TRANSFERRING)
            receiveFiles(offer, resume)
            return ReceiveOutcome(offer.transferId, TransferState.COMPLETED, received.toList())
        } catch (e: AirboxException) {
            return finish(e.code)
        } catch (e: EOFException) {
            return finish(ErrorCode.CONNECTION_LOST)
        } catch (e: IOException) {
            return finish(ErrorCode.CONNECTION_LOST)
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun finish(code: ErrorCode): ReceiveOutcome {
        val id = offer?.transferId
        val state = when (code) {
            ErrorCode.CANCELLED_BY_PEER, ErrorCode.CANCELLED_LOCALLY, ErrorCode.REJECTED_BY_RECEIVER -> TransferState.CANCELLED
            ErrorCode.CONNECTION_LOST -> if (paused) TransferState.PAUSED else TransferState.FAILED
            else -> TransferState.FAILED
        }
        // Keep partial data only when the transfer can still resume.
        if (id != null && state != TransferState.PAUSED && code != ErrorCode.CONNECTION_LOST) storage.discard(id)
        if (!stateMachine.state.isTerminal) runCatching { stateMachine.moveTo(state) }
        return ReceiveOutcome(id, state, received.toList(), code)
    }

    private fun authenticate(peer: Hello, first: Message) {
        if (!policy.allowUnpairedSenders && !trustStore.isTrusted(peerFp)) {
            channel.send(Cancel(ErrorCode.PEER_NOT_TRUSTED))
            throw AirboxException(ErrorCode.PEER_NOT_TRUSTED)
        }
        when (first) {
            is PairToken -> {
                val ok = tokens.redeem(first.token)
                channel.send(PairResult(ok))
                if (!ok) throw AirboxException(ErrorCode.PAIRING_TOKEN_INVALID)
            }
            is SasCommit -> {
                val myNonce = SasPairing.newNonce()
                channel.send(SasNonce(myNonce.toBase64Url()))
                val reveal = channel.expect<SasReveal>()
                val theirNonce = decodeNonce(reveal.nonce)
                SasPairing.verifyCommitment(first.commitment, theirNonce)
                val code = SasPairing.code(peerFp, channel.stream.localFingerprint, theirNonce, myNonce)
                channel.stream.setReadTimeout(0)
                val confirmed = prompt.confirmSasCode(peer, code)
                channel.stream.setReadTimeout(30_000)
                channel.send(PairResult(confirmed))
                val senderConfirmed = channel.expect<PairResult>().confirmed
                if (!confirmed || !senderConfirmed) throw AirboxException(ErrorCode.PAIRING_FAILED)
            }
            else -> throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "expected pairing")
        }
    }

    private fun decodeNonce(value: String): ByteArray =
        runCatching { value.fromBase64Url() }.getOrNull()?.takeIf { it.size == SasPairing.NONCE_BYTES }
            ?: throw AirboxException(ErrorCode.PAIRING_FAILED, "bad nonce")

    private fun validate(offer: Offer) {
        val files = offer.files
        val ok = offer.transferId.matches(DirectoryReceiveStorage.TRANSFER_ID) &&
            files.isNotEmpty() && files.size <= policy.maxFiles &&
            files.map { it.index } == files.indices.toList() &&
            files.all { it.size >= 0 && it.name.isNotBlank() && it.name.length <= 1024 } &&
            offer.totalBytes in 0..policy.maxTotalBytes
        if (!ok) reject(offer, ErrorCode.OFFER_INVALID)
    }

    private fun reject(offer: Offer, code: ErrorCode): Nothing {
        channel.send(Reject(offer.transferId, code))
        throw AirboxException(code)
    }

    private fun resumeOffsets(offer: Offer): Map<Int, Long> =
        offer.files.mapNotNull { entry ->
            val have = storage.partial(offer.transferId, entry).length
            when {
                have == 0L -> null
                have > entry.size -> { storage.partial(offer.transferId, entry).delete(); null }
                else -> entry.index to have
            }
        }.toMap()

    private fun receiveFiles(offer: Offer, resume: Map<Int, Long>) {
        var doneBytes = resume.values.sum()
        val meter = ThroughputMeter()
        while (true) {
            if (control.cancelRequested) {
                channel.send(Cancel(ErrorCode.CANCELLED_LOCALLY))
                throw AirboxException(ErrorCode.CANCELLED_LOCALLY)
            }
            when (val message = channel.receive()) {
                is Complete -> {
                    if (received.size != offer.files.size) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "complete before all files")
                    stateMachine.moveTo(TransferState.COMPLETED)
                    return
                }
                is Pause -> {
                    paused = true
                    throw AirboxException(ErrorCode.CONNECTION_LOST, "paused by sender")
                }
                is FileStart -> {
                    val entry = offer.files.getOrNull(message.index)
                        ?: throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "bad file index")
                    if (received.any { it.entry.index == entry.index }) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "duplicate file")
                    val partial = storage.partial(offer.transferId, entry)
                    if (message.offset != 0L && message.offset != partial.length) {
                        throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "resume offset mismatch")
                    }
                    if (message.offset == 0L) partial.delete()
                    stateMachine.moveTo(TransferState.TRANSFERRING)
                    doneBytes += receiveOne(offer, entry, partial, message.offset, doneBytes, meter)
                }
                else -> throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "unexpected ${message::class.simpleName}")
            }
        }
    }

    /** Returns the number of new bytes counted towards progress. */
    private fun receiveOne(
        offer: Offer,
        entry: FileEntry,
        partial: PartialFile,
        offset: Long,
        doneBefore: Long,
        meter: ThroughputMeter,
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) partial.openRead().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        var written = offset
        var announcedHash = ""
        partial.openAppend().use { out ->
            while (true) {
                val frame = channel.readFrame()
                if (frame.type == FrameType.DATA) {
                    if (written + frame.length > entry.size) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "more data than announced")
                    out.write(frame.payload, 0, frame.length)
                    digest.update(frame.payload, 0, frame.length)
                    written += frame.length
                    meter.record(frame.length.toLong())
                    progress?.onProgress(
                        TransferProgress(offer.transferId, TransferState.TRANSFERRING, entry.index, written, entry.size,
                            doneBefore + written - offset, offer.totalBytes, meter.bytesPerSecond),
                    )
                    if (control.cancelRequested) {
                        channel.send(Cancel(ErrorCode.CANCELLED_LOCALLY))
                        throw AirboxException(ErrorCode.CANCELLED_LOCALLY)
                    }
                    continue
                }
                when (val message = MessageCodec.decode(frame.payload)) {
                    is FileEnd -> {
                        if (message.index != entry.index) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "file_end index")
                        announcedHash = message.sha256
                        out.flush()
                        break
                    }
                    is Cancel -> throw AirboxException(ErrorCode.CANCELLED_BY_PEER)
                    is Pause -> { paused = true; throw AirboxException(ErrorCode.CONNECTION_LOST, "paused by sender") }
                    else -> throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "unexpected message mid-file")
                }
            }
        }
        // A FileEnd was read: verify before anything is shown as received.
        stateMachine.moveTo(TransferState.VERIFYING)
        val actual = digest.digest().toHex()
        if (written != entry.size || !MessageDigest.isEqual(actual.toByteArray(), announcedHash.toByteArray())) {
            // Discard: the sender may retry this file from byte 0.
            partial.delete()
            channel.send(FileResult(entry.index, ok = false, code = ErrorCode.INTEGRITY_CHECK_FAILED))
            return 0
        }
        val savedAs = storage.publish(partial, FileNames.sanitize(entry.name), entry)
        received += ReceivedFile(entry, savedAs)
        channel.send(FileResult(entry.index, ok = true))
        return entry.size - offset
    }
}
