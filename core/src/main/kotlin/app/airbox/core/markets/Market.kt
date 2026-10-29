package app.airbox.core.markets

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * NSE cash market, India time. A full day is 75 five-minute bars: the first starts 9:15,
 * the last 15:25. Days with fewer bars (holidays slipping in, Muhurat trading, data gaps)
 * are not "full" and are left out of anything statistical.
 */
object Nse {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    const val BAR_MIN = 5
    const val BARS_PER_DAY = 75
    val OPEN: LocalTime = LocalTime.of(9, 15)
    val CLOSE: LocalTime = LocalTime.of(15, 30)

    /** 0 for the 9:15 bar, 74 for the 15:25 bar; negative before the open. */
    fun barIndex(t: LocalDateTime): Int = Math.floorDiv(t.hour * 60 + t.minute - (9 * 60 + 15), BAR_MIN)

    fun slot(day: LocalDate, index: Int): LocalDateTime = day.atTime(OPEN).plusMinutes(BAR_MIN.toLong() * index)

    fun now(): LocalDateTime = LocalDateTime.now(IST).truncatedTo(ChronoUnit.MINUTES)

    fun ist(epochSeconds: Long): LocalDateTime =
        Instant.ofEpochSecond(epochSeconds).atZone(IST).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)

    /** Weekday 9:15 to 15:30. Exchange holidays are not known here; the data shows them. */
    fun isOpen(now: LocalDateTime = now()): Boolean =
        now.dayOfWeek.value <= 5 && !now.toLocalTime().isBefore(OPEN) && now.toLocalTime().isBefore(CLOSE)

    fun hhmm(s: String): LocalTime = LocalTime.parse(s.trim().padStart(5, '0'))
}

data class Bar(
    val start: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
) {
    val end: LocalDateTime get() = start.plusMinutes(Nse.BAR_MIN.toLong())
    val index: Int get() = Nse.barIndex(start)
}

data class DailyBar(val day: LocalDate, val close: Double, val adjClose: Double, val volume: Double)

data class Stock(val symbol: String, val name: String, val industry: String)

/** Live snapshot of one stock, plus a small price series for a sparkline. */
data class Quote(
    val symbol: String,
    val price: Double,
    val previousClose: Double,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Double?,
    val high52: Double?,
    val low52: Double?,
    val series: List<Double>,
    val time: LocalDateTime?,
) {
    val change: Double get() = price - previousClose
    val changePct: Double get() = if (previousClose > 0) change / previousClose else 0.0
    val tradedValue: Double? get() = volume?.let { it * price }
}

data class Headline(val title: String, val link: String, val published: String?)

/** Bars grouped by day, in time order, one bar per 5-minute slot inside market hours. */
fun sessions(bars: List<Bar>): Map<LocalDate, List<Bar>> =
    bars.filter { it.index in 0 until Nse.BARS_PER_DAY }
        .groupBy { it.start.toLocalDate() }
        .toSortedMap()
        .mapValues { (_, b) -> b.associateBy { it.index }.toSortedMap().values.toList() }

fun isFull(day: List<Bar>): Boolean = day.size == Nse.BARS_PER_DAY && day.withIndex().all { (i, b) -> b.index == i }

/** Only bars that have finished by [now]: a bar still forming is never used. */
fun closedBars(day: List<Bar>, now: LocalDateTime): List<Bar> = day.filter { !it.end.isAfter(now) }
