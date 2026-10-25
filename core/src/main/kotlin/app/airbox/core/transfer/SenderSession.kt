package app.airbox.core.transfer

import app.airbox.core.protocol.Accept
import app.airbox.core.protocol.AuthStatus
import app.airbox.core.protocol.Cancel
import app.airbox.core.protocol.Complete
import app.airbox.core.protocol.FileEnd
import app.airbox.core.protocol.FileEntry
import app.airbox.core.protocol.FileResult
import app.airbox.core.protocol.FileStart
import app.airbox.core.protocol.Hello
import app.airbox.core.protocol.MessageChannel
import app.airbox.core.protocol.Offer
import app.airbox.core.protocol.PROTOCOL_VERSION
import app.airbox.core.protocol.PairResult
import app.airbox.core.protocol.PairToken
import app.airbox.core.protocol.Pause
import app.airbox.core.protocol.SasCommit
import app.airbox.core.protocol.SasNonce
import app.airbox.core.protocol.SasReveal
import app.airbox.core.security.SasPairing
import app.airbox.core.security.fromBase64Url
import app.airbox.core.security.randomBytes
import app.airbox.core.security.toBase64Url
import app.airbox.core.security.toHex
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** How the sender proves itself to a receiver that doesn't trust it yet. */
sealed interface SenderAuth {
    /** Token from the receiver's QR code / deep link (the link also pinned its fingerprint). */
    data class Token(val token: String) : SenderAuth

    /** Compare a 6-digit code on both screens; [confirm] asks the sending user. */
    data class Sas(val confirm: (code: String) -> Boolean) : SenderAuth

    /** Only succeeds if the receiver already trusts us. */
    data object TrustedOnly : SenderAuth
}

data class SendOutcome(
    val transferId: String,
    val state: TransferState,
    val completedIndexes: Set<Int>,
    val error: ErrorCode? = null,
) {
    /** True when a reconnect with the same transferId can continue from where it stopped. */
    val resumable: Boolean get() = state == TransferState.PAUSED || error == ErrorCode.CONNECTION_LOST
}

/**
 * Sender half of one connection. A transfer interrupted by a lost connection or a pause can
 * be continued by running a new [SenderSession] with the same [transferId]; the receiver
 * reports how many bytes of each file it already holds.
 */
