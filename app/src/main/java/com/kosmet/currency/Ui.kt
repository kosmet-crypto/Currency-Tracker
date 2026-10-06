package com.kosmet.currency

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Ui {
    const val GREEN = 0xFF2E7D5B.toInt()
    const val RED = 0xFFC62828.toInt()
    const val GREY = 0xFF8A8F98.toInt()

    fun changeColor(p: PairQuote) = if (p.points.isEmpty()) GREY else if (p.change >= 0) GREEN else RED

    /** Four decimals for normal rates, fewer for big ones like USD/RSD, more for tiny ones. */
    fun price(v: Double): String = String.format(Locale.US, if (v >= 100) "%.2f" else if (v >= 0.1) "%.4f" else "%.6f", v)

    fun change(ctx: Context, p: PairQuote): String =
        if (p.points.isEmpty()) ctx.getString(R.string.daily_rate) else change(p.price, p.base)

    fun change(value: Double, base: Double): String {
        val diff = value - base
        val pct = if (base != 0.0) diff / base * 100 else 0.0
        return String.format(Locale.US, if (value < 0.1) "%+.6f (%+.2f%%)" else "%+.4f (%+.2f%%)", diff, pct)
    }

    /** Time only for today, date and time otherwise. */
    fun shortTime(millis: Long): String {
        if (millis <= 0) return "—"
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val pattern = if (day.format(Date(millis)) == day.format(Date())) "HH:mm" else "dd.MM. HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    }

    fun time(millis: Long): String =
        if (millis <= 0) "—" else SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))

    fun chart(p: PairQuote?, w: Int, h: Int, stroke: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (p != null) drawChart(Canvas(bmp), p, w.toFloat(), h.toFloat(), stroke)
        return bmp
    }

    class Geometry(val points: List<Double>, val base: Double, val w: Float, val h: Float, val pad: Float) {
        private val min = minOf(points.min(), base)
        private val max = maxOf(points.max(), base)
        private val range = (max - min).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = pad + i * (w - 2 * pad) / (points.size - 1)
        fun y(v: Double) = pad + ((max - v) / range * (h - 2 * pad)).toFloat()
        fun index(x: Float) = ((x - pad) / (w - 2 * pad) * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
    }

    /** Line chart with a dashed line at the previous close, like the Yahoo app. Null if too few points. */
    fun drawChart(canvas: Canvas, p: PairQuote, w: Float, h: Float, stroke: Float): Geometry? {
        val points = p.points
        if (points.size < 2) return null
        val color = changeColor(p)
        val g = Geometry(points, p.base, w, h, stroke * 2)

        val line = Path()
        points.forEachIndexed { i, v -> if (i == 0) line.moveTo(g.x(i), g.y(v)) else line.lineTo(g.x(i), g.y(v)) }

        val fill = Path(line).apply {
            lineTo(g.x(points.size - 1), h)
            lineTo(g.x(0), h)
            close()
        }
        val fillColor = (color and 0x00FFFFFF) or 0x33000000
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h, fillColor, 0, Shader.TileMode.CLAMP)
        })

        val baseY = g.y(p.base)
        canvas.drawLine(0f, baseY, w, baseY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = GREY
            strokeWidth = stroke / 2
            pathEffect = DashPathEffect(floatArrayOf(stroke * 3, stroke * 2), 0f)
        })

        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeJoin = Paint.Join.ROUND
        })
        return g
    }
}
