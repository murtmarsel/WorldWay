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
    data class TrafficPoint(val timestampMs: Long, val bytesPerSecond: Float)

    companion object {
        private const val WINDOW_MS = 60 * 60 * 1000L
        private const val HEADROOM = 1.1f
    }

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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = resources.displayMetrics.scaledDensity * 10
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    private var points: List<TrafficPoint> = emptyList()

    fun setPoints(values: List<TrafficPoint>) {
        points = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val left = resources.displayMetrics.density * 40
        val top = resources.displayMetrics.density * 8
        val right = width.toFloat() - resources.displayMetrics.density * 8
        val bottom = height.toFloat() - resources.displayMetrics.density * 16
        val chartW = right - left
        val chartH = bottom - top
        if (chartW <= 0f || chartH <= 0f) return

        val now = System.currentTimeMillis()
        val minTs = now - WINDOW_MS
        val visiblePoints = points.filter { it.timestampMs >= minTs }

        val rawMax = max(visiblePoints.maxOfOrNull { it.bytesPerSecond } ?: 0f, 1f)
        val chartMax = rawMax * HEADROOM

        // Y axis + grid with max/half/zero.
        val yTop = top
        val yMid = top + chartH / 2f
        val yBottom = bottom
        canvas.drawLine(left, yTop, right, yTop, gridPaint)
        canvas.drawLine(left, yMid, right, yMid, gridPaint)
        canvas.drawLine(left, yBottom, right, yBottom, gridPaint)

        val maxLabel = formatSpeed(chartMax)
        val midLabel = formatSpeed(chartMax / 2f)
        val zeroLabel = "0 B/s"
        canvas.drawText(maxLabel, 0f, yTop + textPaint.textSize, textPaint)
        canvas.drawText(midLabel, 0f, yMid + textPaint.textSize / 2f, textPaint)
        canvas.drawText(zeroLabel, 0f, yBottom, textPaint)

        // X axis labels: last 60 minutes.
        canvas.drawText("60м", left, height - resources.displayMetrics.density * 2, textPaint)
        canvas.drawText("30м", left + chartW / 2f - resources.displayMetrics.density * 8, height - resources.displayMetrics.density * 2, textPaint)
        canvas.drawText("Сейчас", right - resources.displayMetrics.density * 28, height - resources.displayMetrics.density * 2, textPaint)

        if (visiblePoints.size < 2) return

        val path = Path()

        visiblePoints.forEachIndexed { index, point ->
            val xFraction = ((point.timestampMs - minTs).toFloat() / WINDOW_MS.toFloat()).coerceIn(0f, 1f)
            val x = left + xFraction * chartW
            val normalized = (point.bytesPerSecond / chartMax).coerceIn(0f, 1f)
            val y = bottom - normalized * chartH
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(path, linePaint)
    }

    private fun formatSpeed(bytesPerSec: Float): String {
        val kb = 1024f
        val mb = kb * 1024f
        return when {
            bytesPerSec >= mb -> String.format("%.1f MB/s", bytesPerSec / mb)
            bytesPerSec >= kb -> String.format("%.1f KB/s", bytesPerSec / kb)
            else -> String.format("%.0f B/s", bytesPerSec)
        }
    }
}
