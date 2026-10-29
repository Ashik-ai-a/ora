package app.airbox.core.markets

import kotlinx.serialization.Serializable

/** Proof results kept on the phone between runs (each run downloads 25-35 MB). */
@Serializable
data class SavedIntraday(val targetR: Double, val stocks: Int, val every: ProofSummary?, val taken: ProofSummary?)

@Serializable
data class SavedYear(val year: Int, val ret: Double)

@Serializable
data class SavedGrid(val topN: Int, val regime: Boolean, val metrics: Momentum.Metrics?)

@Serializable
data class SavedMomentum(val stocks: Int, val metrics: Momentum.Metrics?, val universe: Momentum.Metrics?, val yearly: List<SavedYear>,
                         val grid: List<SavedGrid>, val turnover: Double)

@Serializable
data class SavedProofs(val intraday: List<SavedIntraday>? = null, val intradayAt: Long? = null,
                       val momentum: SavedMomentum? = null, val momentumAt: Long? = null)

