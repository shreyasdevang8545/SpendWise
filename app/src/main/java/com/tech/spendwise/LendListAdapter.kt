package com.tech.spendwise

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tech.spendwise.databinding.ItemLendBinding
import com.tech.spendwise.models.LendTransaction
import java.text.SimpleDateFormat
import java.util.Locale

class LendListAdapter : ListAdapter<LendTransaction, LendListAdapter.LendViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LendViewHolder {
        val binding = ItemLendBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LendViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LendViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LendViewHolder(private val binding: ItemLendBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormatter = SimpleDateFormat("dd MMM", Locale.getDefault())

        fun bind(lend: LendTransaction) {
            binding.textName.text = lend.name
            binding.textAmount.text = "₹${lend.amount}"
            
            val status = if (lend.isReturned) "Returned" else "via ${lend.paymentMode} • Due ${dateFormatter.format(lend.returnDate)}"
            binding.textDetails.text = status

            val color = if (lend.isReturned) {
                ContextCompat.getColor(binding.root.context, R.color.primary_green)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.error_red)
            }
            binding.statusIndicator.setBackgroundColor(color)
            
            if (lend.isReturned) {
                binding.textName.alpha = 0.5f
                binding.textAmount.alpha = 0.5f
                binding.textDetails.alpha = 0.5f
            } else {
                binding.textName.alpha = 1.0f
                binding.textAmount.alpha = 1.0f
                binding.textDetails.alpha = 1.0f
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<LendTransaction>() {
        override fun areItemsTheSame(oldItem: LendTransaction, newItem: LendTransaction) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LendTransaction, newItem: LendTransaction) = oldItem == newItem
    }
}
