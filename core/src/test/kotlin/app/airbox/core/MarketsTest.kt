package app.airbox.core

import app.airbox.core.markets.Bar
import app.airbox.core.markets.Chart
import app.airbox.core.markets.DailyBar
import app.airbox.core.markets.Headline
import app.airbox.core.markets.MarketSource
import app.airbox.core.markets.Momentum
import app.airbox.core.markets.Money
import app.airbox.core.markets.Nse
import app.airbox.core.markets.OrbProof
import app.airbox.core.markets.Panel
import app.airbox.core.markets.ProofTrade
import app.airbox.core.markets.Rates
import app.airbox.core.markets.RiskLimits
import app.airbox.core.markets.Side
import app.airbox.core.markets.Stock
import app.airbox.core.markets.Strategy
import app.airbox.core.markets.Terminal
import app.airbox.core.markets.TimeMachine
import app.airbox.core.markets.Universe
import app.airbox.core.markets.YahooSource
import app.airbox.core.markets.closedBars
import app.airbox.core.markets.isFull
import app.airbox.core.markets.outcome
import app.airbox.core.markets.roundTrip
import app.airbox.core.markets.sessions
import app.airbox.core.markets.signal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hand-built NSE days (IST, 75 five-minute bars) with answers worked out by hand. */
class MarketsTest {
    private val day = LocalDate.of(2026, 10, 20)

    private fun dayBars(d: LocalDate, path: List<Double>, vol: Double = 10_000.0): List<Bar> {
        var prev = path.first()
        return path.mapIndexed { i, c ->
            val o = prev; prev = c
            Bar(Nse.slot(d, i), o, maxOf(o, c) * 1.001, minOf(o, c) * 0.999, c, vol)
        }
    }

    /** Range ~99.7-100.4 for 15 minutes, breakout at the 9:30 bar's close, then target or stop. */
    private fun breakout(d: LocalDate, win: Boolean): List<Bar> {
        val path = mutableListOf(100.0, 100.3, 99.8, 100.9)
        path += if (win) listOf(101.5, 102.5, 103.5) else listOf(100.4, 99.9, 99.6)
        while (path.size < Nse.BARS_PER_DAY) path += path.last()
        return dayBars(d, path)
    }

    private fun quiet(d: LocalDate, seed: Int = 0): List<Bar> {
        val rng = Random(seed); var p = 100.0
        return dayBars(d, List(Nse.BARS_PER_DAY) { p *= 1 + rng.nextDouble(-0.001, 0.001); p })
    }

    private fun weekdays(n: Int, before: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>(); var d = before
        while (out.size < n) { d = d.minusDays(1); if (d.dayOfWeek.value <= 5) out += d }
        return out.reversed()
    }

    @Test fun sessionShape() {
        assertEquals("15:25", Nse.slot(day, 74).toLocalTime().toString())
        assertEquals(-1, Nse.barIndex(day.atTime(9, 14)))
        assertTrue(isFull(quiet(day)))
        assertEquals(71, Strategy().flatIndex) // the 15:10 bar ends at 15:15
    }

    @Test fun breakoutPlanAndOutcomes() {
        val p = signal("A", breakout(day, true), Strategy())!!
        assertEquals(Side.LONG, p.side); assertEquals(3, p.entryBar); assertEquals("09:35", p.entryTime)
        assertEquals(1.5 * p.risk, p.target - p.entry, 0.051)
        assertEquals("target", outcome(breakout(day, true), p, 71)!!.result)
        val lose = breakout(day, false)
        val o = outcome(lose, signal("A", lose, Strategy())!!, 71)!!
        assertEquals("stop", o.result); assertTrue(o.r <= -1.0)
        assertNull(signal("A", quiet(day), Strategy(maxRangePct = 0.0001)))
    }

    @Test fun formingBarIsNeverUsed() {
        val bars = breakout(day, true)
        assertNull(signal("A", closedBars(bars, day.atTime(9, 33)), Strategy()))
        assertNotNull(signal("A", closedBars(bars, day.atTime(9, 35)), Strategy()))
    }

    @Test fun gapThroughStopFillsWorse() {
        val bars = breakout(day, true)
        val plan = signal("A", bars, Strategy())!!
        val gap = bars.take(4) + Bar(bars[4].start, 95.0, 95.5, 94.0, 95.0, 1.0) + bars.drop(5)
        val o = outcome(gap, plan, 71)!!
        assertEquals("stop", o.result); assertEquals(95.0, o.exit)
    }

