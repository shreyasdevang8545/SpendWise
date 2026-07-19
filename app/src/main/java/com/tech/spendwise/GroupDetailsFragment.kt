package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.tech.spendwise.databinding.FragmentGroupDetailsBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

class GroupDetailsFragment : Fragment() {

    private var _binding: FragmentGroupDetailsBinding? = null
    private val binding get() = _binding!!

    private val supabaseRepository = SupabaseRepository()
    private val adapter = TransactionListAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGroupDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupId = arguments?.getString("groupId") ?: return
        val groupName = arguments?.getString("groupName") ?: "Group Details"

        binding.toolbar.title = groupName
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.transactionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.transactionsRecyclerView.adapter = adapter

        fetchGroupTransactions(groupId)
    }

    private fun fetchGroupTransactions(groupId: String) {
        binding.progressIndicator.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        
        lifecycleScope.launch {
            val transactions = supabaseRepository.fetchTransactionsByGroupId(groupId)
            binding.progressIndicator.visibility = View.GONE
            
            if (transactions.isEmpty()) {
                binding.emptyState.visibility = View.VISIBLE
                binding.textTotalSpent.text = "₹0.00"
            } else {
                binding.emptyState.visibility = View.GONE
                adapter.submitList(transactions)
                
                var total = 0.0
                transactions.forEach { jsonStr ->
                    try {
                        val amount = JSONObject(jsonStr).optDouble("amount", 0.0)
                        val type = JSONObject(jsonStr).optString("type", "DEBIT")
                        if (type.equals("DEBIT", true)) {
                            total += amount
                        } else {
                            total -= amount // income reduces total spent
                        }
                    } catch (e: Exception) {
                        // ignore parsing error for total calculation
                    }
                }
                
                binding.textTotalSpent.text = "₹%.2f".format(total)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
