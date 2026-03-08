package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentHomeBinding

/**
 * Main screen showing pending transactions.
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: TransactionViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.pendingTransactions.observe(viewLifecycleOwner) { list ->
            if (list.isNotEmpty()) {
                binding.pendingTransactionCard.visibility = View.VISIBLE
                val count = list.size
                binding.pendingTransactionText.text = if (count == 1) {
                    "1 Transaction found. Tap to review."
                } else {
                    "$count Transactions found. Tap to review."
                }
            } else {
                binding.pendingTransactionCard.visibility = View.GONE
            }
        }

        binding.pendingTransactionCard.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_review)
        }

        // Voice FAB — opens mic entry screen
        binding.voiceFab.setOnClickListener {
            findNavController().navigate(
                HomeFragmentDirections.actionHomeToVoiceInput(autoStart = false)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
