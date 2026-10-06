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

data class Snapshot(
    val pairs: List<PairQuote>,
    /** How many units of each currency one euro buys. */
    val perEur: Map<String, Double>,
    val updatedAt: Long,
    val error: String?,
)

object RatesRepository {
    val PAIRS = listOf("EUR/NOK", "EUR/USD", "USD/NOK")
    val CURRENCIES = listOf("EUR", "USD", "NOK", "RSD")

    private const val PREFS = "rates"
    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_API = "api_key"

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
        val old = load(ctx)
        val errors = mutableListOf<String>()
        val pairs = mutableListOf<PairQuote>()
        var fresh = 0

        val key = apiKey(ctx)
        if (key.isBlank()) {
            errors += "Нема Twelve Data API кључа"
            old?.pairs?.let { pairs += it }
        } else {
            for (symbol in PAIRS) {
                try {
                    pairs += fetchPair(symbol, key)
                    fresh++
                } catch (e: Exception) {
                    errors += "$symbol: ${e.message ?: "грешка"}"
                    old?.pairs?.find { it.symbol == symbol }?.let { pairs += it }
                }
            }
        }

        val perEur = HashMap(old?.perEur ?: emptyMap())
        perEur["EUR"] = 1.0
        try {
            perEur.putAll(fetchDailyPerEur())
            fresh++
        } catch (e: Exception) {
            errors += "RSD: ${e.message ?: "грешка"}"
        }
        // Live quotes are fresher than the daily list.
        pairs.find { it.symbol == "EUR/USD" }?.let { perEur["USD"] = it.price }
        pairs.find { it.symbol == "EUR/NOK" }?.let { perEur["NOK"] = it.price }

        val snapshot = Snapshot(
            pairs = pairs,
            perEur = perEur,
            updatedAt = if (fresh > 0) System.currentTimeMillis() else old?.updatedAt ?: 0L,
            error = errors.firstOrNull(),
        )
        prefs(ctx).edit().putString(KEY_SNAPSHOT, toJson(snapshot).toString()).apply()
        return snapshot
    }

    /** Last 24 hours in 15-minute steps. */
    private fun fetchPair(symbol: String, key: String): PairQuote {
        val url = "https://api.twelvedata.com/time_series" +
            "?symbol=" + URLEncoder.encode(symbol, "UTF-8") +
            "&interval=15min&outputsize=96&timezone=UTC" +
            "&apikey=" + URLEncoder.encode(key, "UTF-8")
        val json = JSONObject(httpGet(url))
        if (json.optString("status") == "error") {
            val msg = when (json.optInt("code")) {
                429 -> "лимит захтева, пробај за минут"
                401, 403 -> "погрешан API кључ"
                else -> json.optString("message", "грешка")
            }
            throw IOException(msg)
        }
        val values = json.getJSONArray("values")
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
        return CURRENCIES.filter { rates.has(it) }.associateWith { rates.getDouble(it) }
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
            pairs = pairs,
            perEur = perEur,
            updatedAt = o.optLong("updatedAt"),
            error = if (o.isNull("error")) null else o.optString("error"),
        )
    }
}
