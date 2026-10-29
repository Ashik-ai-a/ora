package app.airbox.core.markets

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * What one intraday (MIS) round trip on NSE equity really costs. Defaults are a discount
 * broker's published 2025 rates (brokerage Rs 20 or 0.03% per executed order, whichever is
 * lower). Slippage is not a fee but it is real money: small caps rarely fill at the signal.
 */
data class Rates(
    val brokeragePct: Double = 0.0003,
    val brokerageCap: Double = 20.0,
    val sttSellPct: Double = 0.00025,
    val exchangePct: Double = 0.0000297,
    val sebiPct: Double = 0.000001,
    val stampBuyPct: Double = 0.00003,
    val gstPct: Double = 0.18,
    val slippagePct: Double = 0.0005,
)

data class Charges(val brokerage: Double, val stt: Double, val exchange: Double, val sebi: Double, val stamp: Double,
                   val gst: Double, val slippage: Double) {
    val fees: Double get() = brokerage + stt + exchange + sebi + stamp + gst
    val total: Double get() = fees + slippage
}

fun roundTrip(qty: Int, buyPrice: Double, sellPrice: Double, r: Rates = Rates()): Charges {
    val buy = qty * buyPrice
    val sell = qty * sellPrice
    val brokerage = min(r.brokerageCap, buy * r.brokeragePct) + min(r.brokerageCap, sell * r.brokeragePct)
    val exchange = (buy + sell) * r.exchangePct
    val sebi = (buy + sell) * r.sebiPct
    return Charges(brokerage, sell * r.sttSellPct, exchange, sebi, buy * r.stampBuyPct,
        (brokerage + exchange + sebi) * r.gstPct, (buy + sell) * r.slippagePct)
}

enum class Side { LONG, SHORT }

/**
 * Opening-range breakout. Range: the first [orBars] five-minute bars (9:15 to 9:30).
 * Signal: the first CLOSED bar after it that closes above the range (long) or, with shorts
 * on, below it. Stop at the range midpoint (or far side), target = [targetR] x risk, out by
 * [flatBy] either way, before the broker's own square-off.
 */
data class Strategy(
    val orBars: Int = 3,
    val lastEntry: String = "14:30",
    val flatBy: String = "15:15",
    val stopAtMid: Boolean = true,
    val targetR: Double = 1.5, // 1R barely pays: after Indian charges it is often ~0.7R
    val maxRangePct: Double = 0.03,
    val shorts: Boolean = false,
) {
    /** Index of the last bar held: the one that ends at [flatBy]. */
    val flatIndex: Int get() = Nse.barIndex(java.time.LocalDate.of(2000, 1, 3).atTime(Nse.hhmm(flatBy))) - 1
}

data class Plan(
    val symbol: String,
    val day: java.time.LocalDate,
    val side: Side,
    val entry: Double,
    val entryBar: Int,
    val entryTime: String,
    val stop: Double,
    val target: Double,
    val rangeHigh: Double,
    val rangeLow: Double,
    val targetR: Double,
    val flatBy: String,
) {
    val risk: Double get() = kotlin.math.abs(entry - stop)
}

data class Outcome(val result: String, val exit: Double, val exitTime: String, val r: Double)

private const val TICK = 0.05
private fun down(x: Double) = floor(((x / TICK) * 1e6).roundToLong() / 1e6) * TICK
private fun up(x: Double) = ceil(((x / TICK) * 1e6).roundToLong() / 1e6) * TICK
private fun round2(x: Double) = (x * 100).roundToLong() / 100.0

fun openingRange(bars: List<Bar>, n: Int): Pair<Double, Double>? {
    if (bars.size < n || bars.take(n).withIndex().any { (i, b) -> b.index != i }) return null
    return bars.take(n).maxOf { it.high } to bars.take(n).minOf { it.low }
}

/** The day's first breakout and its plan, or null. [bars] must be closed bars only. */
fun signal(symbol: String, bars: List<Bar>, s: Strategy): Plan? {
    val (hi, lo) = openingRange(bars, s.orBars) ?: return null
    if (lo <= 0 || (hi - lo) / lo > s.maxRangePct) return null
    val last = Nse.hhmm(s.lastEntry)
    for (b in bars.drop(s.orBars)) {
        if (b.end.toLocalTime().isAfter(last)) return null
        val side = when {
            b.close > hi -> Side.LONG
            s.shorts && b.close < lo -> Side.SHORT
            else -> continue
        }
        val entry = b.close
        val mid = (hi + lo) / 2
        val (stop, target) = if (side == Side.LONG) {
            val st = down(if (s.stopAtMid) mid else lo)
            if (st >= entry) return null
            st to up(entry + s.targetR * (entry - st))
        } else {
            val st = up(if (s.stopAtMid) mid else hi)
            if (st <= entry) return null
            st to down(entry - s.targetR * (st - entry))
        }
        return Plan(symbol, b.start.toLocalDate(), side, round2(entry), b.index, b.end.toLocalTime().toString(),
            round2(stop), round2(target), hi, lo, s.targetR, s.flatBy)
    }
    return null
}

/**
 * What a plan did on a real day. Inside a bar the stop is checked before the target (we can't
 * tell which came first, so assume the worse); a gap through the stop fills at the open.
 */
fun outcome(bars: List<Bar>, plan: Plan, flatIndex: Int): Outcome? {
    val long = plan.side == Side.LONG
    var lastBar: Bar? = null
    for (b in bars) {
        if (b.index <= plan.entryBar) continue
        if (b.index > flatIndex) break
        lastBar = b
        if (long && b.low <= plan.stop || !long && b.high >= plan.stop) {
            val px = if (long) min(b.open, plan.stop) else max(b.open, plan.stop)
            return Outcome("stop", round2(px), b.start.toLocalTime().toString(), rOf(plan, px))
        }
        if (long && b.high >= plan.target || !long && b.low <= plan.target) {
            val px = if (long) max(b.open, plan.target) else min(b.open, plan.target)
            return Outcome("target", round2(px), b.start.toLocalTime().toString(), rOf(plan, px))
        }
    }
    val b = lastBar ?: return null
    return Outcome("flat", round2(b.close), b.start.toLocalTime().toString(), rOf(plan, b.close))
}

fun rOf(plan: Plan, exit: Double): Double = (exit - plan.entry) / plan.risk * (if (plan.side == Side.LONG) 1 else -1)
