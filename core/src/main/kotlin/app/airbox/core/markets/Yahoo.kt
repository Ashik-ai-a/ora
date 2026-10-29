package app.airbox.core.markets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** Where prices and lists come from. Tests use a fake; the app uses [YahooSource]. */
interface MarketSource {
    fun universe(name: Universe): List<Stock>
    /** One chart: 5-minute bars (interval "5m") or daily bars ("1d"). */
    fun chart(symbol: String, range: String, interval: String): Chart?
    /** Up to 20 symbols per call: closes only, plus each stock's live snapshot. */
    fun spark(symbols: List<String>, range: String, interval: String): Map<String, Chart>
    fun news(symbol: String): List<Headline>
}

/** Mid and small caps only, as asked: no large-cap lists here. */
enum class Universe(val file: String, val label: String) {
    MIDSMALL_400("ind_niftymidsmallcap400list.csv", "Nifty MidSmallcap 400"),
    SMALLCAP_250("ind_niftysmallcap250list.csv", "Nifty Smallcap 250"),
    MIDCAP_150("ind_niftymidcap150list.csv", "Nifty Midcap 150"),
}

class Chart(
    val symbol: String,
    val times: List<java.time.LocalDateTime>,
    val open: List<Double?>,
    val high: List<Double?>,
    val low: List<Double?>,
    val close: List<Double?>,
    val volume: List<Double?>,
    val adjClose: List<Double?>,
    val meta: Map<String, Double?>,
) {
    fun bars(): List<Bar> = times.indices.mapNotNull { i ->
        val o = open.getOrNull(i); val h = high.getOrNull(i); val l = low.getOrNull(i); val c = close.getOrNull(i)
        if (o == null || h == null || l == null || c == null) null else Bar(times[i], o, h, l, c, volume.getOrNull(i) ?: 0.0)
    }

    fun daily(): List<DailyBar> = times.indices.mapNotNull { i ->
        val c = close.getOrNull(i) ?: return@mapNotNull null
        DailyBar(times[i].toLocalDate(), c, adjClose.getOrNull(i) ?: c, volume.getOrNull(i) ?: 0.0)
    }.groupBy { it.day }.map { it.value.last() }

    fun quote(): Quote? {
        val price = meta["regularMarketPrice"] ?: close.lastOrNull { it != null } ?: return null
        val prev = meta["chartPreviousClose"] ?: meta["previousClose"] ?: close.firstOrNull { it != null } ?: return null
        return Quote(symbol, price, prev, meta["regularMarketDayHigh"], meta["regularMarketDayLow"], meta["regularMarketVolume"],
            meta["fiftyTwoWeekHigh"], meta["fiftyTwoWeekLow"], close.filterNotNull(),
            meta["regularMarketTime"]?.let { Nse.ist(it.toLong()) })
    }

    companion object {
        /** A Yahoo chart "result" object (v8 chart, or one spark response). */
        fun parse(symbol: String, result: JsonObject): Chart? {
            val ts = (result["timestamp"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull ?: 0L } ?: emptyList()
            val ind = result["indicators"] as? JsonObject ?: return null
            val q = (ind["quote"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            fun col(o: JsonObject?, k: String) = ((o?.get(k) as? JsonArray) ?: JsonArray(emptyList())).map { (it as? JsonPrimitive)?.doubleOrNull }
            val adj = ((ind["adjclose"] as? JsonArray)?.firstOrNull() as? JsonObject)
            val meta = (result["meta"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.doubleOrNull } ?: emptyMap()
            return Chart(symbol, ts.map { Nse.ist(it) }, col(q, "open"), col(q, "high"), col(q, "low"), col(q, "close"),
                col(q, "volume"), col(adj, "adjclose").ifEmpty { col(q, "close") }, meta)
        }
    }
}

/**
 * Free, unofficial Yahoo Finance endpoints (NSE symbols with a .NS suffix) and the index
 * lists from niftyindices.com. Polite: one request per [gapMs] at most. Responses are cached
 * on disk for [maxAgeMs] so screens can be reopened without downloading again.
 *
 * Yahoo refuses most cloud servers ("429 Too Many Requests") but serves home and mobile
 * internet, so this runs on the phone.
 */
class YahooSource(
    private val cache: File,
    private val gapMs: Long = 350,
    private val clock: () -> Long = System::currentTimeMillis,
) : MarketSource {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var last = 0L

    override fun universe(name: Universe): List<Stock> {
        cache.mkdirs()
        val file = File(cache, name.file)
        if (!file.exists() || clock() - file.lastModified() > 7 * DAY) {
            val got = INDEX_HOSTS.firstNotNullOfOrNull { host ->
                runCatching { get(host + name.file, maxAgeMs = 0) }.getOrNull()?.takeIf { "Symbol" in it.lineSequence().first() }
            }
            if (got != null) file.writeText(got)
        }
        if (!file.exists()) {
            // The combined list is the two halves; build it if NSE's file is unavailable.
            if (name == Universe.MIDSMALL_400) return (universe(Universe.MIDCAP_150) + universe(Universe.SMALLCAP_250)).distinctBy { it.symbol }
            throw IOException("Could not download the ${name.label} list")
        }
        return parseIndexCsv(file.readText())
    }

    override fun chart(symbol: String, range: String, interval: String): Chart? {
        val path = "/v8/finance/chart/${enc(symbol)}.NS?range=$range&interval=$interval&includePrePost=false&events=div%2Csplit"
        val text = yahoo(path, ttl(range, interval), "chart-$symbol-$range-$interval") ?: return null
        val result = runCatching { json.parseToJsonElement(text).jsonObject["chart"]!!.jsonObject["result"]!!.jsonArray[0].jsonObject }
            .getOrNull() ?: return null
        return Chart.parse(symbol, result)
    }

    override fun spark(symbols: List<String>, range: String, interval: String): Map<String, Chart> {
        require(symbols.size <= 20) { "Yahoo's spark takes 20 symbols at most" }
        if (symbols.isEmpty()) return emptyMap()
        val list = symbols.joinToString(",") { enc("$it.NS") }
        val text = yahoo("/v7/finance/spark?symbols=$list&range=$range&interval=$interval", ttl(range, interval),
            "spark-${symbols.hashCode()}-$range-$interval") ?: return emptyMap()
        return parseSpark(text)
    }

    override fun news(symbol: String): List<Headline> {
        val text = runCatching {
            get("https://feeds.finance.yahoo.com/rss/2.0/headline?s=${enc("$symbol.NS")}&region=IN&lang=en-IN", 15 * MIN, "news-$symbol")
        }.getOrNull() ?: return emptyList()
        return parseRss(text)
    }

    private fun ttl(range: String, interval: String) = when {
        interval.endsWith("m") && range == "1d" -> 60_000L
        interval.endsWith("m") -> 10 * MIN
        else -> 6 * HOUR
    }

    private fun yahoo(path: String, maxAgeMs: Long, key: String): String? {
        for (attempt in 0 until 3) {
            val host = if (attempt % 2 == 0) "https://query1.finance.yahoo.com" else "https://query2.finance.yahoo.com"
            try {
                return get(host + path, maxAgeMs, key)
            } catch (e: HttpStatus) {
                if (e.code == 404) return null
                if (e.code == 429) throw IOException("Yahoo Finance is refusing requests right now (429). Try again in a few minutes, on another network.")
            } catch (e: IOException) {
                if (attempt == 2) throw e
            }
            Thread.sleep(800L * (attempt + 1))
        }
        return null
    }

    class HttpStatus(val code: Int) : IOException("HTTP $code")

    @Synchronized
    private fun pace() {
        val wait = last + gapMs - clock()
        if (wait > 0) Thread.sleep(wait)
        last = clock()
    }

    private fun get(url: String, maxAgeMs: Long, key: String? = null): String {
        val file = key?.let { File(cache, "http/" + it.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt") }
        if (file != null && file.exists() && clock() - file.lastModified() < maxAgeMs) return file.readText()
        pace()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            if (conn.responseCode != 200) throw HttpStatus(conn.responseCode)
            val stream = if (conn.contentEncoding == "gzip") GZIPInputStream(conn.inputStream) else conn.inputStream
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            file?.let { it.parentFile?.mkdirs(); it.writeText(text) }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN
        private const val DAY = 24 * HOUR
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
        val INDEX_HOSTS = listOf(
            "https://www.niftyindices.com/IndexConstituent/",
            "https://nsearchives.nseindia.com/content/indices/",
            "https://archives.nseindia.com/content/indices/",
        )

        fun parseIndexCsv(text: String): List<Stock> {
            val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
            val head = lines.firstOrNull()?.split(',')?.map { it.trim() } ?: return emptyList()
            val iName = head.indexOf("Company Name"); val iInd = head.indexOf("Industry"); val iSym = head.indexOf("Symbol")
            if (iSym < 0) return emptyList()
            return lines.drop(1).mapNotNull { line ->
                val cells = splitCsv(line)
                val sym = cells.getOrNull(iSym)?.trim().orEmpty()
                if (sym.isEmpty()) null else Stock(sym, cells.getOrNull(iName)?.trim().orEmpty(), cells.getOrNull(iInd)?.trim().orEmpty())
            }
        }

        private fun splitCsv(line: String): List<String> {
            val out = mutableListOf<String>(); val sb = StringBuilder(); var quoted = false
            for (ch in line) when {
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            out += sb.toString()
            return out
        }

        fun parseSpark(text: String): Map<String, Chart> {
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
            val results = (root["spark"] as? JsonObject)?.get("result") as? JsonArray ?: return emptyMap()
            return results.mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                val sym = (o["symbol"] as? JsonPrimitive)?.content?.removeSuffix(".NS") ?: return@mapNotNull null
                val resp = (o["response"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
                Chart.parse(sym, resp)?.let { sym to it }
            }.toMap()
        }

        fun parseRss(text: String): List<Headline> =
            Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(text).mapNotNull { m ->
                val item = m.groupValues[1]
                fun tag(t: String) = Regex("<$t>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</$t>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.trim()
                val title = tag("title")?.let(::unescape) ?: return@mapNotNull null
                Headline(title, tag("link").orEmpty(), tag("pubDate"))
            }.take(20).toList()

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    }
}
