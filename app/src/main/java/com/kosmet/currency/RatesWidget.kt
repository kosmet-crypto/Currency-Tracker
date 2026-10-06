package com.kosmet.currency

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

class RatesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshInBackground(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> refreshInBackground(context)
            ACTION_PERIOD -> {
                val period = Period.entries.find { it.name == intent.getStringExtra(EXTRA_PERIOD) } ?: return
                RatesRepository.setPeriod(context, period)
                refreshInBackground(context)
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun refreshInBackground(context: Context) {
        val app = context.applicationContext
        renderAll(app, loading = true)
        val pending = goAsync()
        Thread {
            try {
                RatesRepository.refresh(app)
            } catch (e: Exception) {
                // Old data stays on screen.
            } finally {
                renderAll(app, loading = false)
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "com.kosmet.currency.REFRESH"
        const val ACTION_PERIOD = "com.kosmet.currency.PERIOD"
        const val EXTRA_PERIOD = "period"

        private val PERIOD_CHIPS = intArrayOf(R.id.w_p1, R.id.w_p2, R.id.w_p3, R.id.w_p4)

        private val ROWS = arrayOf(
            intArrayOf(R.id.w_row1, R.id.w_name1, R.id.w_price1, R.id.w_change1, R.id.w_chart1),
            intArrayOf(R.id.w_row2, R.id.w_name2, R.id.w_price2, R.id.w_change2, R.id.w_chart2),
            intArrayOf(R.id.w_row3, R.id.w_name3, R.id.w_price3, R.id.w_change3, R.id.w_chart3),
            intArrayOf(R.id.w_row4, R.id.w_name4, R.id.w_price4, R.id.w_change4, R.id.w_chart4),
        )

        fun renderAll(context: Context, loading: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RatesWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, buildViews(context, loading))
        }

        private fun buildViews(context: Context, loading: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_rates)
            val snapshot = RatesRepository.load(context)

            val quotes = RatesRepository.quotes(context, snapshot)
            ROWS.forEachIndexed { i, ids ->
                val (rowId, nameId, priceId, changeId) = ids.toList()
                val chartId = ids[4]
                val entry = quotes.getOrNull(i)
                if (entry == null) {
                    views.setViewVisibility(rowId, View.GONE)
                    return@forEachIndexed
                }
                views.setViewVisibility(rowId, View.VISIBLE)
                val (symbol, pair) = entry
                views.setTextViewText(nameId, symbol)
                if (pair == null) {
                    views.setTextViewText(priceId, "—")
                    views.setTextViewText(changeId, "")
                    views.setImageViewBitmap(chartId, Ui.chart(null, 1, 1, 1f))
                } else {
                    val color = Ui.changeColor(pair)
                    views.setTextViewText(priceId, Ui.price(pair.price))
                    views.setTextViewText(changeId, Ui.change(context, pair))
                    views.setTextColor(changeId, color)
                    views.setImageViewBitmap(chartId, Ui.chart(pair, 360, 100, 3f))
                }
                val open = Intent(context, ChartActivity::class.java)
                    .putExtra(ChartActivity.EXTRA_SYMBOL, symbol)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(
                    rowId,
                    PendingIntent.getActivity(
                        context, 20 + i, open,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

            // Header is narrow: just the time, with a warning sign if something failed.
            val status = when {
                loading -> "…"
                snapshot?.error != null -> "⚠ " + Ui.shortTime(snapshot.updatedAt)
                else -> Ui.shortTime(snapshot?.updatedAt ?: 0)
            }
            views.setTextViewText(R.id.w_status, status)

            val selected = RatesRepository.period(context)
            Period.entries.forEachIndexed { i, period ->
                val id = PERIOD_CHIPS[i]
                val on = period == selected
                views.setTextViewText(id, context.getString(period.labelRes))
                views.setTextColor(id, if (on) Ui.GREEN else Ui.GREY)
                views.setInt(id, "setBackgroundResource", if (on) R.drawable.chip_on else R.drawable.chip_off)
                val intent = Intent(context, RatesWidget::class.java)
                    .setAction(ACTION_PERIOD)
                    .putExtra(EXTRA_PERIOD, period.name)
                views.setOnClickPendingIntent(
                    id,
                    PendingIntent.getBroadcast(
                        context, 10 + i, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

            val refresh = Intent(context, RatesWidget::class.java).setAction(ACTION_REFRESH)
            views.setOnClickPendingIntent(
                R.id.w_refresh,
                PendingIntent.getBroadcast(
                    context, 0, refresh,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return views
        }
    }
}
