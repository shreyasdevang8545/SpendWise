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
            val context = binding.root.context
            binding.textName.text = lend.name
            binding.textAmount.text = "₹${lend.amount}"
            binding.textAmountIncoming.text = "₹${lend.amount}"
            
            val status = if (lend.isReturned) "Returned" else "via ${lend.paymentMode} • Due ${dateFormatter.format(lend.returnDate)}"
            binding.textDetails.text = status

            val color = if (lend.isReturned) {
                ContextCompat.getColor(context, R.color.primary_green)
            } else {
                ContextCompat.getColor(context, R.color.error_red)
            }
            binding.statusIndicator.setBackgroundColor(color)
            
            if (lend.isReturned) {
                binding.textName.alpha = 0.6f
                binding.textDetails.alpha = 0.6f
                
                // Strikethrough for original amount
                binding.textAmount.paintFlags = binding.textAmount.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                binding.textAmount.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                binding.textAmount.alpha = 0.5f
                
                // Show incoming amount next to it
                binding.textAmountIncoming.visibility = android.view.View.VISIBLE
            } else {
                binding.textName.alpha = 1.0f
                binding.textDetails.alpha = 1.0f
                
                // Normal amount
                binding.textAmount.paintFlags = binding.textAmount.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                binding.textAmount.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                binding.textAmount.alpha = 1.0f
                
                // Hide incoming amount
                binding.textAmountIncoming.visibility = android.view.View.GONE
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<LendTransaction>() {
        override fun areItemsTheSame(oldItem: LendTransaction, newItem: LendTransaction) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LendTransaction, newItem: LendTransaction) = oldItem == newItem
    }
}
