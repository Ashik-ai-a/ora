package app.airbox.core.transfer

enum class TransferState {
    IDLE,
    DISCOVERING,
    PAIRING,
    AWAITING_ACCEPTANCE,
    CONNECTING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    PAUSED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/**
 * Enforces legal transitions. In particular COMPLETED is only reachable from VERIFYING,
 * so the UI can never show "Completed" before integrity checks pass.
 */
class TransferStateMachine(initial: TransferState = TransferState.IDLE) {
    var state: TransferState = initial
        private set

    private val listeners = mutableListOf<(TransferState, TransferState) -> Unit>()

    fun onChange(listener: (from: TransferState, to: TransferState) -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun moveTo(next: TransferState) {
        if (next == state) return
        check(next in allowed.getValue(state)) { "illegal transition $state -> $next" }
        val previous = state
        state = next
        listeners.forEach { it(previous, next) }
    }

    companion object {
        private val abort = setOf(TransferState.FAILED, TransferState.CANCELLED)

        val allowed: Map<TransferState, Set<TransferState>> = mapOf(
            TransferState.IDLE to setOf(TransferState.DISCOVERING, TransferState.CONNECTING) + abort,
            TransferState.DISCOVERING to setOf(TransferState.CONNECTING, TransferState.IDLE) + abort,
            TransferState.CONNECTING to setOf(TransferState.PAIRING, TransferState.AWAITING_ACCEPTANCE, TransferState.PAUSED) + abort,
            TransferState.PAIRING to setOf(TransferState.AWAITING_ACCEPTANCE) + abort,
            TransferState.AWAITING_ACCEPTANCE to setOf(TransferState.TRANSFERRING) + abort,
            TransferState.TRANSFERRING to setOf(TransferState.VERIFYING, TransferState.PAUSED) + abort,
            // VERIFYING -> TRANSFERRING: next file in a multi-file transfer.
            TransferState.VERIFYING to setOf(TransferState.TRANSFERRING, TransferState.COMPLETED, TransferState.PAUSED) + abort,
            TransferState.PAUSED to setOf(TransferState.CONNECTING) + abort,
            // A failed transfer may be retried (resume) if partial data was kept.
            TransferState.FAILED to setOf(TransferState.CONNECTING),
            TransferState.COMPLETED to emptySet(),
            TransferState.CANCELLED to emptySet(),
        )
    }
}
