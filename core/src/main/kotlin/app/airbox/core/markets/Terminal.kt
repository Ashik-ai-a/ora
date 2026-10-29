package app.airbox.core.markets

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.Serializable

/** One ORB trade in the proof run, after charges. */
data class ProofTrade(
    val day: LocalDate, val symbol: String, val side: Side, val time: String, val entry: Double, val exit: Double,
    val result: String, val qty: Int, val r: Double, val pnlGross: Double, val pnlNet: Double, val charges: Double,
    val riskRs: Double, val relVolume: Double,
)

@Serializable
data class ProofSummary(
    val trades: Int, val days: Int, val target: Double, val stop: Double, val flat: Double, val winRate: Double,
    val avgRGross: Double, val avgRNet: Double, val pnlGross: Double, val charges: Double, val pnlNet: Double,
    val profitFactor: Double, val maxDrawdown: Double, val greenDays: Double, val worstDay: Double, val bestDay: Double,
)

object OrbProof {
    fun tradesFor(symbol: String, sessions: Map<LocalDate, List<Bar>>, s: Strategy, lim: RiskLimits, rates: Rates, minValue: Double): List<ProofTrade> {
        val full = sessions.entries.filter { isFull(it.value) }
        return full.indices.mapNotNull { k ->
            val (day, bars) = full[k]
            val past = full.subList(maxOf(0, k - 10), k)
            if (past.size < 5) return@mapNotNull null
            val traded = past.takeLast(5).sumOf { e -> e.value.sumOf { it.close * it.volume } } / 5
            if (traded < minValue) return@mapNotNull null
            val plan = signal(symbol, bars, s) ?: return@mapNotNull null
            val res = outcome(bars, plan, s.flatIndex) ?: return@mapNotNull null
            val order = Money.size(plan, lim, rates) ?: return@mapNotNull null
            val long = plan.side == Side.LONG
            val gross = order.qty * (res.exit - plan.entry) * (if (long) 1 else -1)
            val c = if (long) roundTrip(order.qty, plan.entry, res.exit, rates) else roundTrip(order.qty, res.exit, plan.entry, rates)
            val orVol = bars.take(s.orBars).sumOf { it.volume }
            val pastOr = past.sumOf { e -> e.value.take(s.orBars).sumOf { it.volume } } / past.size
            ProofTrade(day, symbol, plan.side, plan.entryTime, plan.entry, res.exit, res.result, order.qty, res.r, gross,
                gross - c.total, c.total, order.qty * plan.risk, if (pastOr > 0) orVol / pastOr else 0.0)
        }
    }

    /** The trades a disciplined person could actually have taken, day by day. */
    fun portfolio(trades: List<ProofTrade>, lim: RiskLimits): List<ProofTrade> =
        trades.groupBy { it.day }.toSortedMap().flatMap { (_, day) ->
            var pnl = 0.0
            val taken = mutableListOf<ProofTrade>()
            for (t in day.sortedWith(compareBy<ProofTrade> { it.time }.thenByDescending { it.relVolume })) {
                if (taken.size >= lim.maxTradesPerDay || pnl <= -lim.dailyLossLimit) break
                taken += t
                pnl += t.pnlNet
            }
            taken
        }

    fun summary(trades: List<ProofTrade>): ProofSummary? {
        if (trades.isEmpty()) return null
        val n = trades.size.toDouble()
        var eq = 0.0; var peak = 0.0; var dd = 0.0
        trades.sortedWith(compareBy({ it.day }, { it.time })).forEach { eq += it.pnlNet; peak = maxOf(peak, eq); dd = minOf(dd, eq - peak) }
        val byDay = trades.groupBy { it.day }.mapValues { (_, v) -> v.sumOf { it.pnlNet } }
        val won = trades.filter { it.pnlNet > 0 }.sumOf { it.pnlNet }
        val lost = -trades.filter { it.pnlNet <= 0 }.sumOf { it.pnlNet }
        return ProofSummary(trades.size, byDay.size, trades.count { it.result == "target" } / n, trades.count { it.result == "stop" } / n,
            trades.count { it.result == "flat" } / n, trades.count { it.pnlNet > 0 } / n, trades.sumOf { it.r } / n,
            trades.sumOf { it.pnlNet / it.riskRs } / n, trades.sumOf { it.pnlGross }, trades.sumOf { it.charges }, eq,
            if (lost > 0) won / lost else Double.POSITIVE_INFINITY, dd, byDay.values.count { it > 0 }.toDouble() / byDay.size,
            byDay.values.min(), byDay.values.max())
    }
}

