package com.tech.spendwise

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RecyclerView adapter for displaying confirmed (saved) transactions on the Home screen.
 * Each item is a compact JSON string produced by ReviewTransactionFragment.
 */
class TransactionListAdapter :
    ListAdapter<String, TransactionListAdapter.ViewHolder>(TransactionDiff()) {

    private val displayDateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
    private val parseDateFmt   = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

    var isSelectionMode = false
        set(value) {
            field = value
            if (!value) selectedIds.clear()
            notifyDataSetChanged()
        }
    val selectedIds = mutableSetOf<String>()
    
    var onTransactionLongClick: ((String) -> Unit)? = null
    var onTransactionClick: ((String) -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon     : TextView = view.findViewById(R.id.txCategoryIcon)
        val merchant : TextView = view.findViewById(R.id.txMerchant)
        val meta     : TextView = view.findViewById(R.id.txMeta)
        val amount   : TextView = view.findViewById(R.id.txAmount)
        val checkbox : android.widget.CheckBox = view.findViewById(R.id.txSelectionCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val json = getItem(position)
        val data = parseJson(json)

        val merchantName = data["merchant"]?.takeIf { it.isNotBlank() && it != "UNKNOWN" } ?: "Unknown"
        val category     = data["category"] ?: "Others"
        val type         = data["type"] ?: "DEBIT"
        val currency     = data["currency"] ?: "INR"
        val amountVal    = data["amount"]?.toDoubleOrNull() ?: 0.0
        val savedAt      = data["saved_at"] ?: ""
        val payMode      = data["payment_mode"] ?: ""

        // Merchant
        holder.merchant.text = merchantName

        // Meta: category • payment mode • date
        val dateDisplay = try {
            val parsed = parseDateFmt.parse(savedAt)
            if (parsed != null) displayDateFmt.format(parsed) else ""
        } catch (_: Exception) { "" }

        val id = data["id"]
        val isOffline = id == null

        holder.meta.text = buildString {
            if (isOffline) append("Offline · ")
            append(category)
            if (payMode.isNotBlank()) append(" · $payMode")
            if (dateDisplay.isNotBlank()) append(" · $dateDisplay")
        }

        // Amount with sign and color
        val currencySymbol = if (currency == "INR") "₹" else currency
        val sign = if (type == "CREDIT") "+" else "-"
        holder.amount.text = "$sign$currencySymbol%.2f".format(amountVal)

        val colorRes = if (type == "CREDIT") R.color.primary_green else R.color.error_red
        holder.amount.setTextColor(ContextCompat.getColor(holder.itemView.context, colorRes))
        
        if (isOffline) {
            holder.merchant.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_secondary))
        } else {
            holder.merchant.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_primary))
        }
        // Category icon (emoji letter)
        val emoji = categoryEmoji(category)
        holder.icon.text = emoji

        val iconBgColor = categoryIconColor(category)
        holder.icon.background?.mutate()?.let {
            if (it is android.graphics.drawable.GradientDrawable) {
                it.setColor(ContextCompat.getColor(holder.itemView.context, iconBgColor))
            }
        }

        // Selection Logic
        val selectionId = id ?: data["saved_at"] ?: ""
        holder.checkbox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
        holder.checkbox.setOnCheckedChangeListener(null)
        holder.checkbox.isChecked = selectedIds.contains(selectionId)

        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIds.add(selectionId) else selectedIds.remove(selectionId)
            onSelectionChanged?.invoke()
        }

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                holder.checkbox.toggle()
            } else {
                onTransactionClick?.invoke(json)
            }
        }

        holder.itemView.setOnLongClickListener {
            onTransactionLongClick?.invoke(json)
            true
        }
    }

    private fun categoryEmoji(category: String): String = when (category.lowercase()) {
        "food"          -> "🍽"
        "entertainment" -> "🎬"
        "shopping"      -> "🛍"
        "transport"     -> "🚌"
        "bills"         -> "📄"
        "health"        -> "💊"
        "investment"    -> "📈"
        else            -> "💳"
    }

    private fun categoryIconColor(category: String): Int = when (category.lowercase()) {
        "food"          -> R.color.cat_food
        "entertainment" -> R.color.cat_entertainment
        "shopping"      -> R.color.cat_shopping
        "transport"     -> R.color.cat_transport
        "bills"         -> R.color.cat_bills
        "health"        -> R.color.cat_health
        "investment"    -> R.color.cat_investment
        else            -> R.color.cat_others
    }

    /** Minimal JSON field extractor matching string and numeric values. */
    private fun parseJson(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|([^,}\s]+))""")
        for (match in pattern.findAll(json)) {
            val key   = match.groupValues[1]
            val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
            result[key] = value
        }
        return result
    }

    private class TransactionDiff : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }
}
