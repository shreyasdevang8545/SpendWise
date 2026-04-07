package com.tech.spendwise

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tech.spendwise.databinding.ItemCreditCardBinding
import com.tech.spendwise.models.CreditCard

class CreditCardAdapter(
    private var cards: List<CreditCard>,
    private val spentAmounts: Map<String, Double>
) : RecyclerView.Adapter<CreditCardAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemCreditCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCreditCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = cards[position]
        holder.binding.cardName.text = card.name.uppercase()
        holder.binding.cardLast4.text = "**** **** **** ${card.last4}"
        val spent = spentAmounts[card.id] ?: 0.0
        holder.binding.cardSpentAmount.text = "₹ %.2f".format(spent)
        holder.binding.cardLimit.text = "Limit: ₹${card.limit.toInt()}"
        
        // Cycle through premium gradients
        val gradients = listOf(
            R.drawable.bg_card_gradient_blue,
            R.drawable.bg_card_gradient_purple,
            R.drawable.bg_card_gradient_dark
        )
        val gradientRes = gradients[position % gradients.size]
        holder.binding.cardBackground.setBackgroundResource(gradientRes)

        // Subtle entry animation if not already animated
        holder.itemView.alpha = 0f
        holder.itemView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setStartDelay(position * 100L)
            .start()
    }


    override fun getItemCount() = cards.size

    fun updateData(newCards: List<CreditCard>, newSpent: Map<String, Double>) {
        cards = newCards
        // No need to update spentAmounts as it's a map passed in, but we update the internal reference if needed
        // Actually, better to pass new maps
        notifyDataSetChanged()
    }
}