class SenderSession(
    private val channel: MessageChannel,
    private val localHello: Hello,
    private val files: List<OutgoingFile>,
    private val auth: SenderAuth,
    /** True when the connection was opened with [app.airbox.core.net.TlsTransport.connectPinned]. */
    private val receiverVerified: Boolean,
    val transferId: String = newTransferId(),
    private val control: TransferControl = TransferControl(),
    private val progress: ProgressListener? = null,
    /** Files that already succeeded in an earlier attempt of this transfer. */
    alreadyCompleted: Set<Int> = emptySet(),
    private val maxIntegrityRetries: Int = 1,
) {
    val stateMachine = TransferStateMachine(TransferState.CONNECTING)
    private val completed = alreadyCompleted.toMutableSet()

    // Captured once so a file that changes mid-transfer is detected instead of silently mixed.
    private val entries = files.mapIndexed { i, f -> FileEntry(i, f.name, f.size, f.mimeType, f.lastModifiedEpochMs) }

    fun run(): SendOutcome {
        try {
            channel.send(localHello)
            val peer = channel.expect<Hello>()
            if (peer.protocolVersion != PROTOCOL_VERSION) throw AirboxException(ErrorCode.PROTOCOL_VERSION_UNSUPPORTED)

            // Pair unless BOTH directions are already verified: the receiver trusts us, and we
            // know the receiver's certificate (pinned link or our own trust store).
            val receiverTrustsUs = channel.expect<AuthStatus>().trusted
            if (!receiverTrustsUs || !receiverVerified) {
                stateMachine.moveTo(TransferState.PAIRING)
                authenticate()
            }

            stateMachine.moveTo(TransferState.AWAITING_ACCEPTANCE)
            val offer = Offer(transferId, entries)
            channel.send(offer)
            channel.stream.setReadTimeout(0) // the receiver is deciding
            val accept = channel.expect<Accept>()
            channel.stream.setReadTimeout(30_000)
            if (accept.transferId != transferId || accept.chunkBytes <= 0) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION)

            stateMachine.moveTo(TransferState.TRANSFERRING)
            sendAll(offer, accept)
            channel.send(Complete)
            stateMachine.moveTo(TransferState.COMPLETED)
            return SendOutcome(transferId, TransferState.COMPLETED, completed.toSet())
        } catch (e: PausedSignal) {
            stateMachine.moveTo(TransferState.PAUSED)
            return SendOutcome(transferId, TransferState.PAUSED, completed.toSet())
        } catch (e: AirboxException) {
            if (e.code == ErrorCode.CANCELLED_LOCALLY || e.code == ErrorCode.SOURCE_FILE_CHANGED) {
                runCatching { channel.send(Cancel(e.code)) }
            }
            return fail(e.code)
        } catch (e: EOFException) {
            return fail(ErrorCode.CONNECTION_LOST)
        } catch (e: IOException) {
            return fail(ErrorCode.CONNECTION_LOST)
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun fail(code: ErrorCode): SendOutcome {
        val state = when (code) {
            ErrorCode.CANCELLED_LOCALLY, ErrorCode.CANCELLED_BY_PEER, ErrorCode.REJECTED_BY_RECEIVER -> TransferState.CANCELLED
            else -> TransferState.FAILED
        }
        if (!stateMachine.state.isTerminal) stateMachine.moveTo(state)
        return SendOutcome(transferId, state, completed.toSet(), code)
    }

    private fun authenticate() {
        when (auth) {
            is SenderAuth.Token -> {
                channel.send(PairToken(auth.token))
                if (!channel.expect<PairResult>().confirmed) throw AirboxException(ErrorCode.PAIRING_TOKEN_INVALID)
            }
            is SenderAuth.Sas -> {
                val myNonce = SasPairing.newNonce()
                channel.send(SasCommit(SasPairing.commitment(myNonce)))
                val theirNonce = channel.expect<SasNonce>().nonce.let {
                    runCatching { it.fromBase64Url() }.getOrNull()?.takeIf { n -> n.size == SasPairing.NONCE_BYTES }
                        ?: throw AirboxException(ErrorCode.PAIRING_FAILED, "bad nonce")
                }
                channel.send(SasReveal(myNonce.toBase64Url()))
                val code = SasPairing.code(channel.stream.localFingerprint, channel.stream.peerFingerprint, myNonce, theirNonce)
                val mine = auth.confirm(code)
                channel.stream.setReadTimeout(0)
                val theirs = channel.expect<PairResult>().confirmed
                channel.stream.setReadTimeout(30_000)
                channel.send(PairResult(mine))
                if (!mine || !theirs) throw AirboxException(ErrorCode.PAIRING_FAILED)
            }
            SenderAuth.TrustedOnly -> throw AirboxException(ErrorCode.PEER_NOT_TRUSTED)
        }
    }

    private fun sendAll(offer: Offer, accept: Accept) {
        val buffer = ByteArray(accept.chunkBytes)
        val meter = ThroughputMeter()
        var done = completed.sumOf { entries[it].size } + accept.resumeOffsets.filterKeys { it !in completed }.values.sum()
        for (entry in entries) {
            if (entry.index in completed) continue
            var offset = accept.resumeOffsets[entry.index] ?: 0L
            if (offset !in 0..entry.size) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "bad resume offset")
            var attempts = 0
            while (true) {
                val result = sendOne(offer, entry, offset, buffer, meter, done)
                if (result.ok) {
                    completed += entry.index
                    done += entry.size - offset
                    break
                }
                if (attempts++ >= maxIntegrityRetries) throw AirboxException(ErrorCode.INTEGRITY_CHECK_FAILED, "file ${entry.index}")
                done -= offset // receiver discarded everything it had for this file
                offset = 0
                stateMachine.moveTo(TransferState.TRANSFERRING)
            }
        }
    }

    private fun sendOne(offer: Offer, entry: FileEntry, offset: Long, buffer: ByteArray, meter: ThroughputMeter, doneBefore: Long): FileResult {
        val digest = MessageDigest.getInstance("SHA-256")
        stateMachine.moveTo(TransferState.TRANSFERRING)
        files[entry.index].open().use { input ->
            // The final hash covers the whole file, so re-hash the prefix the receiver already has.
            if (offset > 0) hashPrefix(input, offset, digest, buffer)
            channel.send(FileStart(entry.index, offset))
            var sent = offset
            while (true) {
                if (control.cancelRequested) throw AirboxException(ErrorCode.CANCELLED_LOCALLY)
                if (control.pauseRequested) {
                    channel.flush()
                    channel.send(Pause)
                    throw PausedSignal()
                }
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (sent + n > entry.size) throw AirboxException(ErrorCode.SOURCE_FILE_CHANGED, "file grew")
                digest.update(buffer, 0, n)
                channel.sendData(buffer, n)
                sent += n
                meter.record(n.toLong())
                progress?.onProgress(
                    TransferProgress(transferId, TransferState.TRANSFERRING, entry.index, sent, entry.size,
                        doneBefore + sent - offset, offer.totalBytes, meter.bytesPerSecond),
                )
            }
            if (sent != entry.size) throw AirboxException(ErrorCode.SOURCE_FILE_CHANGED, "file shrank")
        }
        channel.send(FileEnd(entry.index, digest.digest().toHex()))
        stateMachine.moveTo(TransferState.VERIFYING)
        val result = channel.expect<FileResult>()
        if (result.index != entry.index) throw AirboxException(ErrorCode.PROTOCOL_VIOLATION, "file_result index")
        return result
    }

    private fun hashPrefix(input: InputStream, length: Long, digest: MessageDigest, buffer: ByteArray) {
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw AirboxException(ErrorCode.SOURCE_FILE_CHANGED, "file shrank before resume point")
            digest.update(buffer, 0, n)
            remaining -= n
        }
    }

    private class PausedSignal : RuntimeException()

    companion object {
        fun newTransferId(): String = randomBytes(12).toBase64Url()
    }
}
