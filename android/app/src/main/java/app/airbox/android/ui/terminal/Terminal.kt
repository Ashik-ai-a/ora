package app.airbox.android.ui.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.airbox.android.platform.MarketsState
import app.airbox.android.platform.MarketsStore
import app.airbox.core.markets.SavedIntraday
import app.airbox.android.platform.Task
import app.airbox.core.markets.Bar
import app.airbox.core.markets.Money
import app.airbox.core.markets.Nse
import app.airbox.core.markets.Plan
import app.airbox.core.markets.Quote
import app.airbox.core.markets.RiskLimits
import app.airbox.core.markets.Side
import app.airbox.core.markets.Terminal
import java.util.Locale
import kotlin.math.floor
import kotlinx.coroutines.delay

/** Terminal palette: black, amber, and green/red only where money moves. */
object T {
    val bg = Color(0xFF000000)
    val panel = Color(0xFF0C0C0C)
    val line = Color(0xFF2A2A2A)
    val amber = Color(0xFFFFA028)
    val amberDim = Color(0xFF8F5E17)
    val text = Color(0xFFE6E6E6)
    val dim = Color(0xFF8C8C8C)
    val green = Color(0xFF2BD96B)
    val red = Color(0xFFFF4D4D)
    val cyan = Color(0xFF5BC8FF)
    val keyBg = Color(0xFFFFB000)
}

val Mono = FontFamily.Monospace
fun mono(size: Int = 13, color: Color = T.text, bold: Boolean = false) =
    TextStyle(fontFamily = Mono, fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, lineHeight = (size + 4).sp)

private fun f(x: Double, d: Int = 2) = String.format(Locale.US, "%,.${d}f", x)
private fun pct(x: Double, d: Int = 1) = String.format(Locale.US, "%+.${d}f%%", x * 100)
private fun pct0(x: Double) = String.format(Locale.US, "%.0f%%", x * 100)
private fun upDown(x: Double) = if (x > 0) T.green else if (x < 0) T.red else T.text

enum class Fn(val key: String, val label: String) {
    MON("F1", "MONITOR"), MOM("F2", "MOMENTUM"), ORB("F3", "INTRADAY"), PROOF("F4", "PROOF"), RISK("F5", "RISK"), SHARE("F6", "SHARE")
}

/** Everything above the content: brand, clock, market status, function keys and the command line. */
@Composable
fun TerminalChrome(tab: Fn, onTab: (Fn) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val st by MarketsStore.flow.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(T.bg).safeDrawingPadding()) {
        TopBar(st)
        FunctionKeys(tab, onTab)
        if (tab != Fn.SHARE) CommandLine { MarketsStore.openDetail(it) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) { content() }
            if (tab != Fn.SHARE && (st.detail.running || st.detail.result != null || st.detail.error != null)) {
                BackHandler { MarketsStore.closeDetail() }
                DetailScreen(st)
            }
        }
    }
}

@Composable
private fun TopBar(st: MarketsState) {
    val now by produceState(Nse.now()) { while (true) { value = java.time.LocalDateTime.now(Nse.IST); delay(1000) } }
    val open = Nse.isOpen(now)
    val invested = st.momentum.result?.invested
    Row(Modifier.fillMaxWidth().background(T.panel).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("AIRBOX", style = mono(15, T.amber, bold = true))
        Text("  NSE·MID/SMALL", style = mono(11, T.dim))
        Spacer(Modifier.weight(1f))
        if (invested != null) Text(if (invested) "RISK-ON " else "RISK-OFF ", style = mono(11, if (invested) T.green else T.red, bold = true))
        Text(if (open) "● OPEN " else "● CLOSED ", style = mono(11, if (open) T.green else T.red, bold = true))
        Text(String.format(Locale.US, "%02d:%02d:%02d IST", now.hour, now.minute, now.second), style = mono(12, T.amber))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(T.amberDim))
}

@Composable
private fun FunctionKeys(tab: Fn, onTab: (Fn) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Fn.entries.forEach { fn ->
            val on = fn == tab
            Row(Modifier.background(if (on) T.keyBg else T.panel).border(1.dp, if (on) T.keyBg else T.line)
                .clickable { onTab(fn) }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                Text(fn.key + " ", style = mono(11, if (on) Color.Black else T.amber, bold = true))
                Text(fn.label, style = mono(11, if (on) Color.Black else T.text, bold = on))
            }
        }
    }
}

