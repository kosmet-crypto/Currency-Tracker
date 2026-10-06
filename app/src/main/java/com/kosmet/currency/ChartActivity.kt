package com.kosmet.currency

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/** Big chart for one pair; drag a finger across it to read the rate at that moment. */
class ChartActivity : Activity() {

    private lateinit var chart: ChartView
    private lateinit var priceText: TextView
    private lateinit var changeText: TextView
    private lateinit var timeText: TextView
    private lateinit var symbol: String
    private var quote: PairQuote? = null
    private var updatedAt = 0L

    private val periodChips by lazy {
        listOf(R.id.a_p1, R.id.a_p2, R.id.a_p3, R.id.a_p4).map { findViewById<TextView>(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart)
        symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: RatesRepository.widgetPairs(this).first()

        findViewById<TextView>(R.id.chart_symbol).text = symbol
        priceText = findViewById(R.id.chart_price)
        changeText = findViewById(R.id.chart_change)
        timeText = findViewById(R.id.chart_time)
        chart = findViewById(R.id.chart)
        chart.onScrub = { index -> showPoint(index) }

        Period.entries.forEachIndexed { i, period ->
            periodChips[i].setText(period.labelRes)
            periodChips[i].setOnClickListener {
                if (RatesRepository.period(this) == period) return@setOnClickListener
                RatesRepository.setPeriod(this, period)
                show(RatesRepository.load(this))
                refresh()
            }
        }

        show(RatesRepository.load(this))
        refresh()
    }

    private fun refresh() {
        timeText.setText(R.string.updating)
        val app = applicationContext
        Thread {
            val result = try {
                RatesRepository.refresh(app)
            } catch (e: Exception) {
                null
            }
            RatesWidget.renderAll(app)
            runOnUiThread {
                if (!isDestroyed) show(result ?: RatesRepository.load(app))
            }
        }.start()
    }

    private fun show(s: Snapshot?) {
        updatedAt = s?.updatedAt ?: 0L
        quote = RatesRepository.quotes(this, s).find { it.first == symbol }?.second
        chart.quote = quote
        val selected = RatesRepository.period(this)
        Period.entries.forEachIndexed { i, period ->
            val on = period == selected
            periodChips[i].setTextColor(if (on) Ui.GREEN else Ui.GREY)
            periodChips[i].setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip_off)
        }
        showPoint(null)
    }

    /** Null index means "now": the latest price. */
    private fun showPoint(index: Int?) {
        val q = quote
        if (q == null) {
            priceText.text = "—"
            changeText.text = ""
            timeText.text = ""
            return
        }
        if (index == null || index !in q.points.indices) {
            priceText.text = Ui.price(q.price)
            changeText.text = Ui.change(this, q)
            changeText.setTextColor(Ui.changeColor(q))
            timeText.text = getString(R.string.updated_at, Ui.time(updatedAt))
        } else {
            val value = q.points[index]
            priceText.text = Ui.price(value)
            changeText.text = Ui.change(value, q.base)
            changeText.setTextColor(if (value >= q.base) Ui.GREEN else Ui.RED)
            timeText.text = Ui.time(q.times[index] * 1000)
        }
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }
}

class ChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var quote: PairQuote? = null
        set(value) {
            field = value
            scrubIndex = null
            invalidate()
        }

    var onScrub: ((Int?) -> Unit)? = null

    private var scrubIndex: Int? = null
    private var geometry: Ui.Geometry? = null
    private val density = resources.displayMetrics.density
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.GREY
        strokeWidth = density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val q = quote ?: return
        val g = Ui.drawChart(canvas, q, width.toFloat(), height.toFloat(), 2.5f * density) ?: return
        geometry = g
        val i = scrubIndex ?: return
        val x = g.x(i)
        val y = g.y(q.points[i])
        canvas.drawLine(x, 0f, x, height.toFloat(), cursorPaint)
        dotPaint.color = Ui.changeColor(q)
        canvas.drawCircle(x, y, 5 * density, dotPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val i = g.index(event.x)
                if (i != scrubIndex) {
                    scrubIndex = i
                    onScrub?.invoke(i)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrubIndex = null
                onScrub?.invoke(null)
                invalidate()
            }
        }
        return true
    }
}
