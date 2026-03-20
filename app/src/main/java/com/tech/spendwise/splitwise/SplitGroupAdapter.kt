package com.tech.spendwise.splitwise

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tech.spendwise.databinding.ItemSplitGroupBinding
import com.tech.spendwise.models.SplitGroup

class SplitGroupAdapter(
    private val onClick: (SplitGroup) -> Unit,
    private val onLongClick: (SplitGroup) -> Unit = {}
) : ListAdapter<SplitGroup, SplitGroupAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SplitGroup>() {
            override fun areItemsTheSame(a: SplitGroup, b: SplitGroup) = a.id == b.id
            override fun areContentsTheSame(a: SplitGroup, b: SplitGroup) = a == b
        }
    }

    inner class ViewHolder(private val b: ItemSplitGroupBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(group: SplitGroup) {
            b.tvGroupName.text = group.name
            b.tvMemberCount.text = "${group.members.size} members"
            b.root.setOnClickListener { onClick(group) }
            b.root.setOnLongClickListener { onLongClick(group); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemSplitGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(h: ViewHolder, pos: Int) = h.bind(getItem(pos))
}