@Composable
private fun CommandLine(onGo: (String) -> Unit) {
    var cmd by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).border(1.dp, T.line).background(T.panel).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("> ", style = mono(13, T.amber, bold = true))
        Box(Modifier.weight(1f)) {
            if (cmd.isEmpty()) Text("SYMBOL  e.g. KPITTECH", style = mono(13, T.dim))
            BasicTextField(cmd, { cmd = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '&' || c == '-' }.take(20) },
                textStyle = mono(13, T.amber), singleLine = true, cursorBrush = SolidColor(T.amber),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo(cmd); cmd = "" }), modifier = Modifier.fillMaxWidth())
        }
        Text(" GO ", style = mono(12, Color.Black, bold = true),
            modifier = Modifier.background(T.keyBg).clickable { onGo(cmd); cmd = "" }.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Cell(text: String, width: Dp, color: Color = T.text, end: Boolean = false, bold: Boolean = false, size: Int = 12) {
    Text(text, style = mono(size, color, bold), maxLines = 1, overflow = TextOverflow.Clip,
        textAlign = if (end) TextAlign.End else TextAlign.Start, modifier = Modifier.width(width))
}

@Composable
private fun Header(vararg cols: Pair<String, Dp>) {
    Row(Modifier.fillMaxWidth().background(T.panel).padding(horizontal = 8.dp, vertical = 4.dp)) {
        cols.forEachIndexed { i, (t, w) -> Cell(t, w, T.amber, end = i > 0, bold = true, size = 11) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(T.line))
}

@Composable
private fun Section(title: String, right: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = mono(12, T.amber, bold = true))
        Spacer(Modifier.weight(1f))
        if (right != null) Text(right, style = mono(11, T.dim))
    }
}

@Composable
private fun Key(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(" $label ", style = mono(12, if (enabled) Color.Black else T.dim, bold = true),
        modifier = Modifier.background(if (enabled) T.keyBg else T.line).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp))
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(" $label ", style = mono(11, if (on) Color.Black else T.amber, bold = on),
        modifier = Modifier.background(if (on) T.amber else T.bg).border(1.dp, T.amberDim).clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp))
}

@Composable
private fun Note(text: String, color: Color = T.dim) {
    Text(text, style = mono(11, color), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun <R> TaskLine(task: Task<R>, label: String) {
    when {
        task.running -> {
            val bar = (task.progress * 20).toInt().coerceIn(0, 20)
            Note("$label  [" + "█".repeat(bar) + "·".repeat(20 - bar) + "] " + (if (task.total > 0) "${task.done}/${task.total}" else "…"), T.amber)
        }
        task.error != null -> Note("ERROR  ${task.error}", T.red)
    }
}

@Composable
private fun Spark(values: List<Double>, modifier: Modifier, base: Double? = null) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = minOf(values.min(), base ?: values.min()); val hi = maxOf(values.max(), base ?: values.max())
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height * (1 - ((v - lo) / span)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (base != null) {
            val y = size.height * (1 - ((base - lo) / span)).toFloat()
            drawLine(T.line, Offset(0f, y), Offset(size.width, y), 1f)
        }
        drawPath(path, if (values.last() >= (base ?: values.first())) T.green else T.red, style = Stroke(2f))
    }
}

// ---------------------------------------------------------------- F1 MONITOR

private enum class Sort(val label: String) { GAIN("GAINERS"), LOSS("LOSERS"), VALUE("MOST TRADED") }

@Composable
fun MonitorScreen() {
    val st by MarketsStore.flow.collectAsStateWithLifecycle()
    var sort by remember { mutableStateOf(Sort.GAIN) }
    LaunchedEffect(Unit) {
        while (true) {
            val last = st.monitor.finishedAt
            if (last == null || (Nse.isOpen() && System.currentTimeMillis() - last > 60_000)) MarketsStore.refreshMonitor()
            delay(15_000)
        }
    }
    val quotes = st.monitor.result.orEmpty()
    val rows = when (sort) {
        Sort.GAIN -> quotes.sortedByDescending { it.changePct }
        Sort.LOSS -> quotes.sortedBy { it.changePct }
        Sort.VALUE -> quotes.sortedByDescending { it.tradedValue ?: 0.0 }
    }
    val adv = quotes.count { it.changePct > 0 }; val dec = quotes.count { it.changePct < 0 }
    val median = quotes.map { it.changePct }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("ADV ", style = mono(12, T.dim)); Text("$adv", style = mono(12, T.green, true))
        Text("  DEC ", style = mono(12, T.dim)); Text("$dec", style = mono(12, T.red, true))
        Text("  MEDIAN ", style = mono(12, T.dim)); Text(pct(median), style = mono(12, upDown(median), true))
        Spacer(Modifier.weight(1f))
        Key("REFRESH", !st.monitor.running) { MarketsStore.refreshMonitor() }
    }
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Sort.entries.forEach { Chip(it.label, it == sort) { sort = it } }
    }
    TaskLine(st.monitor, "LOADING 400 MID & SMALL CAPS")
    Spacer(Modifier.height(4.dp))
    Header("TICKER" to 96.dp, "LAST" to 76.dp, "CHG%" to 62.dp, "₹CR" to 50.dp)
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.symbol }) { q -> QuoteRow(q) }
        item { Note("Yahoo Finance, free and unofficial: prices can lag a few minutes. Refreshes every minute while NSE is open.") }
    }
}

