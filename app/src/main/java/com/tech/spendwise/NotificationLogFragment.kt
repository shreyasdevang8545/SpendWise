package com.tech.spendwise

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tech.spendwise.databinding.FragmentNotificationLogBinding
import com.tech.spendwise.databinding.ItemNotificationLogBinding
import com.tech.spendwise.utils.UIUtils

class NotificationLogFragment : Fragment() {

    private var _binding: FragmentNotificationLogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()
    private lateinit var adapter: NotificationLogAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNotificationLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()

        viewModel.pendingTransactions.observe(viewLifecycleOwner) { transactions ->
            if (transactions.isNullOrEmpty()) {
                binding.rvNotifications.visibility = View.GONE
                binding.emptyState.visibility = View.VISIBLE
            } else {
                binding.rvNotifications.visibility = View.VISIBLE
                binding.emptyState.visibility = View.GONE
                adapter.submitList(transactions)
            }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        
        binding.toolbar.inflateMenu(R.menu.menu_notification_log)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_all -> {
                    viewModel.clearPendingTransactions()
                    UIUtils.showSuccessSnackbar(binding.root, "All alerts cleared")
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = NotificationLogAdapter(
            onReview = { json, index ->
                // Reviewing a specific transaction. 
                // For now, our ReviewTransactionFragment reviews the "first" in queue.
                // To keep it simple, we can either:
                // 1. Update VM to support reviewing a specific one.
                // 2. Just move this one to the top of the queue (pop other ones later).
                // But the easiest is to just navigate to ReviewTransactionFragment,
                // and if we want specific one, we'd need to pass it.
                
                // Let's just navigate to home and then to review, or direct to review.
                // But wait, the repository is a queue. To review the N-th item, 
                // we should probably just pass the JSON to the fragment.
                
                // Actually, the ReviewTransactionFragment is built to observe `firstTransaction`.
                // If we want to review a specific one, we should probably just notify the user 
                // that they will review the first one, or we can "reorder" the queue.
                
                // Better approach: ReviewTransactionFragment can accept a JSON string as an argument.
                val bundle = Bundle().apply {
                    putString("transaction_json", json)
                }
                findNavController().navigate(R.id.action_notificationLog_to_review, bundle)
            },
            onDiscard = { index ->
                viewModel.removePendingTransaction(index)
            }
        )
        binding.rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotifications.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class NotificationLogAdapter(
        private val onReview: (String, Int) -> Unit,
        private val onDiscard: (Int) -> Unit
    ) : RecyclerView.Adapter<NotificationLogAdapter.ViewHolder>() {

        private var items = emptyList<String>()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemNotificationLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemNotificationLogBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(json: String, position: Int) {
                val data = parseJson(json)
                
                binding.tvMerchant.text = data["entity"] ?: "Unknown Source"
                binding.tvAmount.text = "₹${data["amount"] ?: "0.0"}"
                binding.tvDate.text = data["date_time"] ?: "Unknown Date"
                binding.tvMessage.text = data["raw_sms"] ?: "No message content"

                binding.btnReview.setOnClickListener { onReview(json, position) }
                binding.btnDiscard.setOnClickListener { onDiscard(position) }
            }

            private fun parseJson(json: String): Map<String, String> {
                val pattern = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|([^,}\s]+))""")
                val data = mutableMapOf<String, String>()
                for (match in pattern.findAll(json)) {
                    val key = match.groupValues[1]
                    val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[3]
                    data[key] = value
                }
                return data
            }
        }
    }
}