    @Test fun chargesMatchAHandCount() {
        val c = roundTrip(100, 500.0, 510.0, Rates(slippagePct = 0.0))
        assertEquals(15.0 + 15.3, c.brokerage, 1e-9)
        assertEquals(51_000 * 0.00025, c.stt, 1e-9)
        assertEquals(50_000 * 0.00003, c.stamp, 1e-9)
        assertEquals((30.3 + 101_000 * (0.0000297 + 0.000001)) * 0.18, c.gst, 1e-9)
        assertEquals(40.0, roundTrip(1000, 500.0, 510.0).brokerage, 1e-9) // capped at Rs 20 a side
    }

    @Test fun sizingKeepsTheAllInLossInsideTheLimit() {
        val lim = RiskLimits()
        val one = Money.size(signal("A", breakout(day, true), Strategy(targetR = 1.0))!!, lim)!!
        assertTrue(-one.lossAtStop <= lim.maxLossPerTrade)
        assertTrue(one.value <= lim.capital)
        assertTrue(one.rewardRisk < 0.8, "1R after Indian charges is well under 1: ${one.rewardRisk}")
        val two = Money.size(signal("A", breakout(day, true), Strategy(targetR = 2.0))!!, lim)!!
        val at = day.atTime(9, 40)
        assertTrue(Money.check(two, lim, emptyList(), at).all { it.ok })
        val bad = listOf(app.airbox.core.markets.JournalEntry(day.toString(), "X", "LONG", 1, 1.0, 1.0, 1.0, 0.0, 0.0, open = false, pnl = -2_900.0))
        assertFalse(Money.check(two, lim, bad, at).first { it.name == "Daily loss limit" }.ok)
        assertFalse(Money.check(two, lim, emptyList(), day.atTime(15, 0)).first { it.name == "Entry window" }.ok)
        assertEquals("1,50,000", Money.inr(150_000))
    }

    @Test fun timeMachineIsRepeatableAndHistoryIsReal() {
        val sess = weekdays(40, day).withIndex().associate { (k, d) -> d to breakout(d, k % 2 == 1) }
        val plan = signal("A", breakout(day, true), Strategy())!!
        val pool = TimeMachine.pool(sess, day)
        assertEquals(40, pool.size)
        val a = TimeMachine.replay(plan, pool, 71, 2_000)
        val b = TimeMachine.replay(plan, pool, 71, 2_000)
        assertEquals(a.target, b.target); assertEquals(1.0, a.target + a.stop + a.neither, 1e-9)
        val hist = TimeMachine.history("A", sess, day, Strategy())
        assertEquals(40, hist.size); assertEquals(20, hist.count { it.result == "target" })
        assertTrue("coin flip" in TimeMachine.verdict(0.4, 0.4, 1.0, 0.0))
        assertTrue("toward the stop" in TimeMachine.verdict(0.4, 0.4, 1.0, 0.25))
    }

    @Test fun portfolioStopsAtTheDailyLimit() {
        fun t(time: String, pnl: Double) = ProofTrade(day, "A", Side.LONG, time, 1.0, 1.0, "stop", 1, 0.0, pnl, pnl, 0.0, 1.0, 1.0)
        val taken = OrbProof.portfolio(listOf(t("09:35", -1600.0), t("09:40", -1600.0), t("09:45", 900.0), t("09:50", 900.0)), RiskLimits())
        assertEquals(2, taken.size)
        assertEquals(-3200.0, OrbProof.summary(taken)!!.pnlNet)
    }

