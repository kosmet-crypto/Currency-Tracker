package com.kosmet.currency

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class RatesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshInBackground(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            refreshInBackground(context)
        } else {
            super.onReceive(context, intent)
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

        private val ROWS = arrayOf(
            intArrayOf(R.id.w_name1, R.id.w_price1, R.id.w_change1, R.id.w_chart1),
            intArrayOf(R.id.w_name2, R.id.w_price2, R.id.w_change2, R.id.w_chart2),
            intArrayOf(R.id.w_name3, R.id.w_price3, R.id.w_change3, R.id.w_chart3),
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

            RatesRepository.PAIRS.forEachIndexed { i, symbol ->
                val (nameId, priceId, changeId, chartId) = ROWS[i].toList()
                views.setTextViewText(nameId, symbol)
                val pair = snapshot?.pairs?.find { it.symbol == symbol }
                if (pair == null) {
                    views.setTextViewText(priceId, "—")
                    views.setTextViewText(changeId, "")
                    views.setImageViewBitmap(chartId, Ui.chart(emptyList(), 1, 1, 0, 1f))
                } else {
                    val color = Ui.changeColor(pair.change)
                    views.setTextViewText(priceId, Ui.price(pair.price))
                    views.setTextViewText(changeId, Ui.change(pair))
                    views.setTextColor(changeId, color)
                    views.setImageViewBitmap(chartId, Ui.chart(pair.points, 360, 100, color, 3f))
                }
            }

            val status = when {
                loading -> context.getString(R.string.updating)
                snapshot?.error != null -> "⚠ " + snapshot.error
                else -> context.getString(R.string.updated_at, Ui.time(snapshot?.updatedAt ?: 0))
            }
            views.setTextViewText(R.id.w_status, status)

            val refresh = Intent(context, RatesWidget::class.java).setAction(ACTION_REFRESH)
            views.setOnClickPendingIntent(
                R.id.w_refresh,
                PendingIntent.getBroadcast(
                    context, 0, refresh,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            val open = Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(
                R.id.w_rows,
                PendingIntent.getActivity(
                    context, 1, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return views
        }
    }
}
