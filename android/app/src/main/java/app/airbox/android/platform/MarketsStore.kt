package app.airbox.android.platform

import android.content.Context
import app.airbox.core.markets.JournalEntry
import app.airbox.core.markets.Momentum
import app.airbox.core.markets.Money
import app.airbox.core.markets.Nse
import app.airbox.core.markets.Plan
import app.airbox.core.markets.ProofSummary
import app.airbox.core.markets.Quote
import app.airbox.core.markets.RiskLimits
import app.airbox.core.markets.SavedGrid
import app.airbox.core.markets.SavedIntraday
import app.airbox.core.markets.SavedMomentum
import app.airbox.core.markets.SavedProofs
import app.airbox.core.markets.SavedYear
import app.airbox.core.markets.Strategy
import app.airbox.core.markets.Terminal
import app.airbox.core.markets.YahooSource
import app.airbox.core.markets.roundTrip
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A long job's state: running with progress, finished with a result, or failed. */
data class Task<T>(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val result: T? = null,
    val error: String? = null,
    val finishedAt: Long? = null,
) {
    val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
}

data class Detail(
    val symbol: String,
    val name: String,
    val bars: List<app.airbox.core.markets.Bar>,
    val plan: Plan?,
    val daily: List<app.airbox.core.markets.DailyBar>,
    val news: List<app.airbox.core.markets.Headline>,
)

data class MarketsState(
    val monitor: Task<List<Quote>> = Task(),
    val scan: Task<Terminal.Scan> = Task(),
    val momentum: Task<Terminal.MomentumNow> = Task(),
    val detail: Task<Detail> = Task(),
    val machine: Task<Terminal.Machine> = Task(),
    val intradayProof: Task<Unit> = Task(),
    val momentumProof: Task<Unit> = Task(),
    val proofs: SavedProofs = SavedProofs(),
    val limits: RiskLimits = RiskLimits(),
    val journal: List<JournalEntry> = emptyList(),
    val strategy: Strategy = Strategy(),
    val names: Map<String, String> = emptyMap(),
)