/**
 * Everything the terminal screens ask for, on top of a [MarketSource]. Blocking calls: the app
 * runs them off the main thread. [progress] gets (done, total) for long jobs.
 */
class Terminal(private val src: MarketSource, val universe: Universe = Universe.MIDSMALL_400) {
    fun stocks(): List<Stock> = src.universe(universe)

    /** Live quotes for every stock, 20 per request, with today's 5-minute closes as the sparkline. */
    fun monitor(progress: (Int, Int) -> Unit = { _, _ -> }): List<Quote> {
        val syms = stocks().map { it.symbol }
        val out = mutableListOf<Quote>()
        syms.chunked(20).forEachIndexed { i, batch ->
            src.spark(batch, "1d", "5m").values.mapNotNullTo(out) { it.quote() }
            progress(minOf(syms.size, (i + 1) * 20), syms.size)
        }
        return out
    }

    data class Breakout(val plan: Plan, val relVolume: Double, val tradedCr: Double)
    data class Scan(val asOf: LocalDateTime, val breakouts: List<Breakout>, val near: List<Pair<String, Double>>, val checked: Int)

    /**
     * Opening-range breakouts now. Stage 1: today's 5-minute closes for every stock (cheap).
     * A close above the range's highest close is needed for a breakout, so only those get the
     * full bars (stage 2) to check against the real highs and lows.
     */
    fun scan(s: Strategy, now: LocalDateTime = Nse.now(), minTradedCr: Double = 10.0, progress: (Int, Int) -> Unit = { _, _ -> }): Scan {
        val syms = stocks().map { it.symbol }
        val candidates = mutableListOf<String>()
        val near = mutableListOf<Pair<String, Double>>()
        syms.chunked(20).forEachIndexed { i, batch ->
            src.spark(batch, "1d", "5m").forEach { (sym, ch) ->
                val today = ch.times.indices.filter { ch.times[it].toLocalDate() == now.toLocalDate() && ch.close[it] != null }
                if (today.size <= s.orBars) return@forEach
                val orMax = today.take(s.orBars).maxOf { ch.close[it]!! }
                val closes = today.drop(s.orBars).map { ch.close[it]!! }
                when {
                    closes.any { it > orMax } || s.shorts -> candidates += sym
                    closes.last() >= orMax * 0.995 -> near += sym to closes.last()
                }
            }
            progress(minOf(syms.size, (i + 1) * 20) / 2, syms.size)
        }
        val found = candidates.mapIndexedNotNull { i, sym ->
            progress(syms.size / 2 + (i + 1) * (syms.size / 2) / candidates.size.coerceAtLeast(1), syms.size)
            val sess = sessions(src.chart(sym, "5d", "5m")?.bars() ?: return@mapIndexedNotNull null)
            val today = sess[now.toLocalDate()] ?: return@mapIndexedNotNull null
            val past = sess.filter { it.key.isBefore(now.toLocalDate()) && isFull(it.value) }.values.toList()
            val traded = if (past.isEmpty()) 0.0 else past.sumOf { d -> d.sumOf { it.close * it.volume } } / past.size
            if (traded < minTradedCr * 1e7) return@mapIndexedNotNull null
            val plan = signal(sym, closedBars(today, now), s) ?: return@mapIndexedNotNull null
            val orVol = today.take(s.orBars).sumOf { it.volume }
            val pastOr = if (past.isEmpty()) 0.0 else past.sumOf { d -> d.take(s.orBars).sumOf { it.volume } } / past.size
            Breakout(plan, if (pastOr > 0) orVol / pastOr else 0.0, traded / 1e7)
        }.sortedWith(compareBy<Breakout> { it.plan.entryTime }.thenByDescending { it.relVolume })
        return Scan(now, found, near.sortedByDescending { it.second }.take(15), syms.size)
    }

