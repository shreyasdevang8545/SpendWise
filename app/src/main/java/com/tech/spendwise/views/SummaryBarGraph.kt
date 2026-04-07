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
    private var selectedIndex: Int = -1
    private var tooltipAlpha = 0f
    
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f // Will be scaled in onDraw
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#80FFFFFF") // Semi-transparent white
    }
    
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E61E1E1E") // Dark glass color
        style = Paint.Style.FILL
    }
    
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.05f
        color = Color.WHITE
        isFakeBoldText = true
    }

    private val positiveColor = ContextCompat.getColor(context, R.color.income_indicator)
    private val negativeColor = ContextCompat.getColor(context, R.color.spent_indicator)
    private val barWidthPercent = 0.6f // Width of bar relative to column width
    private val barCornerRadius = 12f
    private val bottomTextMargin = 40f

    fun setData(newData: List<BarData>) {
        this.data = newData
        this.selectedIndex = -1
        this.tooltipAlpha = 0f
        invalidate()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            val columnWidth = width.toFloat() / data.size
            val index = (event.x / columnWidth).toInt()
            if (index in data.indices) {
                if (selectedIndex == index) {
                    selectedIndex = -1
                    tooltipAlpha = 0f
                } else {
                    selectedIndex = index
                    // Animate tooltip alpha
                    android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 200
                        addUpdateListener {
                            tooltipAlpha = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
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
            val totalPeriodAmount = if (bar.income >= bar.spent) bar.income else bar.spent
            val isPositive = bar.income >= bar.spent
            
            val barAlpha = if (selectedIndex == -1 || selectedIndex == index) 255 else 80
            val barHeight = ((totalPeriodAmount / maxValue) * graphHeight).toFloat()
            
            val rect = RectF(
                centerX - (barWidth / 2),
                graphHeight - barHeight,
                centerX + (barWidth / 2),
                graphHeight
            )

            barPaint.color = if (isPositive) positiveColor else negativeColor
            barPaint.alpha = barAlpha
            canvas.drawRoundRect(rect, barCornerRadius, barCornerRadius, barPaint)
            
            // Draw Tooltip if selected
            if (selectedIndex == index) {
                drawTooltip(canvas, centerX, graphHeight - barHeight - 15f, totalPeriodAmount, isPositive)
            }
        }
    }

    private fun drawTooltip(canvas: Canvas, x: Float, y: Float, amount: Double, isPositive: Boolean) {
        val amountStr = "₹ %.0f".format(amount)
        tooltipTextPaint.textSize = 34f
        val textWidth = tooltipTextPaint.measureText(amountStr)
        val padding = 24f
        
        val alphaInt = (tooltipAlpha * 255).toInt()
        
        val bgRect = RectF(
            x - (textWidth / 2) - padding,
            y - 70f,
            x + (textWidth / 2) + padding,
            y
        )
        
        // Use a temporary paint to apply alpha to the bubble
        val tempPaint = Paint(tooltipPaint).apply { alpha = (tooltipAlpha * 230).toInt() }
        canvas.drawRoundRect(bgRect, 20f, 20f, tempPaint)
        
        // Draw Amount Text with alpha
        val textCol = if (isPositive) positiveColor else negativeColor
        tooltipTextPaint.color = textCol
        tooltipTextPaint.alpha = alphaInt
        canvas.drawText(amountStr, x, y - 22f, tooltipTextPaint)
    }
}