    @Test fun momentumPicksSteadyClimbersAndGoesToCash() {
        val days = generateSequence(LocalDate.of(2023, 1, 2)) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(400).toList()
        fun series(drift: Double, seed: Int): List<DailyBar> {
            val rng = Random(seed); var p = 100.0
            return days.map { d -> p *= exp(drift + 0.003 * rng.nextDouble(-1.0, 1.0)); DailyBar(d, p, p, 1e6) }
        }
        val up = Panel.of(mapOf("UP1" to series(0.003, 1), "UP2" to series(0.002, 2), "FLAT" to series(0.0, 3), "DOWN" to series(-0.001, 4)))
        val t = up.days.size - 1
        assertEquals(setOf("UP1", "UP2"), Momentum.picks(up, t, Momentum.Rules(topN = 2, useRegime = false), null).toSet())
        assertEquals("below 200-day average", Momentum.scores(up, t, Momentum.Rules()).first { it.symbol == "DOWN" }.reason)
        val falling = Panel.of((1..8).associate { "S$it" to series(-0.002, it) } + ("UP" to series(0.003, 99)))
        val reg = Momentum.regime(Momentum.marketIndex(falling))
        assertFalse(reg.last())
        assertTrue(Momentum.picks(falling, falling.days.size - 1, Momentum.Rules(), reg).isEmpty())
        val bt = Momentum.backtest(up, Momentum.Rules(topN = 1, useRegime = false), cost = 0.0)
        assertTrue(Momentum.metrics(bt.days, bt.equity)!!.cagr > 0)
    }

    @Test fun yahooAndNseFormatsParse() {
        val spark = """{"spark":{"result":[{"symbol":"ABC.NS","response":[{"meta":{"regularMarketPrice":105.0,"chartPreviousClose":100.0,
            "regularMarketVolume":2000000},"timestamp":[1792467900,1792468200],"indicators":{"quote":[{"close":[101.0,105.0]}]}}]}]}}"""
        val q = YahooSource.parseSpark(spark).getValue("ABC").quote()!!
        assertEquals(0.05, q.changePct, 1e-9); assertEquals(2.1e8, q.tradedValue!!, 1e-3)
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 15), YahooSource.parseSpark(spark).getValue("ABC").times.first())
        val csv = "Company Name,Industry,Symbol,Series,ISIN Code\n\"Abc, Ltd.\",Capital Goods,ABC,EQ,INE1\nXyz Ltd.,IT,XYZ,EQ,INE2\n"
        assertEquals(listOf("ABC", "XYZ"), YahooSource.parseIndexCsv(csv).map { it.symbol })
        assertEquals("Abc, Ltd.", YahooSource.parseIndexCsv(csv).first().name)
        val rss = "<rss><channel><item><title>Abc wins &amp; grows</title><link>https://x/1</link><pubDate>Mon</pubDate></item></channel></rss>"
        assertEquals("Abc wins & grows", YahooSource.parseRss(rss).single().title)
    }

    /** Whole scan flow on a fake source: spark pre-filter, then full bars for candidates only. */
    @Test fun terminalScanFindsTheBreakoutAndSkipsTheRest() {
        val past = weekdays(5, day)
        val abc = past.flatMap { breakout(it, true) } + breakout(day, true)
        val qui = past.flatMap { quiet(it) } + quiet(day)
        val charts = mapOf("ABC" to abc, "QUIET" to qui)
        var fullFetches = 0
        val src = object : MarketSource {
            override fun universe(name: Universe) = listOf(Stock("ABC", "Abc Ltd", "Test"), Stock("QUIET", "Quiet Ltd", "Test"))
            override fun chart(symbol: String, range: String, interval: String): Chart { fullFetches++; return toChart(symbol, charts.getValue(symbol)) }
            override fun spark(symbols: List<String>, range: String, interval: String) = symbols.associateWith { toChart(it, charts.getValue(it)) }
            override fun news(symbol: String) = listOf(Headline("h", "l", null))
        }
        val scan = Terminal(src).scan(Strategy(), day.atTime(10, 0), minTradedCr = 0.01)
        assertEquals(listOf("ABC"), scan.breakouts.map { it.plan.symbol })
        assertTrue(fullFetches <= 2)
    }

    private fun toChart(sym: String, bars: List<Bar>): Chart {
        val epoch = { b: Bar -> b.start.atZone(Nse.IST).toEpochSecond() }
        return Chart(sym, bars.map { Nse.ist(epoch(it)) }, bars.map { it.open }, bars.map { it.high }, bars.map { it.low },
            bars.map { it.close }, bars.map { it.volume }, bars.map { it.close }, mapOf("regularMarketPrice" to bars.last().close))
    }

    @Test fun sessionsGroupByDay() {
        val two = quiet(day) + quiet(day.plusDays(1))
        assertEquals(2, sessions(two).size)
        assertTrue(sessions(two).values.all { isFull(it) })
    }
}
