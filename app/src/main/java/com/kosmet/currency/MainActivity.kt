package com.kosmet.currency

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private val inputs = LinkedHashMap<String, EditText>()
    private lateinit var status: TextView
    private lateinit var ratesText: TextView
    private lateinit var refreshButton: Button
    private lateinit var converterBox: LinearLayout
    private lateinit var pairsBox: LinearLayout

    private var snapshot: Snapshot? = null
    private var lastEdited = "EUR"
    private var updatingFields = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        ratesText = findViewById(R.id.rates_text)
        refreshButton = findViewById(R.id.btn_refresh)
        converterBox = findViewById(R.id.converter_rows)
        pairsBox = findViewById(R.id.pairs)

        refreshButton.setOnClickListener { refresh() }
        findViewById<Button>(R.id.btn_add_currency).setOnClickListener { addCurrency() }
        findViewById<Button>(R.id.btn_add_pair).setOnClickListener { addPair() }

        val keyField = findViewById<EditText>(R.id.api_key)
        keyField.setText(RatesRepository.savedApiKey(this))
        if (BuildConfig.TWELVEDATA_API_KEY.isNotBlank()) keyField.hint = getString(R.string.api_key_builtin)
        findViewById<Button>(R.id.btn_save_key).setOnClickListener {
            RatesRepository.setApiKey(this, keyField.text.toString())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            refresh()
        }

        snapshot = RatesRepository.load(this)
        val currencies = RatesRepository.converterCurrencies(this)
        lastEdited = currencies.first()
        buildConverter(currencies)
        inputs.getValue(lastEdited).setText("1")
        show(snapshot)
        refresh()
    }

    private fun refresh() {
        status.setText(R.string.updating)
        refreshButton.isEnabled = false
        val app = applicationContext
        Thread {
            val result = try {
                RatesRepository.refresh(app)
            } catch (e: Exception) {
                null
            }
            RatesWidget.renderAll(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshButton.isEnabled = true
                show(result ?: RatesRepository.load(app))
            }
        }.start()
    }

    // --- Converter ---

    private fun buildConverter(codes: List<String>) {
        val amounts = inputs.mapValues { it.value.text.toString() }
        inputs.clear()
        converterBox.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (code in codes) {
            val row = inflater.inflate(R.layout.item_currency, converterBox, false)
            row.findViewById<TextView>(R.id.c_code).text = code
            row.findViewById<TextView>(R.id.c_name).text = RatesRepository.ALL_CURRENCIES[code] ?: ""
            val field = row.findViewById<EditText>(R.id.c_amount)
            field.setText(amounts[code] ?: "")
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (updatingFields) return
                    lastEdited = code
                    recalculate()
                }
            })
            row.findViewById<ImageButton>(R.id.c_remove).setOnClickListener { removeCurrency(code) }
            inputs[code] = field
            converterBox.addView(row)
        }
        if (lastEdited !in inputs) lastEdited = codes.first()
    }

    private fun addCurrency() {
        val available = RatesRepository.ALL_CURRENCIES.keys.filter { it !in inputs }
        pickCurrency(getString(R.string.add_currency), available) { code ->
            val codes = inputs.keys.toList() + code
            RatesRepository.setConverterCurrencies(this, codes)
            buildConverter(codes)
            recalculate()
        }
    }

    private fun removeCurrency(code: String) {
        if (inputs.size <= 2) {
            Toast.makeText(this, R.string.min_currencies, Toast.LENGTH_SHORT).show()
            return
        }
        val codes = inputs.keys.filter { it != code }
        RatesRepository.setConverterCurrencies(this, codes)
        buildConverter(codes)
        recalculate()
    }

    private fun recalculate() {
        val perEur = snapshot?.perEur ?: return
        val source = inputs[lastEdited] ?: return
        val amount = source.text.toString().replace(',', '.').replace(" ", "").toDoubleOrNull()
        val sourceRate = perEur[lastEdited]

        updatingFields = true
        for ((code, field) in inputs) {
            if (code == lastEdited) continue
            val rate = perEur[code]
            field.setText(
                if (amount == null || sourceRate == null || rate == null) ""
                else String.format(Locale.US, "%.2f", amount / sourceRate * rate)
            )
        }
        updatingFields = false
    }

    // --- Widget pairs ---

    private fun addPair() {
        val pairs = RatesRepository.widgetPairs(this)
        if (pairs.size >= RatesRepository.MAX_PAIRS) {
            Toast.makeText(this, R.string.max_pairs, Toast.LENGTH_SHORT).show()
            return
        }
        val all = RatesRepository.ALL_CURRENCIES.keys.toList()
        pickCurrency(getString(R.string.pick_base), all) { base ->
            pickCurrency(getString(R.string.pick_quote), all.filter { it != base }) { quote ->
                val symbol = "$base/$quote"
                if (symbol in pairs) {
                    Toast.makeText(this, R.string.pair_exists, Toast.LENGTH_SHORT).show()
                } else {
                    savePairs(pairs + symbol, refresh = true)
                }
            }
        }
    }

    private fun swapPair(symbol: String) {
        val pairs = RatesRepository.widgetPairs(this)
        val swapped = RatesRepository.inverse(symbol)
        if (swapped in pairs) {
            Toast.makeText(this, R.string.pair_exists, Toast.LENGTH_SHORT).show()
            return
        }
        savePairs(pairs.map { if (it == symbol) swapped else it }, refresh = true)
    }

    private fun removePair(symbol: String) {
        val pairs = RatesRepository.widgetPairs(this)
        if (pairs.size <= 1) {
            Toast.makeText(this, R.string.min_pairs, Toast.LENGTH_SHORT).show()
            return
        }
        savePairs(pairs.filter { it != symbol }, refresh = false)
    }

    private fun savePairs(pairs: List<String>, refresh: Boolean) {
        RatesRepository.setWidgetPairs(this, pairs)
        show(snapshot)
        RatesWidget.renderAll(applicationContext)
        if (refresh) refresh()
    }

    private fun pickCurrency(title: String, codes: List<String>, onPick: (String) -> Unit) {
        val labels = codes.map { "$it  ·  ${RatesRepository.ALL_CURRENCIES[it] ?: ""}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which -> onPick(codes[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Rendering ---

    private fun show(s: Snapshot?) {
        snapshot = s
        status.text = when {
            s == null -> getString(R.string.no_data)
            s.error != null -> "⚠ " + s.error + "\n" + getString(R.string.updated_at, Ui.time(s.updatedAt))
            else -> getString(R.string.updated_at, Ui.time(s.updatedAt))
        }

        val perEur = s?.perEur.orEmpty()
        val base = inputs.keys.firstOrNull()
        val baseRate = base?.let { perEur[it] }
        ratesText.text = if (base == null || baseRate == null) "" else inputs.keys.filter { it != base }
            .mapNotNull { code -> perEur[code]?.let { "1 $base = " + Ui.price(it / baseRate) + " " + code } }
            .joinToString("\n")

        pairsBox.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density
        for ((symbol, pair) in RatesRepository.quotes(this, s)) {
            val row = inflater.inflate(R.layout.item_pair, pairsBox, false)
            row.findViewById<TextView>(R.id.p_name).text = symbol
            row.findViewById<ImageButton>(R.id.p_swap).setOnClickListener { swapPair(symbol) }
            row.findViewById<ImageButton>(R.id.p_remove).setOnClickListener { removePair(symbol) }
            if (pair != null) {
                val color = Ui.changeColor(pair)
                row.findViewById<TextView>(R.id.p_price).text = Ui.price(pair.price)
                row.findViewById<TextView>(R.id.p_change).apply {
                    text = Ui.change(pair)
                    setTextColor(color)
                }
                row.findViewById<ImageView>(R.id.p_chart)
                    .setImageBitmap(Ui.chart(pair.points, (160 * density).toInt(), (56 * density).toInt(), color, 2 * density))
            } else {
                row.findViewById<TextView>(R.id.p_price).text = "—"
            }
            pairsBox.addView(row)
        }

        recalculate()
    }
}
