package app.airbox.core.markets

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.floor
import kotlinx.serialization.Serializable

/** Your limits, in rupees. Leverage stays 1: intraday margin multiplies losses as much as gains. */
@Serializable
data class RiskLimits(
    val capital: Double = 150_000.0,
    val maxLossPerTrade: Double = 1_500.0,
    val dailyLossLimit: Double = 3_000.0,
    val maxTradesPerDay: Int = 3,
    val leverage: Double = 1.0,
    val minRewardRisk: Double = 1.0,
    val entryFrom: String = "09:30",
    val entryUntil: String = "14:30",
)

data class Order(
    val plan: Plan,
    val qty: Int,
    val value: Double,
    val marginNeeded: Double,
    val lossAtStop: Double, // negative
    val profitAtTarget: Double,
    val charges: Double,
    val slippage: Double,
) {
    val rewardRisk: Double get() = if (lossAtStop < 0) profitAtTarget / -lossAtStop else 0.0
}

/** A trade you took, for the daily loss limit. */
@Serializable
data class JournalEntry(
    val day: String,
    val symbol: String,
    val side: String,
    val qty: Int,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val lossAtStop: Double,
    val marginNeeded: Double,
    val open: Boolean = true,
    val exit: Double? = null,
    val pnl: Double? = null,
)

data class Check(val name: String, val ok: Boolean, val detail: String)

object Money {
    fun net(qty: Int, plan: Plan, exit: Double, r: Rates): Double {
        val long = plan.side == Side.LONG
        val gross = qty * (exit - plan.entry) * (if (long) 1 else -1)
        val c = if (long) roundTrip(qty, plan.entry, exit, r) else roundTrip(qty, exit, plan.entry, r)
        return gross - c.total
    }

    /** Shares so the all-in loss at the stop (charges and slippage too) fits the limit. */
    fun size(plan: Plan, lim: RiskLimits, r: Rates = Rates()): Order? {
        var qty = minOf(floor(lim.capital * lim.leverage / plan.entry), floor(lim.maxLossPerTrade / plan.risk)).toInt()
        while (qty > 0 && -net(qty, plan, plan.stop, r) > lim.maxLossPerTrade) qty--
        if (qty <= 0) return null
        val long = plan.side == Side.LONG
        val c = if (long) roundTrip(qty, plan.entry, plan.target, r) else roundTrip(qty, plan.target, plan.entry, r)
        return Order(plan, qty, qty * plan.entry, qty * plan.entry / lim.leverage, net(qty, plan, plan.stop, r),
            net(qty, plan, plan.target, r), c.fees, c.slippage)
    }

    fun check(order: Order, lim: RiskLimits, journal: List<JournalEntry>, now: LocalDateTime): List<Check> {
        val today = order.plan.day.toString()
        val todays = journal.filter { it.day == today }
        val realised = todays.filter { !it.open }.sumOf { it.pnl ?: 0.0 }
        val openRisk = todays.filter { it.open }.sumOf { -it.lossAtStop }
        val worst = realised - openRisk + order.lossAtStop
        val t = now.toLocalTime()
        val usedMargin = todays.filter { it.open }.sumOf { it.marginNeeded }
        return listOf(
            Check("Max loss per trade", -order.lossAtStop <= lim.maxLossPerTrade + 0.01,
                "${rs(-order.lossAtStop)} at the stop, limit ${rs(lim.maxLossPerTrade)}"),
            Check("Daily loss limit", -worst <= lim.dailyLossLimit + 0.01,
                "today ${rs(realised)}, open risk ${rs(openRisk)}; worst case ${rs(worst)}, limit -${rs(lim.dailyLossLimit)}"),
            Check("Trades per day", todays.size < lim.maxTradesPerDay, "${todays.size} today, limit ${lim.maxTradesPerDay}"),
            Check("Reward to risk", order.rewardRisk >= lim.minRewardRisk,
                "${"%.2f".format(order.rewardRisk)} after charges, need ${lim.minRewardRisk}"),
            Check("Entry window", !t.isBefore(Nse.hhmm(lim.entryFrom)) && !t.isAfter(Nse.hhmm(lim.entryUntil)),
                "now ${t.withSecond(0)}, allowed ${lim.entryFrom}-${lim.entryUntil} IST"),
            Check("Capital", order.marginNeeded <= lim.capital - usedMargin, "needs ${rs(order.marginNeeded)} of ${rs(lim.capital - usedMargin)} free"),
        )
    }

    fun rs(x: Double): String {
        val v = kotlin.math.abs(x).let { Math.round(it) }
        return (if (x < 0) "-" else "") + "Rs " + inr(v)
    }

    /** Indian grouping: 1,50,000. */
    fun inr(v: Long): String {
        val s = v.toString()
        if (s.length <= 3) return s
        val head = s.dropLast(3)
        return head.reversed().chunked(2).joinToString(",").reversed() + "," + s.takeLast(3)
    }

    fun journalDay(day: LocalDate, journal: List<JournalEntry>): Double =
        journal.filter { it.day == day.toString() && !it.open }.sumOf { it.pnl ?: 0.0 }
}
