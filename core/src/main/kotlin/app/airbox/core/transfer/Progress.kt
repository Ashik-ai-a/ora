package app.airbox.core.transfer

/** Snapshot for the UI. Throughput is measured, never estimated from link speed. */
data class TransferProgress(
    val transferId: String,
    val state: TransferState,
    val currentFileIndex: Int,
    val currentFileBytes: Long,
    val currentFileSize: Long,
    val transferredBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Double,
) {
    /** Null until we have a meaningful measurement. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond < 1.0 || transferredBytes == 0L) null
        else ((totalBytes - transferredBytes) / bytesPerSecond).toLong()
}

fun interface ProgressListener {
    fun onProgress(progress: TransferProgress)
}

/** Exponentially-smoothed throughput meter. */
internal class ThroughputMeter(private val clock: () -> Long = System::nanoTime) {
    private var lastTime = clock()
    private var pending = 0L
    var bytesPerSecond = 0.0
        private set

    fun record(bytes: Long) {
        pending += bytes
        val now = clock()
        val elapsed = (now - lastTime) / 1e9
        if (elapsed >= 0.25) {
            val sample = pending / elapsed
            bytesPerSecond = if (bytesPerSecond == 0.0) sample else 0.7 * bytesPerSecond + 0.3 * sample
            pending = 0
            lastTime = now
        }
    }
}

/** Cooperative cancel / pause, checked between chunks. */
class TransferControl {
    @Volatile var cancelRequested = false
        private set
    @Volatile var pauseRequested = false
        private set

    fun cancel() { cancelRequested = true }
    fun pause() { pauseRequested = true }
    fun clearPause() { pauseRequested = false }
}
