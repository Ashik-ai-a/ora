package app.airbox.core.markets

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * Time Machine: replay the rest of the session thousands of times before risking a rupee.
 * Method from the MIT-licensed Day Trader skill (github.com/Jakeschincariol/day-trader-skill),
 * moved to NSE hours with Indian charges taken off.
 *
 *  1. Pool: every FULL session of this stock before the plan's day; each bar becomes four
 *     ratios to the previous bar's close.
 *  2. A replay draws, for each bar left until the flat-by time, a past bar from the same time
 *     of day (+/- window) and scales it to the current price. Stop before target in a bar.
 *  3. History: the same rule on every past session. That really happened.
 *  4. Verdict: of replays that hit a side, how often the target came first, against the
 *     break-even rate once costs are paid: (1 + c) / (1 + R), c = costs in units of risk.
 */
object TimeMachine {
    class Ratios(val open: Double, val high: Double, val low: Double, val close: Double)

    data class Replay(
        val replays: Int, val seed: Long, val target: Double, val stop: Double, val neither: Double, val green: Double,
        val avgR: Double, val exitP5: Double, val exitMedian: Double, val exitP95: Double,
        /** Price paths for a fan chart: (bar index, 5%, 25%, 50%, 75%, 95%). */
        val fan: List<DoubleArray>,
    )

    data class Past(val day: LocalDate, val side: Side, val result: String, val r: Double, val risk: Double, val entry: Double)

    fun pool(sessions: Map<LocalDate, List<Bar>>, before: LocalDate): List<Array<Ratios>> =
        sessions.filter { (d, b) -> d.isBefore(before) && isFull(b) }.values.map { bars ->
            Array(bars.size) { i ->
                if (i == 0) Ratios(1.0, 1.0, 1.0, 1.0)
                else bars[i - 1].close.let { pc -> Ratios(bars[i].open / pc, bars[i].high / pc, bars[i].low / pc, bars[i].close / pc) }
            }
        }

    fun replay(plan: Plan, pool: List<Array<Ratios>>, flatIndex: Int, replays: Int = 10_000, seed: Long = 19, window: Int = 2): Replay {
        require(pool.isNotEmpty())
        val rng = java.util.Random(seed)
        val long = plan.side == Side.LONG
        var nT = 0; var nS = 0; var nN = 0; var nG = 0; var rSum = 0.0
        val steps = (flatIndex - plan.entryBar).coerceAtLeast(0)
        val finals = DoubleArray(replays)
        val paths = Array(steps + 1) { DoubleArray(replays) }
        for (k in 0 until replays) {
            var p = plan.entry
            var hit: String? = null
            paths[0][k] = p
            var step = 0
            for (i in plan.entryBar + 1..flatIndex) {
                step++
                if (hit == null) {
                    val day = pool[rng.nextInt(pool.size)]
                    val j = min(Nse.BARS_PER_DAY - 1, max(1, i + rng.nextInt(2 * window + 1) - window)).coerceAtMost(day.size - 1)
                    val r = day[j]
                    val o = p * r.open; val h = p * r.high; val l = p * r.low; val c = p * r.close
                    if (long && l <= plan.stop || !long && h >= plan.stop) {
                        p = if (long) min(o, plan.stop) else max(o, plan.stop); hit = "stop"
                    } else if (long && h >= plan.target || !long && l <= plan.target) {
                        p = if (long) max(o, plan.target) else min(o, plan.target); hit = "target"
                    } else p = c
                }
                paths[step][k] = p
            }
            val r = rOf(plan, p)
            rSum += r
            finals[k] = p
            when (hit) { "target" -> nT++; "stop" -> nS++; else -> nN++ }
            if (hit == "target" || (hit == null && r > 0)) nG++
        }
        finals.sort()
        fun q(a: DoubleArray, f: Double) = a[min(a.size - 1, (f * a.size).toInt())]
        val fan = paths.mapIndexed { s, col ->
            col.sort(); doubleArrayOf((plan.entryBar + s).toDouble(), q(col, .05), q(col, .25), q(col, .5), q(col, .75), q(col, .95))
        }
        val n = replays.toDouble()
        return Replay(replays, seed, nT / n, nS / n, nN / n, nG / n, rSum / n, q(finals, .05), q(finals, .5), q(finals, .95), fan)
    }

    fun history(symbol: String, sessions: Map<LocalDate, List<Bar>>, before: LocalDate, s: Strategy): List<Past> =
        sessions.filter { (d, b) -> d.isBefore(before) && isFull(b) }.mapNotNull { (d, bars) ->
            val p = signal(symbol, bars, s) ?: return@mapNotNull null
            val o = outcome(bars, p, s.flatIndex) ?: return@mapNotNull null
            Past(d, p.side, o.result, o.r, p.risk, p.entry)
        }

    fun verdict(target: Double, stop: Double, targetR: Double, costR: Double): String {
        val hits = target + stop
        if (hits <= 0) return "Almost every replay was still open at the flat-by time: no edge to read."
        val rate = target / hits
        val breakEven = (1 + costR) / (1 + targetR)
        val gap = (rate - breakEven) * 100
        val base = "Target first in ${"%.0f".format(rate * 100)}% of replays that hit a side; after charges it needs " +
            "${"%.0f".format(breakEven * 100)}% to break even. "
        return base + when {
            kotlin.math.abs(gap) < 5 -> if (kotlin.math.abs(targetR - 1) < 1e-9) "Close to a coin flip." else "Close to break-even."
            gap > 0 -> if (gap < 15) "Leans toward the target." else "Strongly toward the target."
            else -> if (gap > -15) "Leans toward the stop." else "Strongly toward the stop."
        }
    }
}
