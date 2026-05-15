package com.tech.spendwise

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentMonthlyReportBinding
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MonthlyReportFragment : Fragment() {

    private var _binding: FragmentMonthlyReportBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TransactionViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMonthlyReportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        setupSpinners()

        binding.btnGenerate.setOnClickListener {
            generateReport()
        }

        binding.btnRegenerate.setOnClickListener {
            generateReport()
        }
        
        checkFileExists()
    }

    private fun setupSpinners() {
        val months = arrayOf(
            getString(R.string.month_january), getString(R.string.month_february), getString(R.string.month_march),
            getString(R.string.month_april), getString(R.string.month_may), getString(R.string.month_june),
            getString(R.string.month_july), getString(R.string.month_august), getString(R.string.month_september),
            getString(R.string.month_october), getString(R.string.month_november), getString(R.string.month_december)
        )
        val monthAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, months)
        binding.spinnerMonth.setAdapter(monthAdapter)
        
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH)
        binding.spinnerMonth.setText(months[currentMonth], false)

        val years = mutableListOf<String>()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        for (i in 0..5) {
            years.add((currentYear - i).toString())
        }
        val yearAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, years)
        binding.spinnerYear.setAdapter(yearAdapter)
        binding.spinnerYear.setText(currentYear.toString(), false)
        
        val listener = AdapterView.OnItemClickListener { _, _, _, _ -> checkFileExists() }
        binding.spinnerMonth.onItemClickListener = listener
        binding.spinnerYear.onItemClickListener = listener
    }

    private fun getReportFile(): File {
        val month = binding.spinnerMonth.text.toString()
        val year = binding.spinnerYear.text.toString()
        val dir = File(requireContext().filesDir, "reports")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Report_${month}_${year}.pdf")
    }

    private fun checkFileExists() {
        if (_binding == null) return
        val file = getReportFile()
        if (file.exists()) {
            binding.llFileExists.visibility = View.VISIBLE
            binding.btnGenerate.visibility = View.GONE
        } else {
            binding.llFileExists.visibility = View.GONE
            binding.btnGenerate.visibility = View.VISIBLE
        }
    }

    private fun generateReport() {
        val selectedMonthName = binding.spinnerMonth.text.toString()
        val selectedYear = binding.spinnerYear.text.toString().toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
        
        val months = arrayOf(
            getString(R.string.month_january), getString(R.string.month_february), getString(R.string.month_march),
            getString(R.string.month_april), getString(R.string.month_may), getString(R.string.month_june),
            getString(R.string.month_july), getString(R.string.month_august), getString(R.string.month_september),
            getString(R.string.month_october), getString(R.string.month_november), getString(R.string.month_december)
        )
        val selectedMonthIndex = months.indexOf(selectedMonthName)

        binding.progressBar.visibility = View.VISIBLE
        binding.btnGenerate.isEnabled = false
        binding.llFileExists.visibility = View.GONE

        val observer = object : androidx.lifecycle.Observer<List<String>> {
            override fun onChanged(value: List<String>) {
                viewModel.firestoreTransactions.removeObserver(this)
                viewModel.confirmedTransactions.removeObserver(this)
                
                val cloudList = viewModel.firestoreTransactions.value ?: emptyList()
                val localList = viewModel.confirmedTransactions.value ?: emptyList()
                val combinedList = (cloudList + localList).distinct()
                val filteredTransactions = filterTransactions(combinedList, selectedMonthIndex, selectedYear)

                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true

                if (filteredTransactions.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.msg_no_transactions_found_period, selectedMonthName, selectedYear), Toast.LENGTH_SHORT).show()
                } else {
                    saveAndOpenHtmlReport(filteredTransactions, selectedMonthName, selectedYear)
                }
            }
        }

        viewModel.firestoreTransactions.observe(viewLifecycleOwner, observer)
        viewModel.confirmedTransactions.observe(viewLifecycleOwner, observer)
        viewModel.fetchFromFirestore()
    }

    private fun saveAndOpenHtmlReport(transactions: List<JsonObject>, month: String, year: Int) {
        try {
            val html = buildHtmlReport(transactions, month, year)
            
            binding.webView.settings.javaScriptEnabled = true
            binding.webView.settings.domStorageEnabled = true
            binding.webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            
            binding.webView.visibility = View.VISIBLE
            binding.fabExport.visibility = View.VISIBLE
            
            // Smooth scroll down to the report
            binding.webView.postDelayed({
                binding.nestedScrollView.smoothScrollTo(0, binding.webView.top)
            }, 500)
            
            binding.fabExport.setOnClickListener {
                exportToPdf(month, year)
            }
            
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.error_updating_name, e.localizedMessage ?: getString(R.string.label_unknown)), Toast.LENGTH_LONG).show()
        }
    }

    private fun exportToPdf(month: String, year: Int) {
        try {
            val printManager = requireContext().getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
            val jobName = "SpendWise_Report_${month}_${year}"
            val printAdapter = binding.webView.createPrintDocumentAdapter(jobName)
            printManager.print(jobName, printAdapter, android.print.PrintAttributes.Builder().build())
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to export PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun filterTransactions(jsonList: List<String>, monthIndex: Int, year: Int): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val json = Json { ignoreUnknownKeys = true }
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        
        for (jsonStr in jsonList) {
            try {
                val obj = json.parseToJsonElement(jsonStr).jsonObject
                val savedAtStr = obj["saved_at"]?.jsonPrimitive?.content ?: ""
                val date = sdf.parse(savedAtStr) ?: continue
                val cal = Calendar.getInstance().apply { time = date }
                
                if (cal.get(Calendar.MONTH) == monthIndex && cal.get(Calendar.YEAR) == year) {
                    result.add(obj)
                }
            } catch (e: Exception) {
                try {
                    val obj = json.parseToJsonElement(jsonStr).jsonObject
                    val savedAtLong = obj["saved_at"]?.jsonPrimitive?.long ?: 0L
                    val cal = Calendar.getInstance().apply { timeInMillis = savedAtLong }
                    if (cal.get(Calendar.MONTH) == monthIndex && cal.get(Calendar.YEAR) == year) {
                        result.add(obj)
                    }
                } catch (e2: Exception) {}
            }
        }
        return result.sortedByDescending { 
            val savedAtStr = it["saved_at"]?.jsonPrimitive?.content ?: ""
            try { sdf.parse(savedAtStr)?.time ?: 0L } catch (e: Exception) { it["saved_at"]?.jsonPrimitive?.long ?: 0L }
        }
    }

    private fun buildHtmlReport(transactions: List<JsonObject>, month: String, year: Int): String {
        var totalIncome = 0.0
        var totalExpense = 0.0
        val categoryMap = mutableMapOf<String, Double>()
        val dailyIncomeMap = mutableMapOf<Int, Double>()
        val dailyExpenseMap = mutableMapOf<Int, Double>()

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val displaySdf = SimpleDateFormat("dd MMM", Locale.getDefault())
        val cal = Calendar.getInstance()

        val rows = StringBuilder()

        for (tx in transactions) {
            val amountRaw = tx["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val amount = Math.abs(amountRaw)
            val type = tx["type"]?.jsonPrimitive?.content ?: "DEBIT"
            val isIncome = type.equals("Income", true) || type.equals("CREDIT", true)
            val category = tx["category"]?.jsonPrimitive?.content ?: "Unknown"
            
            val savedAtStr = tx["saved_at"]?.jsonPrimitive?.content ?: ""
            val date = try {
                sdf.parse(savedAtStr) ?: Date(tx["saved_at"]?.jsonPrimitive?.long ?: 0L)
            } catch (e: Exception) {
                Date(tx["saved_at"]?.jsonPrimitive?.long ?: 0L)
            }
            
            cal.time = date
            val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)

            if (isIncome) {
                totalIncome += amount
                dailyIncomeMap[dayOfMonth] = (dailyIncomeMap[dayOfMonth] ?: 0.0) + amount
            } else {
                totalExpense += amount
                categoryMap[category] = (categoryMap[category] ?: 0.0) + amount
                dailyExpenseMap[dayOfMonth] = (dailyExpenseMap[dayOfMonth] ?: 0.0) + amount
            }

            val dateStr = displaySdf.format(date)
            val merchant = tx["merchant"]?.jsonPrimitive?.content ?: "-"
            val color = if (isIncome) "#00E676" else "#FF5252"
            
            rows.append("""
                <tr>
                    <td>$dateStr</td>
                    <td>$merchant</td>
                    <td><span class="category-badge">$category</span></td>
                    <td style="color: $color; font-weight: 700; text-align: right;">${if (isIncome) "+" else "-"}₹${amount.toInt()}</td>
                </tr>
            """.trimIndent())
        }

        val sortedCategories = categoryMap.entries.sortedByDescending { it.value }
        val categoryLabels = sortedCategories.map { "\"${it.key}\"" }.joinToString(",")
        val categoryData = sortedCategories.map { it.value }.joinToString(",")
        
        // Colors for Donut Chart
        val chartColors = listOf(
            "'#6C63FF'", "'#FF6584'", "'#4CAF50'", "'#FFD700'", "'#00BCD4'", 
            "'#FF9800'", "'#9C27B0'", "'#009688'", "'#E91E63'", "'#3F51B5'"
        )
        val categoryColors = sortedCategories.mapIndexed { index, _ -> chartColors[index % chartColors.size] }.joinToString(",")
        
        var maxDay = 31
        try {
            val monthIndex = SimpleDateFormat("MMMM", Locale.getDefault()).parse(month)?.month ?: 0
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, monthIndex)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        } catch (e: Exception) {}

        val daysList = (1..maxDay).toList()
        val dailyIncomeData = daysList.map { dailyIncomeMap[it] ?: 0.0 }.joinToString(",")
        val dailyExpenseData = daysList.map { dailyExpenseMap[it] ?: 0.0 }.joinToString(",")
        val daysLabels = daysList.map { "\"$it\"" }.joinToString(",")

        val savings = totalIncome - totalExpense
        val savingsColor = if (savings >= 0) "#00E676" else "#FF5252"

        val msgGeneratedAt = getString(R.string.msg_generated_by_spendwise, SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date()))

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>SpendWise Report - $month $year</title>
                <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                <link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap" rel="stylesheet">
                <style>
                    * { box-sizing: border-box; }
                    :root {
                        --bg-color: #0F172A;
                        --card-bg: #1E293B;
                        --text-primary: #F8FAFC;
                        --text-secondary: #94A3B8;
                        --accent-green: #00E676;
                        --accent-red: #FF5252;
                        --border-color: #334155;
                    }
                    body { 
                        font-family: 'Inter', sans-serif; 
                        background-color: var(--bg-color); 
                        color: var(--text-primary); 
                        margin: 0; 
                        padding: 16px; 
                        width: 100%;
                    }
                    .dashboard {
                        max-width: 1200px;
                        margin: 0 auto;
                        display: flex;
                        flex-direction: column;
                        gap: 20px;
                        width: 100%;
                    }
                    @media (min-width: 768px) {
                        .dashboard { display: grid; grid-template-columns: repeat(2, 1fr); }
                        .full-width { grid-column: 1 / -1; }
                    }
                    .header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    .title h1 { margin: 0; font-size: 24px; font-weight: 700; color: #fff; }
                    .title p { margin: 4px 0 0; color: var(--text-secondary); font-size: 14px; }
                    .export-btn {
                        background: linear-gradient(135deg, #6366F1, #8B5CF6);
                        color: white;
                        border: none;
                        padding: 10px 20px;
                        border-radius: 8px;
                        font-weight: 600;
                        font-family: 'Inter', sans-serif;
                        cursor: pointer;
                        box-shadow: 0 4px 12px rgba(99, 102, 241, 0.3);
                        transition: transform 0.2s;
                    }
                    .export-btn:active { transform: scale(0.95); }
                    @media print { 
                        .export-btn { display: none; } 
                        * { -webkit-print-color-adjust: exact !important; print-color-adjust: exact !important; } 
                        body { padding: 0; margin: 0; width: 100%; }
                        .dashboard { display: flex; flex-direction: column; width: 100%; gap: 16px; }
                        .card { width: 100%; page-break-inside: avoid; break-inside: avoid; padding: 16px; }
                        .chart-container { height: 250px; }
                        table { font-size: 11px; }
                        th, td { padding: 10px 4px; }
                    }
                    .card {
                        background: var(--card-bg);
                        border-radius: 16px;
                        padding: 24px;
                        box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
                        border: 1px solid var(--border-color);
                    }
                    .summary-grid {
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        gap: 16px;
                    }
                    .summary-item {
                        text-align: center;
                    }
                    .summary-label {
                        font-size: 12px;
                        text-transform: uppercase;
                        letter-spacing: 1px;
                        color: var(--text-secondary);
                        margin-bottom: 8px;
                    }
                    .summary-val {
                        font-size: 24px;
                        font-weight: 700;
                    }
                    .chart-container {
                        position: relative;
                        height: 300px;
                        width: 100%;
                    }
                    .card-title {
                        font-size: 16px;
                        font-weight: 600;
                        margin: 0 0 20px 0;
                        color: var(--text-primary);
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                    }
                    th, td {
                        padding: 16px 12px;
                        text-align: left;
                        border-bottom: 1px solid var(--border-color);
                    }
                    th {
                        color: var(--text-secondary);
                        font-size: 12px;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                    }
                    tr:last-child td { border-bottom: none; }
                    .category-badge {
                        background: rgba(148, 163, 184, 0.1);
                        color: var(--text-secondary);
                        padding: 4px 10px;
                        border-radius: 20px;
                        font-size: 12px;
                        font-weight: 500;
                    }
                    .footer {
                        text-align: center;
                        margin-top: 32px;
                        color: var(--text-secondary);
                        font-size: 12px;
                    }
                </style>
            </head>
            <body>
                <div class="dashboard">
                    <div class="header full-width">
                        <div class="title">
                            <h1>SpendWise Report</h1>
                            <p>$month $year Analytics Dashboard</p>
                        </div>
                    </div>

                    <div class="card full-width summary-grid">
                        <div class="summary-item">
                            <div class="summary-label">Total Income</div>
                            <div class="summary-val" style="color: var(--accent-green);">₹${totalIncome.toInt()}</div>
                        </div>
                        <div class="summary-item">
                            <div class="summary-label">Total Expense</div>
                            <div class="summary-val" style="color: var(--accent-red);">₹${totalExpense.toInt()}</div>
                        </div>
                        <div class="summary-item">
                            <div class="summary-label">Net Savings</div>
                            <div class="summary-val" style="color: $savingsColor;">₹${Math.abs(savings).toInt()}</div>
                        </div>
                    </div>

                    <div class="card">
                        <h2 class="card-title">Expense Breakdown</h2>
                        <div class="chart-container">
                            ${if (totalExpense > 0) "<canvas id='categoryChart'></canvas>" else "<div style='display:flex; height:100%; align-items:center; justify-content:center; color:var(--text-secondary);'>No expenses this month</div>"}
                        </div>
                    </div>

                    <div class="card">
                        <h2 class="card-title">Daily Cash Flow</h2>
                        <div class="chart-container">
                            <canvas id="dailyChart"></canvas>
                        </div>
                    </div>

                    <div class="card full-width">
                        <h2 class="card-title">Transaction History</h2>
                        <div style="overflow-x: auto;">
                            <table>
                                <thead>
                                    <tr>
                                        <th>Date</th>
                                        <th>Merchant</th>
                                        <th>Category</th>
                                        <th style="text-align: right;">Amount</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    $rows
                                </tbody>
                            </table>
                            ${if (transactions.isEmpty()) "<div style='text-align:center; padding: 20px; color:var(--text-secondary);'>No transactions found.</div>" else ""}
                        </div>
                    </div>
                </div>

                <div class="footer">
                    $msgGeneratedAt
                </div>

                <script>
                    Chart.defaults.color = '#94A3B8';
                    Chart.defaults.font.family = "'Inter', sans-serif";

                    // Category Donut Chart
                    const categoryCtx = document.getElementById('categoryChart');
                    if (categoryCtx) {
                        new Chart(categoryCtx, {
                            type: 'doughnut',
                            data: {
                                labels: [$categoryLabels],
                                datasets: [{
                                    data: [$categoryData],
                                    backgroundColor: [$categoryColors],
                                    borderWidth: 0,
                                    hoverOffset: 4
                                }]
                            },
                            options: {
                                animation: false,
                                responsive: true,
                                maintainAspectRatio: false,
                                cutout: '75%',
                                plugins: {
                                    legend: { position: 'right', labels: { usePointStyle: true, padding: 20 } },
                                    tooltip: {
                                        callbacks: {
                                            label: function(context) {
                                                return ' ₹' + context.raw;
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    }

                    // Daily Bar Chart
                    const dailyCtx = document.getElementById('dailyChart');
                    if (dailyCtx) {
                        new Chart(dailyCtx, {
                            type: 'bar',
                            data: {
                                labels: [$daysLabels],
                                datasets: [
                                    {
                                        label: 'Expense',
                                        data: [$dailyExpenseData],
                                        backgroundColor: '#FF5252',
                                        borderRadius: 4
                                    },
                                    {
                                        label: 'Income',
                                        data: [$dailyIncomeData],
                                        backgroundColor: '#00E676',
                                        borderRadius: 4
                                    }
                                ]
                            },
                            options: {
                                animation: false,
                                responsive: true,
                                maintainAspectRatio: false,
                                interaction: { mode: 'index', intersect: false },
                                scales: {
                                    x: { grid: { display: false, drawBorder: false } },
                                    y: { grid: { color: '#334155', drawBorder: false }, beginAtZero: true }
                                },
                                plugins: {
                                    legend: { position: 'top', labels: { usePointStyle: true } },
                                    tooltip: {
                                        callbacks: {
                                            label: function(context) {
                                                return context.dataset.label + ': ₹' + context.raw;
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
