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
        val months = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
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
        
        val months = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
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
                    Toast.makeText(requireContext(), "No transactions found for $selectedMonthName $selectedYear", Toast.LENGTH_SHORT).show()
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
            val dir = File(requireContext().filesDir, "reports")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "Report_${month}_${year}.html")
            
            FileOutputStream(file).use { it.write(html.toByteArray()) }
            
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open Report in Browser"))
            
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
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

        for (tx in transactions) {
            val amount = tx["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val type = tx["type"]?.jsonPrimitive?.content ?: "Expense"
            val category = tx["category"]?.jsonPrimitive?.content ?: "Uncategorized"

            if (type == "Income") {
                totalIncome += amount
            } else {
                totalExpense += amount
                categoryMap[category] = (categoryMap[category] ?: 0.0) + amount
            }
        }

        val sortedCategories = categoryMap.entries.sortedByDescending { it.value }
        val rows = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val displaySdf = SimpleDateFormat("dd MMM", Locale.getDefault())
        
        for (tx in transactions) {
            val savedAtStr = tx["saved_at"]?.jsonPrimitive?.content ?: ""
            val date = try {
                sdf.parse(savedAtStr) ?: Date(tx["saved_at"]?.jsonPrimitive?.long ?: 0L)
            } catch (e: Exception) {
                Date(tx["saved_at"]?.jsonPrimitive?.long ?: 0L)
            }
            val dateStr = displaySdf.format(date)
            val merchant = tx["merchant"]?.jsonPrimitive?.content ?: "-"
            val amountRaw = tx["amount"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            val amount = Math.abs(amountRaw).toInt()
            val type = tx["type"]?.jsonPrimitive?.content ?: "Expense"
            val color = if (type == "Income") "#4CAF50" else "#F44336"
            
            rows.append("""
                <tr>
                    <td>$dateStr</td>
                    <td>$merchant</td>
                    <td>${tx["category"]?.jsonPrimitive?.content ?: "-"}</td>
                    <td style="color: $color; font-weight: bold;">${if (type == "Income") "+" else "-"}$amount</td>
                </tr>
            """.trimIndent())
        }

        val categoryRows = StringBuilder()
        for (entry in sortedCategories) {
            val percentage = if (totalExpense > 0) (entry.value / totalExpense * 100).toInt() else 0
            categoryRows.append("""
                <div style="margin-bottom: 8px;">
                    <div style="display: flex; justify-content: space-between;">
                        <span>${entry.key}</span>
                        <span>₹${entry.value.toInt()} ($percentage%)</span>
                    </div>
                    <div style="width: 100%; height: 8px; background: #eee; border-radius: 4px; overflow: hidden; margin-top: 4px;">
                        <div style="width: ${percentage}%; height: 100%; background: #2E7D32;"></div>
                    </div>
                </div>
            """.trimIndent())
        }

        // Convert logo to Base64 for external browser
        val base64Logo = try {
            val inputStream = resources.openRawResource(requireContext().resources.getIdentifier("app_logo", "drawable", requireContext().packageName))
            val bytes = inputStream.readBytes()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) { "" }

        val savings = totalIncome - totalExpense
        val savingsColor = if (savings >= 0) "#1565C0" else "#C62828"

        return """
            <html>
            <head>
                <link href="https://fonts.googleapis.com/css2?family=Nunito:wght@400;600;700&display=swap" rel="stylesheet">
                <style>
                    body { font-family: 'Nunito', sans-serif; color: #333; padding: 20px; line-height: 1.6; background-color: #fdfdfd; }
                    .header { text-align: center; margin-bottom: 30px; }
                    .logo { height: 80px; margin-bottom: 10px; }
                    .summary-card { background: #fff; padding: 25px; border-radius: 16px; margin-bottom: 30px; display: flex; justify-content: space-around; box-shadow: 0 4px 15px rgba(0,0,0,0.05); }
                    .summary-item { text-align: center; }
                    .summary-value { font-size: 24px; font-weight: bold; margin-top: 5px; }
                    .income { color: #2E7D32; }
                    .expense { color: #C62828; }
                    .savings { color: $savingsColor; }
                    table { width: 100%; border-collapse: collapse; margin-top: 20px; background: #fff; border-radius: 12px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.03); }
                    th, td { text-align: left; padding: 15px; border-bottom: 1px solid #f0f0f0; }
                    th { color: #888; font-size: 13px; text-transform: uppercase; background: #fafafa; }
                    .section-title { font-size: 18px; font-weight: bold; margin: 30px 0 15px 0; border-left: 5px solid #2E7D32; padding-left: 12px; }
                    .export-btn { 
                        position: fixed; bottom: 30px; right: 30px; 
                        background: #2E7D32; color: #fff; border: none; 
                        padding: 15px 30px; border-radius: 50px; 
                        font-weight: bold; cursor: pointer; 
                        box-shadow: 0 10px 20px rgba(46,125,50,0.3);
                        font-family: 'Nunito', sans-serif;
                        font-size: 16px;
                    }
                    @media print {
                        .export-btn { display: none; }
                        body { background: #fff; }
                    }
                </style>
            </head>
            <body>
                <button class="export-btn" onclick="window.print()">Export as PDF</button>

                <div class="header">
                    ${if (base64Logo.isNotEmpty()) "<img class='logo' src='data:image/png;base64,$base64Logo'>" else ""}
                    <h1 style="margin: 0; color: #2E7D32; font-weight: 700;">SpendWise Report</h1>
                    <p style="margin: 5px 0; color: #666;">$month $year Summary</p>
                </div>
                
                <div class="summary-card">
                    <div class="summary-item">
                        <div style="color: #666; font-size: 12px; letter-spacing: 1px;">TOTAL INCOME</div>
                        <div class="summary-value income">₹${totalIncome.toInt()}</div>
                    </div>
                    <div class="summary-item">
                        <div style="color: #666; font-size: 12px; letter-spacing: 1px;">TOTAL EXPENSE</div>
                        <div class="summary-value expense">₹${totalExpense.toInt()}</div>
                    </div>
                    <div class="summary-item">
                        <div style="color: #666; font-size: 12px; letter-spacing: 1px;">${if (savings >= 0) "NET SAVINGS" else "DEFICIT"}</div>
                        <div class="summary-value savings">₹${Math.abs(savings).toInt()}</div>
                    </div>
                </div>

                <div class="section-title">Category Breakdown</div>
                $categoryRows

                <div class="section-title">Transactions</div>
                <table>
                    <thead>
                        <tr>
                            <th>Date</th>
                            <th>Merchant</th>
                            <th>Category</th>
                            <th>Amount</th>
                        </tr>
                    </thead>
                    <tbody>
                        $rows
                    </tbody>
                </table>
                
                <div style="margin-top: 50px; text-align: center; color: #aaa; font-size: 12px;">
                    Generated by SpendWise on ${SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())}
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
