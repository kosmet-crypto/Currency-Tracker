package com.kosmet.currency

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Currency

data class PairQuote(
    val symbol: String,
    val price: Double,
    /** Previous close: the dashed line and the reference for change. */
    val base: Double,
    /** Epoch seconds, one per point. */
    val times: List<Long>,
    val points: List<Double>,
) {
    val change get() = price - base
    val changePct get() = if (base != 0.0) change / base * 100 else 0.0
}

/** History range for charts and change; range and interval are Yahoo Finance parameters. */
enum class Period(val labelRes: Int, val range: String, val interval: String) {
    D1(R.string.period_d1, "1d", "5m"),
    W1(R.string.period_w1, "5d", "15m"),
    M1(R.string.period_m1, "1mo", "60m"),
    Y1(R.string.period_y1, "1y", "1d"),
}

data class Snapshot(
    val period: Period,
    val pairs: List<PairQuote>,
    /** How many units of each currency one euro buys. */
    val perEur: Map<String, Double>,
    val updatedAt: Long,
    val error: String?,
)

object RatesRepository {
    val ALL_CURRENCIES = listOf(
        "EUR", "USD", "NOK", "RSD", "GBP", "CHF", "SEK", "DKK", "PLN", "CZK", "HUF", "RON", "BGN",
        "BAM", "MKD", "TRY", "RUB", "UAH", "ISK", "JPY", "CNY", "CAD", "AUD", "NZD", "INR", "AED",
        "ILS", "SGD", "HKD", "KRW", "BRL", "MXN", "ZAR", "THB",
    )

    const val MAX_PAIRS = 4

    private const val PREFS = "rates"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_CURRENCIES = "converter_currencies"
    private const val KEY_PAIRS = "widget_pairs"
    private const val KEY_PERIOD = "period"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Currency name in the phone's language. */
    fun currencyName(code: String): String = try {
        Currency.getInstance(code).displayName
    } catch (e: IllegalArgumentException) {
        ""
    }

    fun converterCurrencies(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_CURRENCIES, "EUR,USD,NOK,RSD")!!.split(',').filter { it.isNotBlank() }

    fun setConverterCurrencies(ctx: Context, codes: List<String>) {
        prefs(ctx).edit().putString(KEY_CURRENCIES, codes.joinToString(",")).apply()
    }

    fun widgetPairs(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_PAIRS, "EUR/NOK,EUR/USD,USD/NOK")!!.split(',').filter { it.isNotBlank() }

    fun setWidgetPairs(ctx: Context, pairs: List<String>) {
        prefs(ctx).edit().putString(KEY_PAIRS, pairs.joinToString(",")).apply()
    }

    fun period(ctx: Context): Period =
        Period.entries.find { it.name == prefs(ctx).getString(KEY_PERIOD, null) } ?: Period.D1

    fun setPeriod(ctx: Context, period: Period) {
        prefs(ctx).edit().putString(KEY_PERIOD, period.name).apply()
    }

    fun inverse(symbol: String): String = symbol.split('/').let { it[1] + "/" + it[0] }