    data class Machine(val plan: Plan, val replay: TimeMachine.Replay, val history: List<TimeMachine.Past>, val costR: Double,
                       val verdict: String, val sessions: Int, val order: Order?)

    fun timeMachine(plan: Plan, lim: RiskLimits, rates: Rates = Rates(), replays: Int = 10_000): Machine {
        val sess = sessions(src.chart(plan.symbol, "60d", "5m")?.bars().orEmpty())
        val pool = TimeMachine.pool(sess, plan.day)
        require(pool.size >= 20) { "Only ${pool.size} full sessions before ${plan.day}: too few for a fair replay (need 20)." }
        val s = Strategy(targetR = plan.targetR, flatBy = plan.flatBy, shorts = plan.side == Side.SHORT)
        val order = Money.size(plan, lim, rates)
        val qty = order?.qty ?: 1
        val costPerShare = (if (plan.side == Side.LONG) roundTrip(qty, plan.entry, plan.target, rates) else roundTrip(qty, plan.target, plan.entry, rates)).total / qty
        val costR = costPerShare / plan.risk
        val rep = TimeMachine.replay(plan, pool, s.flatIndex, replays)
        val hist = TimeMachine.history(plan.symbol, sess, plan.day, s).filter { it.side == plan.side }
        return Machine(plan, rep, hist, costR, TimeMachine.verdict(rep.target, rep.stop, plan.targetR, costR), pool.size, order)
    }

    /** Today's bars for one stock, for its chart, and its plan if it broke out. */
    fun intraday(symbol: String, s: Strategy, now: LocalDateTime = Nse.now()): Pair<List<Bar>, Plan?> {
        val sess = sessions(src.chart(symbol, "5d", "5m")?.bars().orEmpty())
        val day = sess.keys.lastOrNull { !it.isAfter(now.toLocalDate()) } ?: return emptyList<Bar>() to null
        val bars = closedBars(sess.getValue(day), if (day == now.toLocalDate()) now else day.atTime(Nse.CLOSE))
        return bars to signal(symbol, bars, s)
    }

    fun daily(symbol: String, range: String = "1y"): List<DailyBar> = src.chart(symbol, range, "1d")?.daily().orEmpty()

    fun news(symbol: String): List<Headline> = src.news(symbol)

    data class MomentumNow(val asOf: LocalDate, val invested: Boolean, val ranked: List<Momentum.Score>, val left: Map<String, Int>)

    /**
     * This month's momentum list. Stage 1 ranks on 2 years of spark closes; the top 40 are then
     * re-scored on proper adjusted daily data with volumes (splits and bonuses fixed, liquidity known).
     */
    fun momentum(rules: Momentum.Rules = Momentum.Rules(), progress: (Int, Int) -> Unit = { _, _ -> }): MomentumNow {
        val syms = stocks().map { it.symbol }
        val rough = HashMap<String, List<DailyBar>>()
        syms.chunked(20).forEachIndexed { i, batch ->
            src.spark(batch, "2y", "1d").forEach { (sym, ch) -> rough[sym] = ch.daily() }
            progress(minOf(syms.size, (i + 1) * 20) * 2 / 3, syms.size)
        }
        val roughPanel = Panel.of(rough)
        val t = roughPanel.days.size - 1
        val invested = Momentum.regime(Momentum.marketIndex(roughPanel)).let { it.isNotEmpty() && it[t] }
        val firstPass = Momentum.scores(roughPanel, t, rules.copy(minValue = 0.0))
        val top = firstPass.filter { it.eligible }.take(40).map { it.symbol }
        val exact = HashMap(rough)
        top.forEachIndexed { i, sym ->
            daily(sym, "2y").takeIf { it.size > 260 }?.let { exact[sym] = it }
            progress(syms.size * 2 / 3 + (i + 1) * (syms.size / 3) / top.size.coerceAtLeast(1), syms.size)
        }
        val panel = Panel.of(exact.filterKeys { it in top }.ifEmpty { exact })
        val tt = panel.days.size - 1
        val ranked = Momentum.scores(panel, tt, rules)
        val left = firstPass.filter { !it.eligible }.groupingBy { it.reason }.eachCount()
        return MomentumNow(panel.days.lastOrNull() ?: LocalDate.now(), invested, ranked, left)
    }

