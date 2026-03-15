package com.tech.spendwise.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.tech.spendwise.R

/**
 * A custom bar graph view for the SpendWise dashboard.
 * Visualizes a sequence of financial periods with income (positive) and expenses (negative).
 */
class SummaryBarGraph @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BarData(
        val label: String,
        val income: Double,
        val spent: Double
    )

    private var data: List<BarData> = emptyList()
    
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f // Will be scaled in onDraw
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#80FFFFFF") // Semi-transparent white
    }

    private val positiveColor = ContextCompat.getColor(context, R.color.income_indicator)
    private val negativeColor = ContextCompat.getColor(context, R.color.spent_indicator)
    private val barWidthPercent = 0.6f // Width of bar relative to column width
    private val barCornerRadius = 12f
    private val bottomTextMargin = 40f

    fun setData(newData: List<BarData>) {
        this.data = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val graphHeight = height - bottomTextMargin - 20f
        
        val columnWidth = width / data.size
        val barWidth = columnWidth * barWidthPercent
        
        // Find max absolute value to scale bars correctly
        val maxValue = data.maxOf { Math.max(it.income, it.spent) }.coerceAtLeast(1.0)

        // Adjust text size based on view size roughly
        textPaint.textSize = Math.min(columnWidth * 0.4f, 32f)

        data.forEachIndexed { index, bar ->
            val centerX = (index * columnWidth) + (columnWidth / 2)
            
            // Draw Label
            canvas.drawText(bar.label, centerX, height - 10f, textPaint)

            // Calculate heights
            // We'll show the larger of the two as the main bar, or maybe stacked?
            // The reference image shows a single bar per day.
            // Let's draw the NET balance as the bar, or the max?
            // User's reference shows red vs green bars.
            val totalPeriodAmount = if (bar.income >= bar.spent) bar.income else bar.spent
            val isPositive = bar.income >= bar.spent
            
            val barHeight = ((totalPeriodAmount / maxValue) * graphHeight).toFloat()
            
            val rect = RectF(
                centerX - (barWidth / 2),
                graphHeight - barHeight,
                centerX + (barWidth / 2),
                graphHeight
            )

            barPaint.color = if (isPositive) positiveColor else negativeColor
            canvas.drawRoundRect(rect, barCornerRadius, barCornerRadius, barPaint)
        }
    }
}
