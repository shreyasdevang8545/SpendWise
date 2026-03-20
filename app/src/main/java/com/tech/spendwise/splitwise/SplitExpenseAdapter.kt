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

class SplitExpenseAdapter :
    ListAdapter<SplitExpense, SplitExpenseAdapter.ViewHolder>(DIFF) {

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
            b.tvExpensePaidBy.text = "Paid by ${e.paidBy}"
            b.tvExpenseAmount.text = fmt.format(e.amount)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemSplitExpenseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(h: ViewHolder, pos: Int) = h.bind(getItem(pos))
}
