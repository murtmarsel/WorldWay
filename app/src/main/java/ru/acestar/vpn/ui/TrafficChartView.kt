package ru.acestar.vpn.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ru.acestar.vpn.R
import kotlin.math.max

class TrafficChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.text_secondary)
        alpha = 60
    }

    private var points: List<Float> = emptyList()

    fun setPoints(values: List<Float>) {
        points = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawLine(0f, h, w, h, gridPaint)
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, gridPaint)

        if (points.size < 2) return

        val maxValue = max(points.maxOrNull() ?: 1f, 1f)
        val dx = if (points.size == 1) w else w / (points.size - 1)
        val path = Path()

        points.forEachIndexed { index, value ->
            val x = index * dx
            val normalized = (value / maxValue).coerceIn(0f, 1f)
            val y = h - normalized * h
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(path, linePaint)
    }
}
