package app.airbox.core.markets

import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

/** Daily prices for many stocks on one shared calendar; missing values are NaN. */
class Panel(val days: List<LocalDate>, val symbols: List<String>, val adj: Array<DoubleArray>, val value: Array<DoubleArray>) {
    companion object {
        fun of(daily: Map<String, List<DailyBar>>): Panel {
            val counts = HashMap<LocalDate, Int>()
            daily.values.forEach { bars -> bars.forEach { counts.merge(it.day, 1, Int::plus) } }
            // Days when almost nothing traded are holidays that slipped in.
            val days = counts.filter { it.value >= 0.2 * daily.size.coerceAtLeast(1) }.keys.sorted()
            val pos = days.withIndex().associate { it.value to it.index }
            val syms = daily.keys.sorted()
            val adj = Array(syms.size) { DoubleArray(days.size) { Double.NaN } }
            val value = Array(syms.size) { DoubleArray(days.size) { Double.NaN } }
            syms.forEachIndexed { s, sym ->
                daily.getValue(sym).forEach { b ->
                    val i = pos[b.day] ?: return@forEach
                    adj[s][i] = b.adjClose
                    if (b.volume > 0) value[s][i] = b.close * b.volume
                }
            }
            return Panel(days, syms, adj, value)
        }
    }
}

/**
 * Monthly momentum on mid and small caps. Ranking follows the idea behind NSE's momentum
 * indices: 6- and 12-month returns, skipping the latest month, divided by volatility. Only
 * stocks above their 200-day average, trading >= minValue a day, not up > spikeLimit in a month.
 * When the whole universe is below its 200-day average, hold cash.
 */
object Momentum {
    const val M6 = 126
    const val M12 = 252
    const val SKIP = 21

    data class Rules(val topN: Int = 10, val minValue: Double = 5e7, val spikeLimit: Double = 0.60, val useRegime: Boolean = true)

    data class Score(
        val symbol: String, val price: Double, val ret1m: Double, val ret6m: Double, val ret12m: Double, val vol: Double,
        val sma200: Double, val valueCr: Double?, val reason: String, val score: Double,
    ) {
        val eligible: Boolean get() = reason.isEmpty()
    }

    fun marketIndex(p: Panel): DoubleArray {
        val out = DoubleArray(p.days.size)
        var level = 1.0
        for (t in p.days.indices) {
            if (t > 0) {
                var sum = 0.0; var n = 0
                for (s in p.symbols.indices) {
                    val a = p.adj[s][t - 1]; val b = p.adj[s][t]
                    if (!a.isNaN() && !b.isNaN() && a > 0) { sum += (b / a - 1).coerceIn(-0.5, 1.0); n++ }
                }
                if (n > 0) level *= 1 + sum / n
            }
            out[t] = level
        }
        return out
    }

    fun regime(index: DoubleArray): BooleanArray = BooleanArray(index.size) { t ->
        t >= 199 && index[t] > (t - 199..t).sumOf { index[it] } / 200
    }

    fun scores(p: Panel, t: Int, rules: Rules): List<Score> {
        if (t < M12) return emptyList()
        val raw = p.symbols.indices.map { s ->
            val a = p.adj[s]
            val price = a[t]; val pSkip = a[t - SKIP]; val p6 = a[t - M6]; val p12 = a[t - M12]
            val window = (t - M12..t).map { a[it] }
            val valid = window.count { !it.isNaN() }
            val rets = (t - M12 + 1..t).mapNotNull { i -> if (a[i].isNaN() || a[i - 1].isNaN() || a[i - 1] <= 0) null else a[i] / a[i - 1] - 1 }
            val vol = std(rets) * sqrt(252.0)
            val last200 = (t - 199..t).map { a[it] }.filter { !it.isNaN() }
            val sma = if (last200.isEmpty()) Double.NaN else last200.average()
            val v = (t - 59..t).map { p.value[s][it] }.filter { !it.isNaN() }
            val liquidity = if (v.isEmpty()) null else median(v)
            val r6 = pSkip / p6 - 1
            val r12 = pSkip / p12 - 1
            val r1 = price / pSkip - 1
            val reason = when {
                valid < M12 * 0.95 || price.isNaN() || pSkip.isNaN() || p6.isNaN() || p12.isNaN() -> "under a year of prices"
                price <= sma -> "below 200-day average"
                r6 <= 0 || r12 <= 0 -> "no momentum"
                liquidity != null && liquidity < rules.minValue -> "too little trading"
                r1 > rules.spikeLimit -> "spiked this month"
                !(vol > 0) -> "no price movement"
                else -> ""
            }
            Score(p.symbols[s], price, r1, r6, r12, vol, sma, liquidity?.div(1e7), reason, Double.NaN)
        }
        val ok = raw.filter { it.eligible }
        if (ok.isEmpty()) return raw.sortedBy { it.symbol }
        val m6 = ok.map { it.ret6m / it.vol }; val m12 = ok.map { it.ret12m / it.vol }
        fun z(xs: List<Double>) = (std(xs).takeIf { it > 0 } ?: 1.0).let { sd -> xs.average().let { mu -> xs.map { (it - mu) / sd } } }
        val z6 = z(m6); val z12 = z(m12)
        val scored = ok.mapIndexed { i, s -> s.copy(score = 0.5 * z6[i] + 0.5 * z12[i]) }.sortedByDescending { it.score }
        return scored + raw.filter { !it.eligible }
    }

