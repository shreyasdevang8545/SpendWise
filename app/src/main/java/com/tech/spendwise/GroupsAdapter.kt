package com.tech.spendwise

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tech.spendwise.models.TransactionGroup

class GroupsAdapter(
    private val onGroupClicked: (TransactionGroup) -> Unit
) : ListAdapter<TransactionGroup, GroupsAdapter.ViewHolder>(GroupDiff()) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.textGroupName)
        val desc: TextView = view.findViewById(R.id.textGroupDesc)
        val icon: TextView = view.findViewById(R.id.txGroupIcon)

        init {
            view.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onGroupClicked(getItem(adapterPosition))
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = getItem(position)
        holder.name.text = group.name
        holder.icon.text = group.name.take(1).uppercase()
        
        if (!group.description.isNullOrEmpty()) {
            holder.desc.visibility = View.VISIBLE
            holder.desc.text = group.description
        } else {
            holder.desc.visibility = View.GONE
        }
    }
}

class GroupDiff : DiffUtil.ItemCallback<TransactionGroup>() {
    override fun areItemsTheSame(oldItem: TransactionGroup, newItem: TransactionGroup) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: TransactionGroup, newItem: TransactionGroup) = oldItem == newItem
}
