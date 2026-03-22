package com.tech.spendwise.splitwise

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tech.spendwise.databinding.ItemSplitExpenseBinding
import com.tech.spendwise.models.SplitExpense
import java.text.NumberFormat
import java.util.Locale
import android.widget.PopupMenu
import com.tech.spendwise.R

class SplitExpenseAdapter(
    private val currentUserId: String,
    private var memberMappings: Map<String, String>,
    private val onEdit: (SplitExpense) -> Unit,
    private val onDelete: (SplitExpense) -> Unit
) : ListAdapter<SplitExpense, SplitExpenseAdapter.ViewHolder>(DIFF) {

    private var currentUserMappedName = memberMappings[currentUserId]?.split("|")?.firstOrNull()

    fun updateMappings(newMappings: Map<String, String>) {
        memberMappings = newMappings
        currentUserMappedName = memberMappings[currentUserId]?.split("|")?.firstOrNull()
        notifyDataSetChanged()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SplitExpense>() {
            override fun areItemsTheSame(a: SplitExpense, b: SplitExpense) = a.id == b.id
            override fun areContentsTheSame(a: SplitExpense, b: SplitExpense) = a == b
        }
    }

    private val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    inner class ViewHolder(private val b: ItemSplitExpenseBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(e: SplitExpense) {
            b.tvExpenseDescription.text = e.description
            val originalName = e.paidBy.split("|").firstOrNull() ?: e.paidBy
            val displayName = if (originalName == currentUserMappedName) "You" else originalName
            
            b.tvExpensePaidBy.text = "Paid by $displayName"
            b.tvExpenseAmount.text = fmt.format(e.amount)

            b.root.setOnLongClickListener { view ->
                if (e.uid != currentUserId) {
                    // Option: Toast "Only the owner can edit this" or just hide the menu
                    android.widget.Toast.makeText(view.context, "Only the owner can edit this expense", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnLongClickListener true
                }
                
                val popup = PopupMenu(view.context, view)
                popup.menu.add("Edit")
                popup.menu.add("Delete")
                popup.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        "Edit" -> onEdit(e)
                        "Delete" -> onDelete(e)
                    }
                    true
                }
                popup.show()
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemSplitExpenseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(h: ViewHolder, pos: Int) = h.bind(getItem(pos))
}