    data class MomentumProof(val result: Momentum.Result, val metrics: Momentum.Metrics?, val universeMetrics: Momentum.Metrics?,
                             val yearly: List<Pair<Int, Double>>, val stocks: Int, val grid: List<Triple<Int, Boolean, Momentum.Metrics?>>)

    /** Monthly momentum over 5 years of adjusted daily data for every stock (about 25 MB: use Wi-Fi). */
    fun momentumProof(progress: (Int, Int) -> Unit = { _, _ -> }): MomentumProof {
        val syms = stocks().map { it.symbol }
        val data = HashMap<String, List<DailyBar>>()
        syms.forEachIndexed { i, sym ->
            daily(sym, "5y").takeIf { it.size > 300 }?.let { data[sym] = it }
            progress(i + 1, syms.size)
        }
        val p = Panel.of(data)
        val res = Momentum.backtest(p, Momentum.Rules())
        val idx = Momentum.marketIndex(p)
        val from = p.days.indexOf(res.days.firstOrNull() ?: p.days.first()).coerceAtLeast(0)
        val uni = Momentum.metrics(p.days.subList(from, p.days.size), idx.copyOfRange(from, idx.size))
        val grid = listOf(5, 10, 20).flatMap { n -> listOf(true, false).map { reg ->
            val r = Momentum.backtest(p, Momentum.Rules(topN = n, useRegime = reg))
            Triple(n, reg, Momentum.metrics(r.days, r.equity))
        } }
        return MomentumProof(res, Momentum.metrics(res.days, res.equity), uni, Momentum.yearly(res.days, res.equity), data.size, grid)
    }

    data class IntradayProof(val every: ProofSummary?, val taken: ProofSummary?, val stocks: Int, val targetR: Double, val trades: List<ProofTrade>)

    /**
     * ORB on the [maxStocks] most traded stocks over the ~60 days of 5-minute data, after
     * charges, with your limits. About 300 KB a stock: use Wi-Fi.
     */
    fun intradayProof(lim: RiskLimits, targets: List<Double> = listOf(1.0, 1.5, 2.0), maxStocks: Int = 100, rates: Rates = Rates(),
                      progress: (Int, Int) -> Unit = { _, _ -> }): List<IntradayProof> {
        val syms = stocks().map { it.symbol }
        val traded = HashMap<String, Double>()
        syms.chunked(20).forEach { batch -> src.spark(batch, "5d", "1d").values.forEach { ch -> ch.quote()?.tradedValue?.let { traded[ch.symbol] = it } } }
        val chosen = traded.entries.sortedByDescending { it.value }.take(maxStocks).map { it.key }
        val sess = HashMap<String, Map<LocalDate, List<Bar>>>()
        chosen.forEachIndexed { i, sym ->
            src.chart(sym, "60d", "5m")?.bars()?.let { sess[sym] = sessions(it) }
            progress(i + 1, chosen.size)
        }
        return targets.map { r ->
            val s = Strategy(targetR = r)
            val all = sess.flatMap { (sym, ss) -> OrbProof.tradesFor(sym, ss, s, lim, rates, 10 * 1e7) }
            val taken = OrbProof.portfolio(all, lim)
            IntradayProof(OrbProof.summary(all), OrbProof.summary(taken), sess.size, r, taken)
        }
    }
}
