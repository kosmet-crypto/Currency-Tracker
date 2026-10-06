package com.kosmet.currency

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

    fun changeColor(change: Double) = if (change >= 0) GREEN else RED

    fun price(v: Double): String = String.format(Locale.US, "%.4f", v)

    fun change(p: PairQuote): String =
        String.format(Locale.US, "%+.4f (%+.2f%%)", p.change, p.changePct)

    fun time(millis: Long): String =
        if (millis <= 0) "—" else SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(millis))

    /** Line chart with a dashed line at the starting value, like the Yahoo app. */
    fun chart(points: List<Double>, w: Int, h: Int, color: Int, stroke: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (points.size < 2) return bmp
        val canvas = Canvas(bmp)
        val min = points.min()
        val max = points.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val pad = stroke * 2
        fun x(i: Int) = pad + i * (w - 2 * pad) / (points.size - 1)
        fun y(v: Double) = pad + ((max - v) / range * (h - 2 * pad)).toFloat()

        val line = Path()
        points.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }

        val fill = Path(line).apply {
            lineTo(x(points.size - 1), h.toFloat())
            lineTo(x(0), h.toFloat())
            close()
        }
        val fillColor = (color and 0x00FFFFFF) or 0x33000000
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), fillColor, 0, Shader.TileMode.CLAMP)
        })

        val baseY = y(points.first())
        canvas.drawLine(0f, baseY, w.toFloat(), baseY, Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        return bmp
    }
}
