package app.airbox.core.security

import app.airbox.core.transfer.AirboxException
import app.airbox.core.transfer.ErrorCode

/**
 * Short Authentication String pairing for two devices that have never met and have no
 * QR code to scan (e.g. manual address entry).
 *
 * Commit-then-reveal (Vaudenay-style SAS) run inside the TLS session:
 *   initiator -> responder : commit = SHA256("airbox-sas-commit-v1" || nI)
 *   responder -> initiator : nR
 *   initiator -> responder : nI           (responder checks the commitment)
 *   both display            : 6 digits from SHA256(label || fpI || fpR || nI || nR)
 *
 * fpI/fpR are the TLS certificate fingerprints each side observed, so a man in the middle
 * (two separate TLS sessions with different certificates) yields different codes on the two
 * screens. Because the initiator commits before seeing nR, an attacker gets a single
 * 1-in-10^6 guess per attempt rather than an offline search. Users must compare the codes.
 */
object SasPairing {
    private val COMMIT_LABEL = "airbox-sas-commit-v1".toByteArray()
    private val CODE_LABEL = "airbox-sas-code-v1".toByteArray()
    const val NONCE_BYTES = 32

    fun newNonce(): ByteArray = randomBytes(NONCE_BYTES)

    fun commitment(initiatorNonce: ByteArray): String = sha256(COMMIT_LABEL, initiatorNonce).toBase64Url()

    fun verifyCommitment(commitment: String, revealedNonce: ByteArray) {
        if (revealedNonce.size != NONCE_BYTES || !constantTimeEquals(commitment, commitment(revealedNonce))) {
            throw AirboxException(ErrorCode.PAIRING_FAILED, "SAS commitment mismatch")
        }
    }

    /** Returns a 6-digit code, zero padded. */
    fun code(
        initiatorFingerprint: String,
        responderFingerprint: String,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
    ): String {
        require(initiatorNonce.size == NONCE_BYTES && responderNonce.size == NONCE_BYTES)
        val h = sha256(
            CODE_LABEL,
            initiatorFingerprint.toByteArray(),
            responderFingerprint.toByteArray(),
            initiatorNonce,
            responderNonce,
        )
        val value = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return "%06d".format(value % 1_000_000)
    }
}