@Composable
private fun QuoteRow(q: Quote) {
    Row(Modifier.fillMaxWidth().clickable { MarketsStore.openDetail(q.symbol) }.padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Cell(q.symbol, 96.dp, T.amber, bold = true)
        Cell(f(q.price), 76.dp, end = true)
        Cell(pct(q.changePct), 62.dp, upDown(q.changePct), end = true, bold = true)
        Cell(q.tradedValue?.let { f(it / 1e7, 0) } ?: "-", 50.dp, T.dim, end = true)
        Spacer(Modifier.width(8.dp))
        Spark(q.series, Modifier.weight(1f).height(18.dp), q.previousClose)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(T.panel))
}

// ---------------------------------------------------------------- F2 MOMENTUM

@Composable
fun MomentumScreen() {
    val st by MarketsStore.flow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (st.momentum.result == null && !st.momentum.running) MarketsStore.loadMomentum() }
    val m = st.momentum.result
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MONTHLY MOMENTUM · TOP 10", style = mono(13, T.amber, true))
                    Text("6/12-month strength ÷ volatility, above 200-day avg, ≥₹5 cr/day, no pumps", style = mono(10, T.dim))
                }
                Key("RUN", !st.momentum.running) { MarketsStore.loadMomentum() }
            }
            TaskLine(st.momentum, "RANKING")
        }
        if (m != null) {
            item {
                val color = if (m.invested) T.green else T.red
                Text(if (m.invested) " REGIME: RISK-ON · RULES SAY INVEST " else " REGIME: RISK-OFF · RULES SAY HOLD CASH ",
                    style = mono(12, Color.Black, true), modifier = Modifier.padding(horizontal = 8.dp).background(color).padding(4.dp))
                Note("Mid & small caps as a group are ${if (m.invested) "above" else "below"} their 200-day average. Data to ${m.asOf}.")
                val slice = st.limits.capital / 10
                Note("${Money.rs(st.limits.capital)} split 10 ways: ${Money.rs(slice)} each (whole shares).")
                Spacer(Modifier.height(4.dp))
                Header("#  TICKER" to 112.dp, "PRICE" to 72.dp, "6M" to 58.dp, "12M" to 58.dp, "QTY" to 44.dp)
            }
            val ranked = m.ranked.filter { it.eligible }.take(10)
            items(ranked.withIndex().toList(), key = { it.value.symbol }) { (i, s) ->
                val qty = if (m.invested) floor(st.limits.capital / 10 / s.price).toInt() else 0
                Row(Modifier.fillMaxWidth().clickable { MarketsStore.openDetail(s.symbol) }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Cell(String.format(Locale.US, "%-2d ", i + 1) + s.symbol, 112.dp, T.amber, bold = true)
                    Cell(f(s.price), 72.dp, end = true)
                    Cell(pct(s.ret6m, 0), 58.dp, upDown(s.ret6m), end = true)
                    Cell(pct(s.ret12m, 0), 58.dp, upDown(s.ret12m), end = true)
                    Cell(if (qty > 0) "$qty" else "-", 44.dp, T.text, end = true)
                }
            }
            item {
                Note("Left out: " + m.left.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" })
                Note("HOW: buy the list at the start of a month, hold it, swap only at the next monthly scan. If the regime says cash, sell and wait. " +
                    "Check each company's results on NSE/BSE first. Not investment advice.")
            }
        }
    }
}

// ---------------------------------------------------------------- F3 INTRADAY

