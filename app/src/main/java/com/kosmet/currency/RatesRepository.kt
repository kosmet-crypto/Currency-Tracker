package com.kosmet.currency

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PairQuote(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePct: Double,
    val points: List<Double>,
)

/** History range for charts and change; interval and size are Twelve Data parameters. */
enum class Period(val label: String, val interval: String, val size: Int) {
    D1("1Д", "15min", 96),
    W1("7Д", "1h", 168),
    M1("30Д", "4h", 180),
    Y1("1Г", "1day", 365),
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
    /** Code to Serbian name, in the order shown in pickers. */
    val ALL_CURRENCIES = linkedMapOf(
        "EUR" to "евро", "USD" to "амерички долар", "NOK" to "норвешка круна", "RSD" to "српски динар",
        "GBP" to "британска фунта", "CHF" to "швајцарски франак", "SEK" to "шведска круна",
        "DKK" to "данска круна", "PLN" to "пољски злот", "CZK" to "чешка круна", "HUF" to "мађарска форинта",
        "RON" to "румунски леј", "BGN" to "бугарски лев", "BAM" to "конвертибилна марка",
        "MKD" to "македонски денар", "TRY" to "турска лира", "RUB" to "руска рубља", "UAH" to "украјинска гривна",
        "ISK" to "исландска круна", "JPY" to "јапански јен", "CNY" to "кинески јуан", "CAD" to "канадски долар",
        "AUD" to "аустралијски долар", "NZD" to "новозеландски долар", "INR" to "индијска рупија",
        "AED" to "дирхам УАЕ", "ILS" to "израелски шекел", "SGD" to "сингапурски долар",
        "HKD" to "хонгконшки долар", "KRW" to "јужнокорејски вон", "BRL" to "бразилски реал",
        "MXN" to "мексички пезос", "ZAR" to "јужноафрички ранд", "THB" to "тајландски бат",
    )

    const val MAX_PAIRS = 4

    private const val PREFS = "rates"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_API = "api_key"
    private const val KEY_CURRENCIES = "converter_currencies"
    private const val KEY_PAIRS = "widget_pairs"
    private const val KEY_INVERTED = "inverted_symbols"
    private const val KEY_PERIOD = "period"

    fun period(ctx: Context): Period =
        Period.entries.find { it.name == prefs(ctx).getString(KEY_PERIOD, null) } ?: Period.D1

    fun setPeriod(ctx: Context, period: Period) {
        prefs(ctx).edit().putString(KEY_PERIOD, period.name).apply()
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

    fun inverse(symbol: String): String = symbol.split('/').let { it[1] + "/" + it[0] }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Key typed in the app wins; otherwise the one baked in at build time. */
    fun apiKey(ctx: Context): String =
        prefs(ctx).getString(KEY_API, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.TWELVEDATA_API_KEY

    fun savedApiKey(ctx: Context): String = prefs(ctx).getString(KEY_API, "") ?: ""

    fun setApiKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString(KEY_API, key.trim()).apply()
    }

    fun load(ctx: Context): Snapshot? {
        val raw = prefs(ctx).getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun refresh(ctx: Context): Snapshot {
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
            errors += "Курсна листа: ${e.message ?: "грешка"}"
        }

        val key = apiKey(ctx)
        if (key.isBlank()) errors += "Нема Twelve Data API кључа"
        val inverted = prefs(ctx).getStringSet(KEY_INVERTED, emptySet())!!.toMutableSet()
        val pairs = mutableListOf<PairQuote>()
        for (symbol in widgetPairs(ctx)) {
            val quote = try {
                if (key.isBlank()) null else fetchLive(symbol, key, period, inverted).also { fresh++ }
            } catch (e: SymbolException) {
                null // e.g. RSD pairs: shown with the daily rate, no chart
            } catch (e: Exception) {
                errors += "$symbol: ${e.message ?: "грешка"}"
                null
            }
            // No live data: keep the last chart, or fall back to the daily rate.
            pairs += quote
                ?: oldPairs.find { it.symbol == symbol && it.points.isNotEmpty() }
                ?: dailyQuote(symbol, perEur)
                ?: continue
        }
        prefs(ctx).edit().putStringSet(KEY_INVERTED, inverted).apply()

        // Live quotes against the euro are fresher than the daily list.
        for (p in pairs) {
            if (p.points.isEmpty()) continue
            val (base, quote) = p.symbol.split('/')
            if (base == "EUR") perEur[quote] = p.price
            if (quote == "EUR" && p.price != 0.0) perEur[base] = 1 / p.price
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
        return PairQuote(symbol, q / b, 0.0, 0.0, emptyList())
    }

    /** Twelve Data lists most pairs in one direction only; try the other one and flip it. */
    private fun fetchLive(symbol: String, key: String, period: Period, inverted: MutableSet<String>): PairQuote {
        val first = if (symbol in inverted) inverse(symbol) else symbol
        val result = try {
            fetchPair(first, key, period)
        } catch (e: SymbolException) {
            val other = inverse(first)
            fetchPair(other, key, period).also {
                if (other == symbol) inverted -= symbol else inverted += symbol
            }
        }
        return if (result.symbol == symbol) result else invert(result, symbol)
    }

    private fun invert(p: PairQuote, symbol: String): PairQuote {
        val points = p.points.map { 1 / it }
        val price = points.last()
        val first = points.first()
        val change = price - first
        return PairQuote(symbol, price, change, if (first != 0.0) change / first * 100 else 0.0, points)
    }

    private class SymbolException(msg: String) : IOException(msg)

    private fun fetchPair(symbol: String, key: String, period: Period): PairQuote {
        val url = "https://api.twelvedata.com/time_series" +
            "?symbol=" + URLEncoder.encode(symbol, "UTF-8") +
            "&interval=" + period.interval + "&outputsize=" + period.size + "&timezone=UTC" +
            "&apikey=" + URLEncoder.encode(key, "UTF-8")
        val json = JSONObject(httpGet(url))
        if (json.optString("status") == "error") {
            when (json.optInt("code")) {
                429 -> throw IOException("лимит захтева, пробај за минут")
                401, 403 -> throw IOException("погрешан API кључ")
                400, 404 -> throw SymbolException("пар није доступан уживо")
                else -> throw IOException(json.optString("message", "грешка"))
            }
        }
        val values = json.optJSONArray("values") ?: throw SymbolException("нема података")
        // API returns newest first.
        val points = (values.length() - 1 downTo 0).map {
            values.getJSONObject(it).getString("close").toDouble()
        }
        if (points.isEmpty()) throw IOException("нема података")
        val price = points.last()
        val first = points.first()
        val change = price - first
        return PairQuote(symbol, price, change, if (first != 0.0) change / first * 100 else 0.0, points)
    }

    private fun fetchDailyPerEur(): Map<String, Double> {
        val json = JSONObject(httpGet("https://open.er-api.com/v6/latest/EUR"))
        if (json.optString("result") != "success") throw IOException("курсна листа није доступна")
        val rates = json.getJSONObject("rates")
        return ALL_CURRENCIES.keys.filter { rates.has(it) }.associateWith { rates.getDouble(it) }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299 && !body.trimStart().startsWith("{")) throw IOException("HTTP $code")
            return body
        } catch (e: java.net.UnknownHostException) {
            throw IOException("нема интернета")
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("истекло време")
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
                    put("change", p.change)
                    put("changePct", p.changePct)
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
            PairQuote(
                symbol = p.getString("symbol"),
                price = p.getDouble("price"),
                change = p.getDouble("change"),
                changePct = p.getDouble("changePct"),
                points = (0 until pts.length()).map { pts.getDouble(it) },
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
