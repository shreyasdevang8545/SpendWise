package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tech.spendwise.databinding.BottomSheetHistoryFilterBinding

class HistoryFilterBottomSheet(
    private val onTypeSelected: (HistoryType) -> Unit
) : BottomSheetDialogFragment() {

    enum class HistoryType {
        TRANSACTIONS, LENDS
    }

    private var _binding: BottomSheetHistoryFilterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetHistoryFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.optionTransactions.setOnClickListener {
            onTypeSelected(HistoryType.TRANSACTIONS)
            dismiss()
        }

        binding.optionLends.setOnClickListener {
            onTypeSelected(HistoryType.LENDS)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HistoryFilterBottomSheet"
    }
}