/** The terminal's data, owned here and observed by the UI. All network work runs on IO. */
object MarketsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(MarketsState())
    val flow: StateFlow<MarketsState> = state.asStateFlow()
    @Volatile private var dir: File? = null
    @Volatile private var terminal: Terminal? = null
    private val jobs = HashMap<String, Job>()

    fun attach(context: Context) {
        if (terminal != null) return
        val d = File(context.filesDir, "markets").apply { mkdirs() }
        dir = d
        terminal = Terminal(YahooSource(File(context.cacheDir, "markets")))
        val limits = read(File(d, "limits.json")) { json.decodeFromString(RiskLimits.serializer(), it) } ?: RiskLimits()
        val journal = read(File(d, "journal.json")) { json.decodeFromString(ListSerializer(JournalEntry.serializer()), it) } ?: emptyList()
        val proofs = read(File(d, "proofs.json")) { json.decodeFromString(SavedProofs.serializer(), it) } ?: SavedProofs()
        state.update { it.copy(limits = limits, journal = journal, proofs = proofs) }
        run("names") { t -> val names = t.stocks().associate { it.symbol to it.name }; state.update { it.copy(names = names) } }
    }

    private fun <T> read(f: File, parse: (String) -> T): T? = runCatching { if (f.exists()) parse(f.readText()) else null }.getOrNull()

    private fun write(name: String, text: String) {
        val d = dir ?: return
        runCatching { File(d, "$name.tmp").apply { writeText(text) }.renameTo(File(d, name)) }
    }

    /** One job per key at a time; a second tap while it runs does nothing. */
    private fun run(key: String, block: suspend (Terminal) -> Unit) {
        val t = terminal ?: return
        synchronized(jobs) {
            if (jobs[key]?.isActive == true) return
            jobs[key] = scope.launch { block(t) }
        }
    }

    private fun message(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "The data source is slow to answer. Try again."
        else -> e.message ?: e.javaClass.simpleName
    }

    fun refreshMonitor() = run("monitor") { t ->
        state.update { it.copy(monitor = it.monitor.copy(running = true, error = null)) }
        runCatching { t.monitor { d, n -> state.update { s -> s.copy(monitor = s.monitor.copy(done = d, total = n)) } } }
            .onSuccess { r -> state.update { it.copy(monitor = Task(result = r, finishedAt = System.currentTimeMillis())) } }
            .onFailure { e -> state.update { it.copy(monitor = it.monitor.copy(running = false, error = message(e))) } }
    }

    fun setStrategy(s: Strategy) = state.update { it.copy(strategy = s) }

    fun runScan() = run("scan") { t ->
        state.update { it.copy(scan = it.scan.copy(running = true, error = null)) }
        runCatching { t.scan(state.value.strategy) { d, n -> state.update { s -> s.copy(scan = s.scan.copy(done = d, total = n)) } } }
            .onSuccess { r -> state.update { it.copy(scan = Task(result = r, finishedAt = System.currentTimeMillis())) } }
            .onFailure { e -> state.update { it.copy(scan = it.scan.copy(running = false, error = message(e))) } }
    }

    fun loadMomentum() = run("momentum") { t ->
        state.update { it.copy(momentum = it.momentum.copy(running = true, error = null)) }
        runCatching { t.momentum { d, n -> state.update { s -> s.copy(momentum = s.momentum.copy(done = d, total = n)) } } }
            .onSuccess { r -> state.update { it.copy(momentum = Task(result = r, finishedAt = System.currentTimeMillis())) } }
            .onFailure { e -> state.update { it.copy(momentum = it.momentum.copy(running = false, error = message(e))) } }
    }

    fun openDetail(symbol: String) {
        val sym = symbol.trim().uppercase().removeSuffix(".NS")
        if (sym.isEmpty()) return
        synchronized(jobs) { jobs["detail"]?.cancel() }
        state.update { it.copy(detail = Task(running = true), machine = Task()) }
        run("detail") { t ->
            runCatching {
                val (bars, plan) = t.intraday(sym, state.value.strategy)
                val daily = runCatching { t.daily(sym, "1y") }.getOrDefault(emptyList())
                val news = runCatching { t.news(sym) }.getOrDefault(emptyList())
                if (bars.isEmpty() && daily.isEmpty()) error("No data for $sym on NSE. Check the symbol.")
                Detail(sym, state.value.names[sym].orEmpty(), bars, plan, daily, news)
            }.onSuccess { d -> state.update { it.copy(detail = Task(result = d, finishedAt = System.currentTimeMillis())) } }
                .onFailure { e -> state.update { it.copy(detail = Task(error = message(e))) } }
        }
    }

    fun closeDetail() = state.update { it.copy(detail = Task(), machine = Task()) }

    fun runTimeMachine(plan: Plan) = run("machine") { t ->
        state.update { it.copy(machine = Task(running = true)) }
        runCatching { t.timeMachine(plan, state.value.limits) }
            .onSuccess { r -> state.update { it.copy(machine = Task(result = r, finishedAt = System.currentTimeMillis())) } }
            .onFailure { e -> state.update { it.copy(machine = Task(error = message(e))) } }
    }

    fun runIntradayProof() = run("intradayProof") { t ->
        state.update { it.copy(intradayProof = Task(running = true)) }
        runCatching {
            t.intradayProof(state.value.limits) { d, n -> state.update { s -> s.copy(intradayProof = s.intradayProof.copy(done = d, total = n)) } }
        }.onSuccess { list ->
            val saved = list.map { SavedIntraday(it.targetR, it.stocks, it.every, it.taken) }
            saveProofs { it.copy(intraday = saved, intradayAt = System.currentTimeMillis()) }
            state.update { it.copy(intradayProof = Task(finishedAt = System.currentTimeMillis())) }
        }.onFailure { e -> state.update { it.copy(intradayProof = Task(error = message(e))) } }
    }

    fun runMomentumProof() = run("momentumProof") { t ->
        state.update { it.copy(momentumProof = Task(running = true)) }
        runCatching {
            t.momentumProof { d, n -> state.update { s -> s.copy(momentumProof = s.momentumProof.copy(done = d, total = n)) } }
        }.onSuccess { p ->
            val saved = SavedMomentum(p.stocks, p.metrics, p.universeMetrics, p.yearly.map { SavedYear(it.first, it.second) },
                p.grid.map { SavedGrid(it.first, it.second, it.third) }, p.result.turnover)
            saveProofs { it.copy(momentum = saved, momentumAt = System.currentTimeMillis()) }
            state.update { it.copy(momentumProof = Task(finishedAt = System.currentTimeMillis())) }
        }.onFailure { e -> state.update { it.copy(momentumProof = Task(error = message(e))) } }
    }

    private fun saveProofs(change: (SavedProofs) -> SavedProofs) {
        state.update { it.copy(proofs = change(it.proofs)) }
        write("proofs.json", json.encodeToString(SavedProofs.serializer(), state.value.proofs))
    }

    fun setLimits(l: RiskLimits) {
        state.update { it.copy(limits = l) }
        write("limits.json", json.encodeToString(RiskLimits.serializer(), l))
    }

    fun recordTrade(order: app.airbox.core.markets.Order) {
        val p = order.plan
        val e = JournalEntry(p.day.toString(), p.symbol, p.side.name, order.qty, p.entry, p.stop, p.target, order.lossAtStop, order.marginNeeded)
        saveJournal(state.value.journal + e)
    }

    /** How a trade ended, after charges (fees only: the exit price already includes slippage). */
    fun closeTrade(entry: JournalEntry, exit: Double) {
        val long = entry.side == "LONG"
        val gross = entry.qty * (exit - entry.entry) * (if (long) 1 else -1)
        val fees = (if (long) roundTrip(entry.qty, entry.entry, exit) else roundTrip(entry.qty, exit, entry.entry)).fees
        saveJournal(state.value.journal.map { if (it == entry) it.copy(open = false, exit = exit, pnl = gross - fees) else it })
    }

    fun deleteTrade(entry: JournalEntry) = saveJournal(state.value.journal - entry)

    private fun saveJournal(list: List<JournalEntry>) {
        state.update { it.copy(journal = list) }
        write("journal.json", json.encodeToString(ListSerializer(JournalEntry.serializer()), list))
    }

    fun today(): String = Nse.now().toLocalDate().toString()

    fun rs(x: Double) = Money.rs(x)
}