@Composable
fun IntradayScreen() {
    val st by MarketsStore.flow.collectAsStateWithLifecycle()
    val s = st.strategy
    val now = Nse.now()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OPENING-RANGE BREAKOUT", style = mono(13, T.amber, true))
                    Text("15-min range · first 5-min close beyond it · out by ${s.flatBy}", style = mono(10, T.dim))
                }
                Key("SCAN", !st.scan.running) { MarketsStore.runScan() }
            }
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TARGET", style = mono(11, T.dim))
                listOf(1.0, 1.5, 2.0).forEach { r -> Chip("${if (r % 1.0 == 0.0) r.toInt() else r}R", s.targetR == r) { MarketsStore.setStrategy(s.copy(targetR = r)) } }
                Text(" SHORTS", style = mono(11, T.dim))
                Chip(if (s.shorts) "ON" else "OFF", s.shorts) { MarketsStore.setStrategy(s.copy(shorts = !s.shorts)) }
            }
            if (now.toLocalTime().isBefore(java.time.LocalTime.of(9, 30)) && now.dayOfWeek.value <= 5)
                Note("The opening range is set at 9:30. Scan after that.", T.amber)
            TaskLine(st.scan, "SCANNING")
        }
        st.scan.result?.let { scan ->
            item {
                Note("${scan.breakouts.size} breakouts in ${scan.checked} stocks as of ${scan.asOf.toLocalTime()} IST (≥₹10 cr/day)")
                Header("TIME TICKER" to 118.dp, "ENTRY" to 66.dp, "STOP" to 62.dp, "TGT" to 62.dp, "RV" to 36.dp)
            }
            items(scan.breakouts, key = { it.plan.symbol }) { b -> BreakoutRow(b) }
            if (scan.near.isNotEmpty()) item { Note("NEAR THE TOP: " + scan.near.joinToString("  ") { "${it.first} ${f(it.second)}" }) }
            item {
                Note("A breakout is a setup, not a reason to trade. Tap one, run the TIME MACHINE, and see F4 PROOF for how this rule did " +
                    "after charges. Place orders yourself in your broker app (MIS, with a stop-loss).")
            }
        }
    }
}

@Composable
private fun BreakoutRow(b: Terminal.Breakout) {
    val p = b.plan
    Row(Modifier.fillMaxWidth().clickable { MarketsStore.openDetail(p.symbol) }.padding(horizontal = 8.dp, vertical = 5.dp)) {
        Cell("${p.entryTime} ${p.symbol}", 118.dp, if (p.side == Side.LONG) T.green else T.red, bold = true)
        Cell(f(p.entry), 66.dp, end = true)
        Cell(f(p.stop), 62.dp, T.red, end = true)
        Cell(f(p.target), 62.dp, T.green, end = true)
        Cell(String.format(Locale.US, "%.1f", b.relVolume), 36.dp, if (b.relVolume >= 1.5) T.amber else T.dim, end = true)
    }
}

// ---------------------------------------------------------------- symbol detail + Time Machine

