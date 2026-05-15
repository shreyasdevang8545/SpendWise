package com.tech.spendwise

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.tech.spendwise.models.Transaction
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _selectedMonth = MutableLiveData<Int>() // 0-11
    val selectedMonth: LiveData<Int> = _selectedMonth

    private val _selectedYear = MutableLiveData<Int>()
    val selectedYear: LiveData<Int> = _selectedYear

    private val _transactions = MutableLiveData<List<Transaction>>(emptyList())
    val transactions: LiveData<List<Transaction>> = _transactions

    init {
        val cal = Calendar.getInstance()
        _selectedMonth.value = cal.get(Calendar.MONTH)
        _selectedYear.value = cal.get(Calendar.YEAR)
    }

    fun updateTransactions(list: List<String>) {
        val parsed = list.mapNotNull { json ->
            try {
                val obj = JSONObject(json)
                Transaction(
                    id = obj.optString("id", null),
                    amount = obj.optDouble("amount", 0.0),
                    type = obj.optString("type", "DEBIT"),
                    merchant = obj.optString("merchant", "Unknown"),
                    category = obj.optString("category", "Others"),
                    paymentMode = obj.optString("payment_mode", null),
                    currency = obj.optString("currency", "INR"),
                    savedAt = obj.optString("saved_at", ""),
                    createdAt = obj.optLong("created_at", System.currentTimeMillis()),
                    isLend = obj.optBoolean("is_lend", false),
                    note = obj.optString("note", null)
                )
            } catch (e: Exception) { null }
        }
        _transactions.postValue(parsed)
    }

    fun setMonth(month: Int, year: Int) {
        _selectedMonth.value = month
        _selectedYear.value = year
    }

    // Processed Data for Charts
    
    fun getMonthlySpending(): Double {
        val month = _selectedMonth.value ?: return 0.0
        val year = _selectedYear.value ?: return 0.0
        return _transactions.value?.filter { 
            it.type == "DEBIT" && isSameMonth(it.savedAt, year, month + 1)
        }?.sumOf { it.amount } ?: 0.0
    }

    fun getMonthlyIncome(): Double {
        val month = _selectedMonth.value ?: return 0.0
        val year = _selectedYear.value ?: return 0.0
        return _transactions.value?.filter { 
            it.type == "CREDIT" && isSameMonth(it.savedAt, year, month + 1)
        }?.sumOf { it.amount } ?: 0.0
    }

    fun getDailyAverage(): Double {
        val spent = getMonthlySpending()
        val cal = Calendar.getInstance()
        val currentMonth = cal.get(Calendar.MONTH)
        val currentYear = cal.get(Calendar.YEAR)
        
        val selMonth = _selectedMonth.value ?: 0
        val selYear = _selectedYear.value ?: 0
        
        val days = if (selMonth == currentMonth && selYear == currentYear) {
            cal.get(Calendar.DAY_OF_MONTH)
        } else {
            val selCal = Calendar.getInstance()
            selCal.set(selYear, selMonth, 1)
            selCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        }
        return if (days > 0) spent / days else 0.0
    }

    fun getDailyTrend(): List<Pair<Int, Double>> {
        val month = _selectedMonth.value ?: return emptyList()
        val year = _selectedYear.value ?: return emptyList()
        
        val cal = Calendar.getInstance()
        cal.set(year, month, 1)
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        
        val dailyMap = _transactions.value?.filter { 
            it.type == "DEBIT" && isSameMonth(it.savedAt, year, month + 1)
        }?.groupBy { 
            try { it.savedAt.substring(8, 10).toInt() } catch (e: Exception) { 0 }
        }?.mapValues { it.value.sumOf { t -> t.amount } } ?: emptyMap()
        
        return (1..maxDays).map { day -> 
            day to (dailyMap[day] ?: 0.0)
        }
    }

    fun getCategoryData(): Map<String, Double> {
        val month = _selectedMonth.value ?: return emptyMap()
        val year = _selectedYear.value ?: return emptyMap()
        return _transactions.value?.filter { 
            it.type == "DEBIT" && isSameMonth(it.savedAt, year, month + 1)
        }?.groupBy { it.category }?.mapValues { it.value.sumOf { t -> t.amount } } ?: emptyMap()
    }

    fun getHeatmapData(): Map<Int, Float> {
        val month = _selectedMonth.value ?: return emptyMap()
        val year = _selectedYear.value ?: return emptyMap()
        val dailyTotals = _transactions.value?.filter { 
            it.type == "DEBIT" && isSameMonth(it.savedAt, year, month + 1)
        }?.groupBy { 
            try { it.savedAt.substring(8, 10).toInt() } catch (e: Exception) { 0 }
        }?.mapValues { it.value.sumOf { t -> t.amount }.toFloat() } ?: emptyMap()
        
        val max = dailyTotals.values.maxOrNull() ?: 1f
        return dailyTotals.mapValues { it.value / max }
    }

    fun getWeekdayPattern(): Map<Int, Double> {
        val month = _selectedMonth.value ?: return emptyMap()
        val year = _selectedYear.value ?: return emptyMap()
        val pattern = mutableMapOf<Int, Double>()
        
        _transactions.value?.filter { 
            it.type == "DEBIT" && isSameMonth(it.savedAt, year, month + 1)
        }?.forEach { t ->
            val date = parseRobustly(t.savedAt)
            if (date != null) {
                val cal = Calendar.getInstance()
                cal.time = date
                val dow = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun, 2=Mon...
                pattern[dow] = (pattern[dow] ?: 0.0) + t.amount
            }
        }
        return pattern
    }

    private fun parseRobustly(savedAt: String): Date? {
        if (savedAt.length < 10) return null
        val formats = listOf("yyyy-MM-dd", "dd-MM-yyyy", "yyyy/MM/dd", "dd/MM/yyyy")
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.getDefault())
                sdf.isLenient = false
                return sdf.parse(savedAt.substring(0, 10))
            } catch (e: Exception) {}
        }
        return null
    }

    fun getTopMerchants(): List<Triple<String, Int, Double>> {
        val month = _selectedMonth.value ?: return emptyList()
        val year = _selectedYear.value ?: return emptyList()
        return _transactions.value?.filter { 
            it.type == "DEBIT" && isSameMonth(it.savedAt, year, month + 1)
        }?.groupBy { it.merchant }
            ?.map { Triple(it.key, it.value.size, it.value.sumOf { t -> t.amount }) }
            ?.sortedByDescending { it.third }
            ?.take(5) ?: emptyList()
    }

    fun getSpendingTrend(): List<Pair<String, Double>> {
        val trend = mutableListOf<Pair<String, Double>>()
        val cal = Calendar.getInstance()
        val sdf = SimpleDateFormat("MMM", Locale.getDefault())
        
        for (i in 5 downTo 0) {
            val iterCal = Calendar.getInstance()
            iterCal.add(Calendar.MONTH, -i)
            val m = iterCal.get(Calendar.MONTH) + 1
            val y = iterCal.get(Calendar.YEAR)
            val total = _transactions.value?.filter { 
                it.type == "DEBIT" && isSameMonth(it.savedAt, y, m)
            }?.sumOf { it.amount } ?: 0.0
            trend.add(sdf.format(iterCal.time) to total)
        }
        return trend
    }

    fun getComparisonData(): Pair<Map<String, Double>, Map<String, Double>> {
        val thisMonth = getCategoryData()
        
        val cal = Calendar.getInstance()
        cal.set(_selectedYear.value!!, _selectedMonth.value!!, 1)
        cal.add(Calendar.MONTH, -1)
        val lastM = cal.get(Calendar.MONTH) + 1
        val lastY = cal.get(Calendar.YEAR)
        
        val lastMonth = _transactions.value?.filter { 
            it.type == "DEBIT" && isSameMonth(it.savedAt, lastY, lastM)
        }?.groupBy { it.category }?.mapValues { it.value.sumOf { t -> t.amount } } ?: emptyMap()
        
        // Ensure both maps have the same keys for grouping
        val allCategories = thisMonth.keys + lastMonth.keys
        val syncedThis = allCategories.associateWith { thisMonth[it] ?: 0.0 }
        val syncedLast = allCategories.associateWith { lastMonth[it] ?: 0.0 }
        
        return Pair(syncedThis, syncedLast)
    }

    private fun isSameMonth(savedAt: String, year: Int, month: Int): Boolean {
        if (savedAt.length < 7) return false
        return try {
            if (savedAt[4] == '-' || savedAt[4] == '/') {
                // YYYY-MM
                val y = savedAt.substring(0, 4).toInt()
                val m = savedAt.substring(5, 7).toInt()
                y == year && m == month
            } else {
                // DD-MM-YYYY
                val m = savedAt.substring(3, 5).toInt()
                val y = savedAt.substring(6, 10).toInt()
                y == year && m == month
            }
        } catch (e: Exception) { false }
    }
}
