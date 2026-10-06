package com.kosmet.currency

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var inputs: Map<String, EditText>
    private lateinit var status: TextView
    private lateinit var ratesText: TextView
    private lateinit var refreshButton: Button
    private lateinit var pairsBox: LinearLayout

    private var snapshot: Snapshot? = null
    private var lastEdited = "EUR"
    private var updatingFields = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputs = mapOf(
            "EUR" to findViewById(R.id.in_eur),
            "USD" to findViewById(R.id.in_usd),
            "NOK" to findViewById(R.id.in_nok),
            "RSD" to findViewById(R.id.in_rsd),
        )
        status = findViewById(R.id.status)
        ratesText = findViewById(R.id.rates_text)
        refreshButton = findViewById(R.id.btn_refresh)
        pairsBox = findViewById(R.id.pairs)

        for ((code, field) in inputs) {
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (updatingFields) return
                    lastEdited = code
                    recalculate()
                }
            })
        }

        refreshButton.setOnClickListener { refresh() }

        val keyField = findViewById<EditText>(R.id.api_key)
        keyField.setText(RatesRepository.savedApiKey(this))
        if (BuildConfig.TWELVEDATA_API_KEY.isNotBlank()) keyField.hint = getString(R.string.api_key_builtin)
        findViewById<Button>(R.id.btn_save_key).setOnClickListener {
            RatesRepository.setApiKey(this, keyField.text.toString())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            refresh()
        }

        show(RatesRepository.load(this))
        inputs.getValue("EUR").setText("1")
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

    private fun show(s: Snapshot?) {
        snapshot = s
        status.text = when {
            s == null -> getString(R.string.no_data)
            s.error != null -> "⚠ " + s.error + "\n" + getString(R.string.updated_at, Ui.time(s.updatedAt))
            else -> getString(R.string.updated_at, Ui.time(s.updatedAt))
        }

        val perEur = s?.perEur.orEmpty()
        ratesText.text = RatesRepository.CURRENCIES.filter { it != "EUR" }
            .mapNotNull { code -> perEur[code]?.let { "1 EUR = " + Ui.price(it) + " " + code } }
            .joinToString("\n")

        pairsBox.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density
        for (pair in s?.pairs.orEmpty()) {
            val row = inflater.inflate(R.layout.item_pair, pairsBox, false)
            val color = Ui.changeColor(pair.change)
            row.findViewById<TextView>(R.id.p_name).text = pair.symbol
            row.findViewById<TextView>(R.id.p_price).text = Ui.price(pair.price)
            row.findViewById<TextView>(R.id.p_change).apply {
                text = Ui.change(pair)
                setTextColor(color)
            }
            row.findViewById<ImageView>(R.id.p_chart)
                .setImageBitmap(Ui.chart(pair.points, (180 * density).toInt(), (56 * density).toInt(), color, 2 * density))
            pairsBox.addView(row)
        }

        recalculate()
    }

    private fun recalculate() {
        val perEur = snapshot?.perEur ?: return
        val source = inputs.getValue(lastEdited)
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
}
