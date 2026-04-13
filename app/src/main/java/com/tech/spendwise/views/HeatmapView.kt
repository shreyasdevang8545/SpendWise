package com.tech.spendwise.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.tech.spendwise.R
import java.util.*

class HeatmapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headers = listOf("M", "T", "W", "T", "F", "S", "S")
    
    // Day spent amount mapping: Day of Month -> Intensity (0.0 to 1.0)
    private var data: Map<Int, Float> = emptyMap()
    private var daysInMonth = 31
    private var firstDayOfWeek = Calendar.MONDAY // 1=Sun, 2=Mon... in Java Calendar
    
    private var dynamicSquareSize = 40f
    private val spacing = 8f
    private val cornerRadius = 8f

    init {
        textPaint.color = Color.parseColor("#80FFFFFF")
        textPaint.textSize = 30f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = ResourcesCompat.getFont(context, R.font.nunito_sans)
    }

    fun setData(monthData: Map<Int, Float>, days: Int, startDay: Int) {
        this.data = monthData
        this.daysInMonth = days
        this.firstDayOfWeek = startDay
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = width - paddingLeft - paddingRight
        
        // Calculate square size to fit 7 columns perfectly
        dynamicSquareSize = (availableWidth - (spacing * 6)) / 7
        
        val rows = Math.ceil((daysInMonth + (if (firstDayOfWeek == Calendar.SUNDAY) 6 else firstDayOfWeek - 2).coerceAtLeast(0)).toDouble() / 7).toInt()
        val height = (dynamicSquareSize * (rows + 1)) + (spacing * rows) + paddingTop + paddingBottom
        setMeasuredDimension(width, height.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw Headers
        val startX = paddingLeft.toFloat()
        val startY = paddingTop.toFloat() + 40f
        
        for (i in 0 until 7) {
            canvas.drawText(headers[i], startX + (dynamicSquareSize / 2) + i * (dynamicSquareSize + spacing), startY - 10f, textPaint)
        }

        // Draw Squares
        val offset = if (firstDayOfWeek == Calendar.SUNDAY) 6 else firstDayOfWeek - 2
        
        for (day in 1..daysInMonth) {
            val pos = day + offset - 1
            val col = pos % 7
            val row = pos / 7
            
            val left = startX + col * (dynamicSquareSize + spacing)
            val top = startY + row * (dynamicSquareSize + spacing)
            val right = left + dynamicSquareSize
            val bottom = top + dynamicSquareSize
            
            val intensity = data[day] ?: 0f
            squarePaint.color = getIntensityColor(intensity)
            
            canvas.drawRoundRect(RectF(left, top, right, bottom), cornerRadius, cornerRadius, squarePaint)
        }
    }

    private fun getIntensityColor(intensity: Float): Int {
        if (intensity <= 0f) return Color.parseColor("#1AFFFFFF")
        // Green intensity: #E8F5E9 to #2E7D32
        val r = (232 - (232 - 46) * intensity).toInt()
        val g = (245 - (245 - 125) * intensity).toInt()
        val b = (233 - (233 - 50) * intensity).toInt()
        return Color.rgb(r, g, b)
    }
}
