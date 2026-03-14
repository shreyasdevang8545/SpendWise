package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tech.spendwise.databinding.BottomSheetSelectionBinding

class SelectionBottomSheet(
    private val onOptionSelected: (SelectionOption) -> Unit
) : BottomSheetDialogFragment() {

    enum class SelectionOption {
        TRANSACTION, LEND, HISTORY
    }

    private var _binding: BottomSheetSelectionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.optionAddTransaction.setOnClickListener {
            onOptionSelected(SelectionOption.TRANSACTION)
            dismiss()
        }

        binding.optionAddLend.setOnClickListener {
            onOptionSelected(SelectionOption.LEND)
            dismiss()
        }

        binding.optionLendHistory.setOnClickListener {
            onOptionSelected(SelectionOption.HISTORY)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SelectionBottomSheet"
    }
}
