package com.tech.spendwise

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import com.tech.spendwise.SettingsManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarDataSet
import com.tech.spendwise.databinding.FragmentAnalyticsBinding
import com.tech.spendwise.views.ChartMarker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AnalyticsViewModel by activityViewModels()
    private val transactionViewModel: TransactionViewModel by activityViewModels()

    private var currentMonthsList: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hide UI initially to prevent flickering before gate
        binding.root.visibility = View.INVISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val settingsManager = SettingsManager(requireContext())
            settingsManager.isProUser.collect { isPro ->
                if (!isPro) {
                    val navOptions = androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.analyticsFragment, true)
                        .build()
                    findNavController().navigate(R.id.premiumFragment, null, navOptions)
                } else {
                    // Only initialize and show UI if Pro
                    binding.root.visibility = View.VISIBLE
                    initializeFeatures()
                }
            }
        }
    }

    private fun initializeFeatures() {
        observeData()
        setupCharts()



        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupPeriodSelector(availableMonths: List<String>) {
        if (availableMonths == currentMonthsList) return // No change
        currentMonthsList = availableMonths
        
        binding.periodChipGroup.removeAllViews()
        if (availableMonths.isEmpty()) return

        val sdfSource = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val sdfDisplay = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        
        availableMonths.forEachIndexed { index, monthStr ->
            try {
                val date = sdfSource.parse(monthStr) ?: return@forEachIndexed
                val cal = Calendar.getInstance().apply { time = date }
                
                val month = cal.get(Calendar.MONTH)
                val year = cal.get(Calendar.YEAR)
                
                val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                    text = sdfDisplay.format(date)
                    isCheckable = true
                    id = View.generateViewId()
                    
                    // Logic for initial selection:
                    // If viewModel has a selected month, try to match it.
                    val isSelected = if (viewModel.selectedMonth.value != null) {
                        viewModel.selectedMonth.value == month && viewModel.selectedYear.value == year
                    } else {
                        index == 0
                    }
                    
                    if (isSelected) {
                        isChecked = true
                        viewModel.setMonth(month, year)
                    }

                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            viewModel.setMonth(month, year)
                        }
                    }
                }
                binding.periodChipGroup.addView(chip)
            } catch (e: Exception) {}
        }
    }

    private fun formatMarkdown(text: String): CharSequence {
        val spannable = SpannableStringBuilder(text)
        val regex = Regex("\\*\\*(.*?)\\*\\*")
        var match = regex.find(spannable)
        while (match != null) {
            val start = match.range.first
            val end = match.range.last + 1
            val innerText = match.groupValues[1]
            
            spannable.replace(start, end, innerText)
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                start + innerText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            match = regex.find(spannable)
        }
        return spannable
    }

    private var currencySymbol = "₹"

    private fun observeData() {
        transactionViewModel.allTransactions.observe(viewLifecycleOwner) { list ->
            viewModel.updateTransactions(list)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val sm = SettingsManager(requireContext())
            sm.currency.collect { symbol ->
                currencySymbol = symbol
                updateStats()
            }
        }

        viewModel.transactions.observe(viewLifecycleOwner) { transactions ->
            // Extract unique months (YYYY-MM)
            val uniqueMonths = transactions.mapNotNull { t ->
                if (t.savedAt.length >= 7) t.savedAt.substring(0, 7) else null
            }.distinct().sortedDescending()
            
            setupPeriodSelector(uniqueMonths)
            
            updateStats()
            updateCharts()
            if (transactions.isNotEmpty()) {
                // No automatic insights generation for now
            }
        }

        viewModel.selectedMonth.observe(viewLifecycleOwner) {
            updateStats()
            updateCharts()
        }


    }

    private fun updateStats() {
        val spent = viewModel.getMonthlySpending()
        val income = viewModel.getMonthlyIncome()
        val avg = viewModel.getDailyAverage()

        binding.tvTotalSpent.text = "$currencySymbol${spent.toInt()}"
        
        // Dynamic budget coloring (e.g., alert if spent > 80% of income)
        if (income > 0 && spent > income * 0.8) {
            binding.tvTotalSpent.setTextColor(Color.parseColor("#FB7185")) // spent_indicator red
        } else {
            binding.tvTotalSpent.setTextColor(Color.WHITE)
        }

        val rate = if (income > 0) {
            (((income - spent) / income) * 100).toInt()
        } else if (spent > 0) {
            -100 // Indicate negative savings when there's spending but no income
        } else {
            0
        }
        binding.tvSavingsRate.text = "$rate%"
        binding.tvSavingsRate.setTextColor(if (rate > 30) Color.parseColor("#4ADE80") else if (rate > 10) Color.YELLOW else Color.parseColor("#FB7185"))

        binding.tvDailyAverage.text = "$currencySymbol${avg.toInt()}"

        val cal = Calendar.getInstance()
        val currentMonth = cal.get(Calendar.MONTH)
        val currentYear = cal.get(Calendar.YEAR)
        val selMonth = viewModel.selectedMonth.value ?: 0
        val selYear = viewModel.selectedYear.value ?: 0

        val forecasted = if (selMonth == currentMonth && selYear == currentYear) {
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val currentDay = cal.get(Calendar.DAY_OF_MONTH)
            val remainingDays = (daysInMonth - currentDay).coerceAtLeast(0)
            spent + (avg * remainingDays)
        } else {
            spent
        }

        binding.tvForecasted.text = "$currencySymbol${forecasted.toInt()}"
    }

    private fun setupCharts() {
        // LineChart
        binding.spendingTrendChart.apply {
            setDrawGridBackground(false)
            setDrawBorders(false)
            description.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            setScaleEnabled(true)
            animateY(1000)
        }

        // PieChart
        binding.categoryPieChart.apply {
            description.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            setHoleColor(Color.TRANSPARENT)
            setCenterTextColor(Color.WHITE)
            animateY(1000)
        }

        // Bar Chart
        binding.weekdayBarChart.apply {
            setDrawGridBackground(false)
            description.isEnabled = false
            legend.isEnabled = true
            setTouchEnabled(true)
            setScaleEnabled(false)
            animateY(1000)
        }
    }

    private fun updateCharts() {
        val transactions = viewModel.transactions.value ?: return
        val marker = ChartMarker(requireContext(), R.layout.layout_chart_marker)

        // 4. LINE CHART (Daily Spending Trend)
        val dailyTrend = viewModel.getDailyTrend()
        val lineEntries = dailyTrend.map { pair ->
            com.github.mikephil.charting.data.Entry(
                pair.first.toFloat(),
                pair.second.toFloat(),
                "${pair.first} ${viewModel.selectedMonth.value}"
            )
        }
        val lineDataSet =
            com.github.mikephil.charting.data.LineDataSet(lineEntries, "Daily Spending").apply {
                color = Color.parseColor("#43A047")
                setDrawFilled(true)
                fillAlpha = 50
                fillColor = Color.parseColor("#43A047")
                mode = com.github.mikephil.charting.data.LineDataSet.Mode.HORIZONTAL_BEZIER
                setDrawCircles(true)
                setCircleColor(Color.parseColor("#43A047"))
                circleRadius = 4f
                lineWidth = 2f
                setDrawValues(false)
            }
        binding.spendingTrendChart.apply {
            data = com.github.mikephil.charting.data.LineData(lineDataSet)
            this.marker = marker
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textColor = Color.WHITE
            axisLeft.textColor = Color.WHITE
            axisRight.isEnabled = false
            invalidate()
        }

        // 5. PIE CHART (Category Breakdown)
        val categoryData = viewModel.getCategoryData()
        val total = categoryData.values.sum()
        var colors = getCategoryColors(categoryData.keys.toList())

        val pieEntries = categoryData.map {
            com.github.mikephil.charting.data.PieEntry(it.value.toFloat(), "") // No text on slices
        }
        val pieDataSet = com.github.mikephil.charting.data.PieDataSet(pieEntries, "").apply {
            this.colors = colors
            setDrawValues(false) // Hide values on slices
            sliceSpace = 3f
        }
        binding.categoryPieChart.apply {
            data = com.github.mikephil.charting.data.PieData(pieDataSet)
            centerText = "Total\n₹${total.toInt()}"
            setEntryLabelColor(Color.TRANSPARENT)
            setDrawEntryLabels(false)
            description.isEnabled = false
            legend.isEnabled = false // Hide default legend as we have a list now
            this.marker = marker
            invalidate()
        }

        // 5b. Category List
        val categoriesList = categoryData.keys.toList()
        val listData = categoriesList.mapIndexed { index, cat ->
            val amount = categoryData[cat] ?: 0.0
            CategoryItem(cat, amount, (amount / total * 100).toInt(), colors[index])
        }.sortedByDescending { it.amount }

        binding.rvCategoryBreakdown.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = CategoryBreakdownAdapter(listData)
        }

        // 6. DAILY HEATMAP (Accuracy verified)
        val cal = Calendar.getInstance()
        cal.set(viewModel.selectedYear.value!!, viewModel.selectedMonth.value!!, 1)
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val startDay = cal.get(Calendar.DAY_OF_WEEK)
        binding.dailyHeatmap.setData(viewModel.getHeatmapData(), days, startDay)

        // 7. MONTH COMPARISON (Redesigned List)
        val comparison = viewModel.getComparisonData()
        val thisMonthMap = comparison.first
        val lastMonthMap = comparison.second
        val categories = (thisMonthMap.keys + lastMonthMap.keys).distinct().sorted()

        val comparisonData = categories.map { cat ->
            val thisM = thisMonthMap[cat] ?: 0.0
            val lastM = lastMonthMap[cat] ?: 0.0
            ComparisonItem(cat, thisM, lastM)
        }.filter { it.thisMonth > 0 || it.lastMonth > 0 }
         .sortedByDescending { it.thisMonth }

        // Update Summary Delta
        val totalThis = thisMonthMap.values.sum()
        val totalLast = lastMonthMap.values.sum()
        val totalDelta = totalThis - totalLast
        val totalPercent = if (totalLast > 0) ((totalDelta / totalLast) * 100).toInt() else 0
        
        binding.tvTotalComparisonDelta.apply {
            text = if (totalDelta >= 0) "+₹${totalDelta.toInt()} ($totalPercent%)" else "-₹${Math.abs(totalDelta).toInt()} (${Math.abs(totalPercent)}%)"
            setTextColor(if (totalDelta <= 0) Color.GREEN else Color.RED)
        }

        binding.rvMonthlyComparison.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = MonthlyComparisonAdapter(comparisonData)
        }

        // 8. TOP MERCHANTS LIST
        val topMerchants = viewModel.getTopMerchants()
        binding.rvTopMerchants.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopMerchants.adapter = TopMerchantsAdapter(topMerchants)

        // 9. WEEKDAY PATTERN (Bar Chart)
        val weekdayPattern = viewModel.getWeekdayPattern()
        val weekdayLabels = listOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        
        // Find max day for observation text
        val maxDayEntry = weekdayPattern.maxByOrNull { it.value }
        if (maxDayEntry != null && maxDayEntry.value > 0) {
            val dayName = when(maxDayEntry.key) {
                1 -> "Sunday"
                2 -> "Monday"
                3 -> "Tuesday"
                4 -> "Wednesday"
                5 -> "Thursday"
                6 -> "Friday"
                7 -> "Saturday"
                else -> "N/A"
            }
            binding.tvWeekdayObservation.text = "You spend most on $dayName"
        } else {
            binding.tvWeekdayObservation.text = "Not enough data to determine a pattern"
        }

        val weekdayEntries = (1..7).map { dow ->
            com.github.mikephil.charting.data.BarEntry(
                dow.toFloat(),
                weekdayPattern[dow]?.toFloat() ?: 0f
            )
        }
        val weekdayDataSet = BarDataSet(weekdayEntries, "Spending Pattern").apply {
            colors = (1..7).map { Color.parseColor("#43A047") }
            valueTextColor = Color.WHITE
            setDrawValues(false)
        }
        binding.weekdayBarChart.apply {
            data = com.github.mikephil.charting.data.BarData(weekdayDataSet)
            xAxis.apply {
                valueFormatter = com.github.mikephil.charting.formatter.IndexAxisValueFormatter(weekdayLabels)
                textColor = Color.WHITE
                position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                granularity = 1f
                labelCount = 7
            }
            axisLeft.textColor = Color.WHITE
            axisRight.isEnabled = false
            description.isEnabled = false
            legend.isEnabled = false
            this.marker = marker
            invalidate()
        }
    }

    private data class ComparisonItem(val category: String, val thisMonth: Double, val lastMonth: Double)

    private inner class MonthlyComparisonAdapter(private val items: List<ComparisonItem>) :
        RecyclerView.Adapter<MonthlyComparisonAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvCategoryName)
            val delta: TextView = view.findViewById(R.id.tvDelta)
            val pbThis: com.google.android.material.progressindicator.LinearProgressIndicator = view.findViewById(R.id.pbThisMonth)
            val pbLast: com.google.android.material.progressindicator.LinearProgressIndicator = view.findViewById(R.id.pbLastMonth)
            val amountThis: TextView = view.findViewById(R.id.tvThisMonthAmount)
            val amountLast: TextView = view.findViewById(R.id.tvLastMonthAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_comparison_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.category
            holder.amountThis.text = "₹${item.thisMonth.toInt()}"
            holder.amountLast.text = "₹${item.lastMonth.toInt()}"

            val maxVal = Math.max(item.thisMonth, item.lastMonth).toFloat().coerceAtLeast(1f)
            holder.pbThis.progress = ((item.thisMonth / maxVal) * 100).toInt()
            holder.pbLast.progress = ((item.lastMonth / maxVal) * 100).toInt()

            val diff = item.thisMonth - item.lastMonth
            val percent = if (item.lastMonth > 0) ((diff / item.lastMonth) * 100).toInt() else 0
            
            if (diff >= 0) {
                holder.delta.text = "+$percent%"
                holder.delta.setTextColor(Color.RED)
            } else {
                holder.delta.text = "$percent%"
                holder.delta.setTextColor(Color.GREEN)
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class CategoryBreakdownAdapter(private val items: List<CategoryItem>) :
        RecyclerView.Adapter<CategoryBreakdownAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val color: View = view.findViewById(R.id.vColorIndicator)
            val name: TextView = view.findViewById(R.id.tvCategoryName)
            val amount: TextView = view.findViewById(R.id.tvCategoryAmount)
            val percent: TextView = view.findViewById(R.id.tvCategoryPercent)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_breakdown, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.color.setBackgroundColor(item.color)
            holder.name.text = item.label
            holder.amount.text = "₹${item.amount.toInt()}"
            holder.percent.text = "${item.percent}%"
        }

        override fun getItemCount() = items.size
    }

    private data class CategoryItem(
        val label: String,
        val amount: Double,
        val percent: Int,
        val color: Int
    )

    private inner class TopMerchantsAdapter(private val items: List<Triple<String, Int, Double>>) :
        RecyclerView.Adapter<TopMerchantsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val rank: TextView = view.findViewById(R.id.tvRank)
            val name: TextView = view.findViewById(R.id.tvMerchantName)
            val visits: TextView = view.findViewById(R.id.tvVisitCount)
            val amount: TextView = view.findViewById(R.id.tvAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_top_merchant, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (merchant, count, total) = items[position]
            holder.rank.text = (position + 1).toString()
            holder.name.text = merchant
            holder.visits.text = "$count visits"
            holder.amount.text = "₹${total.toInt()}"
        }

        override fun getItemCount() = items.size
    }

    private fun getCategoryColors(categories: List<String>): List<Int> {
        val colors = listOf(
            "#4CAF50", "#2196F3", "#FF9800", "#E91E63", "#9C27B0",
            "#3F51B5", "#00BCD4", "#8BC34A", "#FFC107", "#795548"
        )
        return categories.mapIndexed { index, _ -> Color.parseColor(colors[index % colors.size]) }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