    fun load(ctx: Context): Snapshot? {
        val raw = prefs(ctx).getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Widget pairs with history from Yahoo; with [withConverter] also live rates for
     * every converter currency. The daily list fills whatever Yahoo cannot give.
     */
    @Synchronized
    fun refresh(ctx: Context, withConverter: Boolean = false): Snapshot {
        val period = period(ctx)
        val old = load(ctx)
        val oldPairs = if (old != null && old.period == period) old.pairs else emptyList()
        val errors = mutableListOf<String>()
        var fresh = 0

        val perEur = HashMap(old?.perEur ?: emptyMap())
        perEur["EUR"] = 1.0
        try {
            perEur.putAll(fetchDailyPerEur())
            fresh++
        } catch (e: Exception) {
            errors += ctx.getString(R.string.err_item, ctx.getString(R.string.daily_list), message(ctx, e))
        }

        val pairs = mutableListOf<PairQuote>()
        for (symbol in widgetPairs(ctx)) {
            val quote = try {
                fetchPair(symbol, period).also { fresh++ }
            } catch (e: Exception) {
                errors += ctx.getString(R.string.err_item, symbol, message(ctx, e))
                null
            }
            // On failure keep the last chart, so the widget never goes blank.
            pairs += quote
                ?: oldPairs.find { it.symbol == symbol && it.points.isNotEmpty() }
                ?: dailyQuote(symbol, perEur)
                ?: continue
        }

        for (p in pairs) {
            if (p.points.isEmpty()) continue
            val (base, quote) = p.symbol.split('/')
            if (base == "EUR") perEur[quote] = p.price
            if (quote == "EUR" && p.price != 0.0) perEur[base] = 1 / p.price
        }
        if (withConverter) {
            for (code in converterCurrencies(ctx)) {
                if (code == "EUR" || pairs.any { it.symbol == "EUR/$code" && it.points.isNotEmpty() }) continue
                try {
                    perEur[code] = fetchPrice("EUR/$code")
                } catch (e: Exception) {
                    // The daily rate stays.
                }
            }
        }

        val snapshot = Snapshot(
            period = period,
            pairs = pairs,
            perEur = perEur,
            updatedAt = if (fresh > 0) System.currentTimeMillis() else old?.updatedAt ?: 0L,
            error = errors.firstOrNull(),
        )
        prefs(ctx).edit().putString(KEY_SNAPSHOT, toJson(snapshot).toString()).apply()
        return snapshot
    }

    /** The chosen pairs in order, right after the user changes them and before a refresh. */
    fun quotes(ctx: Context, snapshot: Snapshot?): List<Pair<String, PairQuote?>> {
        val current = if (snapshot != null && snapshot.period == period(ctx)) snapshot.pairs else emptyList()
        return widgetPairs(ctx).map { symbol ->
            symbol to (current.find { it.symbol == symbol }
                ?: snapshot?.let { dailyQuote(symbol, it.perEur) })
        }
    }

    private fun dailyQuote(symbol: String, perEur: Map<String, Double>): PairQuote? {
        val (base, quote) = symbol.split('/')
        val b = perEur[base] ?: return null
        val q = perEur[quote] ?: return null
        return PairQuote(symbol, q / b, q / b, emptyList(), emptyList())
    }

    private fun message(ctx: Context, e: Exception): String = when (e) {
        is java.net.UnknownHostException -> ctx.getString(R.string.err_no_internet)
        is java.net.SocketTimeoutException -> ctx.getString(R.string.err_timeout)
        else -> e.message ?: ctx.getString(R.string.err_generic)
    }

    private fun yahooUrl(symbol: String, range: String, interval: String) =
        "/v8/finance/chart/" + symbol.replace("/", "") + "=X?range=$range&interval=$interval"

    private fun fetchChart(symbol: String, range: String, interval: String): JSONObject {
        val json = JSONObject(yahooGet(yahooUrl(symbol, range, interval)))
        val chart = json.getJSONObject("chart")
        if (!chart.isNull("error")) {
            throw IOException(chart.getJSONObject("error").optString("description", "Yahoo"))
        }
        val result = chart.optJSONArray("result")
        if (result == null || result.length() == 0) throw IOException("Yahoo: no data")
        return result.getJSONObject(0)
    }

    private fun fetchPrice(symbol: String): Double {
        val meta = fetchChart(symbol, "1d", "1d").getJSONObject("meta")
        return meta.getDouble("regularMarketPrice")
    }

    private fun fetchPair(symbol: String, period: Period): PairQuote {
        val result = fetchChart(symbol, period.range, period.interval)
        val meta = result.getJSONObject("meta")
        val stamps = result.optJSONArray("timestamp") ?: JSONArray()
        val closes = result.getJSONObject("indicators").getJSONArray("quote")
            .getJSONObject(0).optJSONArray("close") ?: JSONArray()

        val times = ArrayList<Long>()
        val points = ArrayList<Double>()
        for (i in 0 until minOf(stamps.length(), closes.length())) {
            if (closes.isNull(i)) continue
            times += stamps.getLong(i)
            points += closes.getDouble(i)
        }
        val price = meta.optDouble("regularMarketPrice").takeIf { !it.isNaN() } ?: points.lastOrNull()
            ?: throw IOException("Yahoo: no price")
        val base = meta.optDouble("chartPreviousClose").takeIf { !it.isNaN() } ?: points.firstOrNull() ?: price
        return PairQuote(symbol, price, base, times, points)
    }

    private fun fetchDailyPerEur(): Map<String, Double> {
        val json = JSONObject(httpGet("https://open.er-api.com/v6/latest/EUR"))
        if (json.optString("result") != "success") throw IOException("open.er-api.com")
        val rates = json.getJSONObject("rates")
        return ALL_CURRENCIES.filter { rates.has(it) }.associateWith { rates.getDouble(it) }
    }

    /** Yahoo sometimes refuses one host; the other usually answers. */
    private fun yahooGet(path: String): String = try {
        httpGet("https://query1.finance.yahoo.com$path")
    } catch (e: IOException) {
        httpGet("https://query2.finance.yahoo.com$path")
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        // Yahoo rejects requests without a browser-like User-Agent.
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CurrencyTracker")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299 && !body.trimStart().startsWith("{")) throw IOException("HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun toJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("period", s.period.name)
        put("updatedAt", s.updatedAt)
        put("error", s.error ?: JSONObject.NULL)
        put("perEur", JSONObject(s.perEur as Map<*, *>))
        put("pairs", JSONArray().apply {
            s.pairs.forEach { p ->
                put(JSONObject().apply {
                    put("symbol", p.symbol)
                    put("price", p.price)
                    put("base", p.base)
                    put("times", JSONArray().apply { p.times.forEach { put(it) } })
                    put("points", JSONArray().apply { p.points.forEach { put(it) } })
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Snapshot {
        val perEurJson = o.getJSONObject("perEur")
        val perEur = perEurJson.keys().asSequence().associateWith { perEurJson.getDouble(it) }
        val arr = o.getJSONArray("pairs")
        val pairs = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val pts = p.getJSONArray("points")
            val ts = p.optJSONArray("times") ?: JSONArray()
            val points = (0 until pts.length()).map { pts.getDouble(it) }
            val times = (0 until ts.length()).map { ts.getLong(it) }
            PairQuote(
                symbol = p.getString("symbol"),
                price = p.getDouble("price"),
                base = p.optDouble("base", points.firstOrNull() ?: p.getDouble("price")),
                // Older saves have no times; drop their points so lists stay aligned.
                times = if (times.size == points.size) times else emptyList(),
                points = if (times.size == points.size) points else emptyList(),
            )
        }
        return Snapshot(
            period = Period.entries.find { it.name == o.optString("period") } ?: Period.D1,
            pairs = pairs,
            perEur = perEur,
            updatedAt = o.optLong("updatedAt"),
            error = if (o.isNull("error")) null else o.optString("error"),
        )
    }
}
