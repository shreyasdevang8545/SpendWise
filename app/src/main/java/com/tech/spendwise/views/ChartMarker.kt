package com.tech.spendwise.views

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.tech.spendwise.R

class ChartMarker(context: Context, layoutResource: Int) : MarkerView(context, layoutResource) {

    private val tvDate: TextView = findViewById(R.id.tvMarkerDate)
    private val tvValue: TextView = findViewById(R.id.tvMarkerValue)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e == null) return

        // If data stores strings/dates in data object, use it. 
        // Otherwise, format value based on X index if it's a date.
        val label = e.data as? String ?: ""
        tvDate.text = label
        tvValue.text = "₹${e.y.toInt()}"

        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF {
        return MPPointF(-(width / 2).toFloat(), -height.toFloat())
    }
}
