package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tech.spendwise.databinding.FragmentAiCoachBinding

import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.app.activityViewModels

class AiCoachFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentAiCoachBinding? = null
    private val binding get() = _binding!!

    private val geminiViewModel: GeminiViewModel by viewModels()
    private val analyticsViewModel: AnalyticsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiCoachBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnSend.setOnClickListener {
            val query = binding.etMessage.text.toString()
            if (query.isNotEmpty()) {
                // For demo, we just trigger the insights logic or a generic chat response
                // In real app, we'd have a specific geminiViewModel.chat(query, contextData)
                val transactions = analyticsViewModel.transactions.value ?: emptyList()
                geminiViewModel.generateInsights(transactions)
                binding.etMessage.setText("")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AiCoachFragment"
    }
}