    fun picks(p: Panel, t: Int, rules: Rules, regime: BooleanArray?): List<String> {
        if (rules.useRegime && regime != null && !regime[t]) return emptyList()
        return scores(p, t, rules).filter { it.eligible }.take(rules.topN).map { it.symbol }
    }

    class Result(val days: List<LocalDate>, val equity: DoubleArray, val holdings: Map<LocalDate, List<String>>, val turnover: Double)

    /** Decide at each month's last trading day; trade at the next day's close; cost on every buy and sell. */
    fun backtest(p: Panel, rules: Rules, cost: Double = 0.0025, startIndex: Int = M12 + 1): Result {
        val n = p.days.size
        val ffill = Array(p.symbols.size) { s -> DoubleArray(n).also { out -> var last = Double.NaN; for (t in 0 until n) { if (!p.adj[s][t].isNaN()) last = p.adj[s][t]; out[t] = last } } }
        val reg = regime(marketIndex(p))
        val monthEnd = BooleanArray(n) { t -> t == n - 1 || p.days[t + 1].month != p.days[t].month }
        var cash = 1.0
        var units = DoubleArray(p.symbols.size)
        val equity = DoubleArray(n) { Double.NaN }
        val holdings = LinkedHashMap<LocalDate, List<String>>()
        val turnovers = mutableListOf<Double>()
        var pending: List<String>? = null
        val first = (startIndex until n).firstOrNull { monthEnd[it] } ?: n
        for (t in first until n) {
            fun value(): Double = cash + p.symbols.indices.sumOf { s -> if (units[s] == 0.0 || ffill[s][t].isNaN()) 0.0 else units[s] * ffill[s][t] }
            var v = value()
            pending?.let { target ->
                pending = null
                val idx = target.map { p.symbols.indexOf(it) }.filter { !ffill[it][t].isNaN() }
                val want = DoubleArray(p.symbols.size).also { w -> idx.forEach { w[it] = v / idx.size } }
                val current = DoubleArray(p.symbols.size) { s -> if (ffill[s][t].isNaN()) 0.0 else units[s] * ffill[s][t] }
                val traded = p.symbols.indices.sumOf { kotlin.math.abs(want[it] - current[it]) }
                v -= traded * cost
                turnovers += traded / v.coerceAtLeast(1e-12)
                val scale = if (idx.isEmpty()) 0.0 else v / want.sum()
                units = DoubleArray(p.symbols.size) { s -> if (want[s] > 0) want[s] * scale / ffill[s][t] else 0.0 }
                cash = if (idx.isEmpty()) v else 0.0
                holdings[p.days[t]] = idx.map { p.symbols[it] }
            }
            equity[t] = v
            if (monthEnd[t] && t + 1 < n) pending = picks(p, t, rules, reg)
        }
        val from = first.coerceAtMost(n - 1)
        return Result(p.days.subList(from, n), equity.copyOfRange(from, n), holdings, if (turnovers.isEmpty()) 0.0 else turnovers.average())
    }

    @kotlinx.serialization.Serializable
    data class Metrics(val years: Double, val cagr: Double, val maxDrawdown: Double, val worstMonth: Double, val monthsUp: Double,
                       val sharpe: Double, val growthOf1L: Double)

    fun metrics(days: List<LocalDate>, equity: DoubleArray, rf: Double = 0.065): Metrics? {
        val pts = days.indices.filter { !equity[it].isNaN() }
        if (pts.size < 30) return null
        val e = pts.map { equity[it] }; val d = pts.map { days[it] }
        val years = java.time.temporal.ChronoUnit.DAYS.between(d.first(), d.last()) / 365.25
        val cagr = (e.last() / e.first()).pow(1 / years) - 1
        var peak = e.first(); var dd = 0.0
        e.forEach { peak = maxOf(peak, it); dd = minOf(dd, it / peak - 1) }
        val daily = e.zipWithNext { a, b -> b / a - 1 }
        val vol = std(daily) * sqrt(252.0)
        val monthly = d.indices.groupBy { d[it].year * 12 + d[it].monthValue }.values.map { e[it.last()] }.zipWithNext { a, b -> b / a - 1 }
        return Metrics(years, cagr, dd, monthly.minOrNull() ?: 0.0, monthly.count { it > 0 }.toDouble() / monthly.size.coerceAtLeast(1),
            if (vol > 0) (cagr - rf) / vol else 0.0, 1e5 * e.last() / e.first())
    }

    fun yearly(days: List<LocalDate>, equity: DoubleArray): List<Pair<Int, Double>> {
        val pts = days.indices.filter { !equity[it].isNaN() }
        val byYear = pts.groupBy { days[it].year }.toSortedMap()
        var prev: Double? = null
        return byYear.map { (y, idx) ->
            val start = prev ?: equity[idx.first()]
            val end = equity[idx.last()]
            prev = end
            y to end / start - 1
        }
    }

    private fun std(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    private fun median(xs: List<Double>): Double = xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
}