@Composable
private fun DetailScreen(st: MarketsState) {
    val d = st.detail.result
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().background(T.bg).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().background(T.panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d?.symbol ?: "…", style = mono(16, T.amber, true))
            Text("  " + (d?.name ?: ""), style = mono(11, T.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Key("CLOSE") { MarketsStore.closeDetail() }
        }
        TaskLine(st.detail, "LOADING")
        if (d == null) return@Column
        val last = d.bars.lastOrNull()?.close ?: d.daily.lastOrNull()?.close
        val prev = d.daily.dropLast(if (d.daily.lastOrNull()?.day == d.bars.lastOrNull()?.start?.toLocalDate()) 1 else 0).lastOrNull()?.close
        if (last != null) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
            Text(f(last), style = mono(22, T.text, true))
            if (prev != null) Text("  " + f(last - prev) + "  " + pct(last / prev - 1, 2), style = mono(13, upDown(last - prev), true))
        }
        if (d.daily.size > 20) {
            val yr = d.daily.takeLast(250)
            Note("52W  ${f(yr.minOf { it.close })} – ${f(yr.maxOf { it.close })}   " +
                "200D AVG ${f(yr.takeLast(200).map { it.close }.average())}")
        }
        Section("INTRADAY 5-MIN", d.bars.lastOrNull()?.start?.toLocalDate()?.toString())
        IntradayChart(d.bars, d.plan, Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 8.dp))
        val plan = d.plan
        if (plan != null) {
            Section("PLAN · ${plan.side} BREAKOUT ${plan.entryTime}")
            Note("ENTRY ${f(plan.entry)}   STOP ${f(plan.stop)}   TARGET ${f(plan.target)} (${plan.targetR}R)", T.text)
            Note("Risk ${f(plan.risk)}/share · range ${f(plan.rangeLow)}–${f(plan.rangeHigh)} · out by ${plan.flatBy} either way")
            Row(Modifier.padding(8.dp)) { Key(if (st.machine.running) "REPLAYING…" else "TIME MACHINE", !st.machine.running) { MarketsStore.runTimeMachine(plan) } }
            TaskLine(st.machine, "10,000 REPLAYS")
            st.machine.result?.takeIf { it.plan == plan }?.let { MachineView(it, st) }
        } else Note("No opening-range breakout today (or the range was wider than 3%).")
        if (d.daily.size > 30) {
            Section("1 YEAR DAILY")
            Spark(d.daily.takeLast(250).map { it.close }, Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 8.dp))
        }
        Section("NEWS", "Yahoo Finance")
        if (d.news.isEmpty()) Note("No headlines found.")
        d.news.forEach { h ->
            Column(Modifier.fillMaxWidth().clickable(enabled = h.link.startsWith("https://")) { runCatching { uri.openUri(h.link) } }
                .padding(horizontal = 8.dp, vertical = 5.dp)) {
                Text(h.title, style = mono(12, T.text))
                h.published?.let { Text(it, style = mono(10, T.dim)) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun IntradayChart(bars: List<Bar>, plan: Plan?, modifier: Modifier) {
    Canvas(modifier.background(T.panel)) {
        if (bars.size < 2) return@Canvas
        val levels = bars.flatMap { listOf(it.high, it.low) } + listOfNotNull(plan?.stop, plan?.target)
        val lo = levels.min(); val hi = levels.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat()
        val w = size.width / Nse.BARS_PER_DAY
        plan?.let { p ->
            drawRect(T.amber.copy(alpha = 0.12f), Offset(0f, y(p.rangeHigh)), Size(w * 3, y(p.rangeLow) - y(p.rangeHigh)))
            drawLine(T.green, Offset(0f, y(p.target)), Offset(size.width, y(p.target)), 1.5f)
            drawLine(T.red, Offset(0f, y(p.stop)), Offset(size.width, y(p.stop)), 1.5f)
            drawLine(T.amber, Offset(0f, y(p.entry)), Offset(size.width, y(p.entry)), 1f)
        }
        bars.forEach { b ->
            val x = b.index * w + w / 2
            val c = if (b.close >= b.open) T.green else T.red
            drawLine(c, Offset(x, y(b.high)), Offset(x, y(b.low)), 1f)
            val top = y(maxOf(b.open, b.close)); val bot = y(minOf(b.open, b.close))
            drawRect(c, Offset(x - w * 0.35f, top), Size(w * 0.7f, (bot - top).coerceAtLeast(1f)))
        }
    }
}

@Composable
private fun MachineView(m: Terminal.Machine, st: MarketsState) {
    val r = m.replay
    Section("TIME MACHINE · ${f(r.replays.toDouble(), 0)} REPLAYS · SEED ${r.seed}", "${m.sessions} sessions")
    Row(Modifier.padding(horizontal = 8.dp)) {
        Odds("TARGET", r.target, T.green); Odds("STOP", r.stop, T.red); Odds("OPEN@${m.plan.flatBy}", r.neither, T.dim)
    }
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(8.dp).background(T.panel)) {
        val fan = r.fan
        if (fan.size < 2) return@Canvas
        val lo = minOf(fan.minOf { it[1] }, m.plan.stop, m.plan.target); val hi = maxOf(fan.maxOf { it[5] }, m.plan.stop, m.plan.target)
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height * (1 - (v - lo) / span)).toFloat()
        fun x(i: Int) = size.width * i / (fan.size - 1)
        for (band in listOf(1 to 5, 2 to 4)) {
            val path = Path()
            fan.indices.forEach { i -> if (i == 0) path.moveTo(x(i), y(fan[i][band.first])) else path.lineTo(x(i), y(fan[i][band.first])) }
            fan.indices.reversed().forEach { i -> path.lineTo(x(i), y(fan[i][band.second])) }
            path.close()
            drawPath(path, T.amber.copy(alpha = if (band.first == 1) 0.15f else 0.3f))
        }
        val med = Path(); fan.indices.forEach { i -> if (i == 0) med.moveTo(x(i), y(fan[i][3])) else med.lineTo(x(i), y(fan[i][3])) }
        drawPath(med, T.amber, style = Stroke(2f))
        drawLine(T.green, Offset(0f, y(m.plan.target)), Offset(size.width, y(m.plan.target)), 1.5f)
        drawLine(T.red, Offset(0f, y(m.plan.stop)), Offset(size.width, y(m.plan.stop)), 1.5f)
    }
    Note("Exit price: median ${f(r.exitMedian)}, 5%–95% ${f(r.exitP5)}–${f(r.exitP95)}")
    Note("Average ${String.format(Locale.US, "%+.2f", r.avgR)}R before charges, ${String.format(Locale.US, "%+.2f", r.avgR - m.costR)}R after " +
        "(charges = ${String.format(Locale.US, "%.2f", m.costR)}R)", T.text)
    val h = m.history
    if (h.isNotEmpty()) Note("HISTORY: rule fired on ${h.size} of ${m.sessions} past days: ${h.count { it.result == "target" }} target, " +
        "${h.count { it.result == "stop" }} stop, ${h.count { it.result == "flat" }} out at ${m.plan.flatBy}; " +
        String.format(Locale.US, "avg %+.2fR before charges", h.map { it.r }.average()))
    Text(" VERDICT ", style = mono(11, Color.Black, true), modifier = Modifier.padding(start = 8.dp, top = 6.dp).background(T.amber))
    Note(m.verdict, T.amber)
    Note("Built from this stock's last ~60 days. Not a prediction: results, news and circuit limits can do what those days never did.")
    m.order?.let { o -> OrderTicket(o, st) } ?: Note("One share risks more than your max loss per trade: no trade.", T.red)
}

@Composable
private fun Odds(label: String, p: Double, color: Color) {
    Column(Modifier.padding(end = 16.dp)) {
        Text(pct0(p), style = mono(18, color, true))
        Text(label, style = mono(10, T.dim))
    }
}

@Composable
private fun OrderTicket(o: app.airbox.core.markets.Order, st: MarketsState) {
    val p = o.plan
    Section("ORDER TICKET · INTRADAY (MIS)")
    Note("${if (p.side == Side.LONG) "BUY" else "SELL (SHORT)"} ${o.qty} ${p.symbol} @ ~${f(p.entry)} = ${Money.rs(o.value)}", T.text)
    Note("STOP-LOSS ${f(p.stop)}: you lose ${Money.rs(-o.lossAtStop)} all in (limit ${Money.rs(st.limits.maxLossPerTrade)})", T.red)
    Note("TARGET ${f(p.target)}: you make ${Money.rs(o.profitAtTarget)} after charges", T.green)
    Note("Charges ${Money.rs(o.charges)} + slippage ${Money.rs(o.slippage)} · reward/risk after costs ${String.format(Locale.US, "%.2f", o.rewardRisk)}")
    val checks = app.airbox.core.markets.Money.check(o, st.limits, st.journal, Nse.now())
    Section("RISK CHECK")
    checks.forEach { c ->
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(if (c.ok) "PASS " else "BLOCK", style = mono(11, if (c.ok) T.green else T.red, true), modifier = Modifier.width(48.dp))
            Text("${c.name}: ${c.detail}", style = mono(11, T.text))
        }
    }
    val pass = checks.all { it.ok }
    Text(if (pass) " PASS: EVERY LIMIT HOLDS " else " BLOCK: DO NOT TAKE THIS TRADE ",
        style = mono(12, Color.Black, true), modifier = Modifier.padding(8.dp).background(if (pass) T.green else T.red).padding(3.dp))
    if (pass) Row(Modifier.padding(horizontal = 8.dp)) {
        Key("I PLACED IT") { MarketsStore.recordTrade(o) }
        Text("  logs it for your daily loss limit", style = mono(10, T.dim), modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------------------------------------------------------- F4 PROOF

@Composable
fun ProofScreen() {
    val st by MarketsStore.flow.collectAsStateWithLifecycle()
    val pr = st.proofs
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("DOES IT MAKE MONEY? TESTED ON REAL DATA, AFTER CHARGES")
            Note("Runs on this phone (Yahoo blocks cloud servers). Use Wi-Fi: each test downloads 25–35 MB.", T.amber)
        }
        item {
            Section("A · INTRADAY OPENING-RANGE BREAKOUT", pr.intradayAt?.let { "run ${ago(it)}" })
            Note("100 most-traded mid & small caps, last ~60 days of 5-min bars, your limits (${st.limits.maxTradesPerDay} trades/day, " +
                "stop the day at -${Money.rs(st.limits.dailyLossLimit)}), brokerage+STT+exchange+SEBI+stamp+GST+0.05% slippage.")
            Row(Modifier.padding(8.dp)) { Key(if (st.intradayProof.running) "RUNNING…" else "RUN INTRADAY TEST", !st.intradayProof.running) { MarketsStore.runIntradayProof() } }
            TaskLine(st.intradayProof, "DOWNLOADING")
        }
        pr.intraday?.let { list ->
            item {
                Header("TARGET" to 64.dp, "TRADES" to 58.dp, "WIN%" to 50.dp, "R NET" to 56.dp, "NET ₹" to 84.dp)
                list.forEach { IntradayRow(it) }
                val best = list.maxByOrNull { it.taken?.pnlNet ?: Double.NEGATIVE_INFINITY }
                val anyProfit = list.any { (it.taken?.pnlNet ?: 0.0) > 0 }
                Text(" VERDICT ", style = mono(11, Color.Black, true), modifier = Modifier.padding(start = 8.dp, top = 8.dp).background(T.amber))
                Note(if (!anyProfit) "LOST MONEY after charges at every target. Do not day trade this rule with real money."
                     else "Best: ${best?.targetR}R made ${Money.rs(best?.taken?.pnlNet ?: 0.0)} after charges over ${best?.taken?.days} days " +
                        "(worst drop ${Money.rs(best?.taken?.maxDrawdown ?: 0.0)}). 60 days is weak evidence: paper trade it for a month first.",
                    if (anyProfit) T.amber else T.red)
            }
        }
        item {
            Section("B · MONTHLY MOMENTUM (F2)", pr.momentumAt?.let { "run ${ago(it)}" })
            Note("All 400 mid & small caps, 5 years of adjusted daily prices, top 10 monthly, 0.25% cost per trade.")
            Row(Modifier.padding(8.dp)) { Key(if (st.momentumProof.running) "RUNNING…" else "RUN MOMENTUM TEST", !st.momentumProof.running) { MarketsStore.runMomentumProof() } }
            TaskLine(st.momentumProof, "DOWNLOADING")
        }
        pr.momentum?.let { mo ->
            item {
                Header("" to 120.dp, "CAGR" to 62.dp, "WORST" to 62.dp, "₹1L→" to 80.dp)
                MetricRow("STRATEGY", mo.metrics); MetricRow("ALL ${mo.stocks} EW", mo.universe)
                Section("YEAR BY YEAR")
                Note(mo.yearly.joinToString("  ") { "${it.year} ${pct(it.ret, 0)}" }, T.text)
                Section("OTHER SETTINGS (NOT TUNED)")
                mo.grid.forEach { g -> MetricRow("TOP ${g.topN}${if (g.regime) " +CASH" else ""}", g.metrics) }
                val s = mo.metrics; val u = mo.universe
                Text(" VERDICT ", style = mono(11, Color.Black, true), modifier = Modifier.padding(start = 8.dp, top = 8.dp).background(T.amber))
                Note(when {
                    s == null || u == null -> "Not enough data."
                    s.cagr > u.cagr && s.maxDrawdown >= u.maxDrawdown - 0.05 -> "Beat holding every mid & small cap, without deeper falls."
                    s.cagr > u.cagr -> "Beat holding every mid & small cap, but with deeper falls."
                    else -> "Did NOT beat simply holding all mid & small caps. Prefer an index fund."
                }, T.amber)
                Note("Uses today's index members, so companies that collapsed are missing: real results are worse than this test (survivorship bias). " +
                    "Not investment advice.")
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun IntradayRow(it: SavedIntraday) {
    val t = it.taken
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Cell("${it.targetR}R", 64.dp, T.amber, bold = true)
        Cell("${t?.trades ?: 0}", 58.dp, end = true)
        Cell(t?.let { x -> pct0(x.winRate) } ?: "-", 50.dp, end = true)
        Cell(t?.let { x -> String.format(Locale.US, "%+.2f", x.avgRNet) } ?: "-", 56.dp, t?.let { x -> upDown(x.avgRNet) } ?: T.dim, end = true)
        Cell(t?.let { x -> Money.rs(x.pnlNet).removePrefix("Rs ") } ?: "-", 84.dp, t?.let { x -> upDown(x.pnlNet) } ?: T.dim, end = true, bold = true)
    }
}

@Composable
private fun MetricRow(label: String, m: app.airbox.core.markets.Momentum.Metrics?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Cell(label, 120.dp, T.amber, bold = true)
        Cell(m?.let { pct(it.cagr) } ?: "-", 62.dp, m?.let { upDown(it.cagr) } ?: T.dim, end = true)
        Cell(m?.let { pct(it.maxDrawdown, 0) } ?: "-", 62.dp, T.red, end = true)
        Cell(m?.let { Money.inr(it.growthOf1L.toLong()) } ?: "-", 80.dp, end = true)
    }
}

private fun ago(t: Long): String {
    val min = (System.currentTimeMillis() - t) / 60_000
    return when { min < 1 -> "just now"; min < 60 -> "${min}m ago"; min < 1440 -> "${min / 60}h ago"; else -> "${min / 1440}d ago" }
}

// ---------------------------------------------------------------- F5 RISK

@Composable
fun RiskScreen() {
    val st by MarketsStore.flow.collectAsStateWithLifecycle()
    val l = st.limits
    var capital by remember(l) { mutableStateOf(l.capital.toLong().toString()) }
    var maxLoss by remember(l) { mutableStateOf(l.maxLossPerTrade.toLong().toString()) }
    var daily by remember(l) { mutableStateOf(l.dailyLossLimit.toLong().toString()) }
    var trades by remember(l) { mutableStateOf(l.maxTradesPerDay.toString()) }
    val today = MarketsStore.today()
    val todays = st.journal.filter { it.day == today }
    val realised = todays.filter { !it.open }.sumOf { it.pnl ?: 0.0 }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("TODAY", today)
            Row(Modifier.padding(horizontal = 8.dp)) {
                Text("REALISED ", style = mono(12, T.dim)); Text(Money.rs(realised), style = mono(12, upDown(realised), true))
                Text("   TRADES ${todays.size}/${l.maxTradesPerDay}", style = mono(12, T.dim))
            }
            val left = l.dailyLossLimit + realised.coerceAtMost(0.0)
            Note(if (left <= 0) "DAILY LOSS LIMIT HIT. THE DAY IS OVER." else "Room before the daily limit: ${Money.rs(left)}",
                if (left <= 0) T.red else T.text)
            Section("YOUR LIMITS")
            NumberField("CAPITAL ₹", capital) { capital = it }
            NumberField("MAX LOSS / TRADE ₹", maxLoss) { maxLoss = it }
            NumberField("DAILY LOSS LIMIT ₹", daily) { daily = it }
            NumberField("TRADES / DAY", trades) { trades = it }
            Row(Modifier.padding(8.dp)) {
                Key("SAVE") {
                    MarketsStore.setLimits(RiskLimits(capital = capital.toDoubleOrNull() ?: l.capital,
                        maxLossPerTrade = maxLoss.toDoubleOrNull() ?: l.maxLossPerTrade, dailyLossLimit = daily.toDoubleOrNull() ?: l.dailyLossLimit,
                        maxTradesPerDay = trades.toIntOrNull() ?: l.maxTradesPerDay))
                }
            }
            Note("A common rule: risk 1% of capital a trade and stop for the day at 2%. Leverage stays off.")
            Section("JOURNAL")
            if (st.journal.isEmpty()) Note("No trades yet. Tap I PLACED IT on an order ticket after you place it in your broker app.")
        }
        items(st.journal.reversed()) { e -> JournalRow(e) }
    }
}

@Composable
private fun JournalRow(e: app.airbox.core.markets.JournalEntry) {
    var exit by remember(e) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp).border(1.dp, T.panel).padding(6.dp)) {
        Row {
            Text("${e.day} ${e.symbol} ${e.side} ${e.qty} @ ${f(e.entry)}", style = mono(12, T.amber, true), modifier = Modifier.weight(1f))
            e.pnl?.let { Text(Money.rs(it), style = mono(12, upDown(it), true)) }
        }
        Text("stop ${f(e.stop)} · target ${f(e.target)}" + (e.exit?.let { " · out ${f(it)}" } ?: ""), style = mono(11, T.dim))
        if (e.open) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(110.dp).border(1.dp, T.line).padding(4.dp)) {
                if (exit.isEmpty()) Text("exit price", style = mono(12, T.dim))
                BasicTextField(exit, { exit = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, textStyle = mono(12, T.amber),
                    singleLine = true, cursorBrush = SolidColor(T.amber), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Spacer(Modifier.width(8.dp))
            Key("CLOSE TRADE", exit.toDoubleOrNull() != null) { exit.toDoubleOrNull()?.let { MarketsStore.closeTrade(e, it) } }
            Spacer(Modifier.width(8.dp))
            Text("DELETE", style = mono(11, T.red), modifier = Modifier.clickable { MarketsStore.deleteTrade(e) })
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = mono(12, T.dim), modifier = Modifier.width(170.dp))
        Box(Modifier.weight(1f).border(1.dp, T.line).padding(5.dp)) {
            BasicTextField(value, { onChange(it.filter { c -> c.isDigit() }.take(9)) }, textStyle = mono(13, T.amber), singleLine = true,
                cursorBrush = SolidColor(T.amber), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }
}
