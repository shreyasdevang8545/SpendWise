package com.tech.spendwise

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.tech.spendwise.utils.UIUtils
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.launch
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tech.spendwise.SupabaseInstance
import com.tech.spendwise.databinding.FragmentLendHistoryBinding
import com.tech.spendwise.models.LendTransaction

class LendHistoryFragment : Fragment() {

    private var _binding: FragmentLendHistoryBinding? = null
    private val binding get() = _binding!!

    private val supabaseRepository = SupabaseRepository()
    private val adapter = LendListAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLendHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnHelp.setOnClickListener {
            showManualDialog()
        }

        binding.btnCloseGuide.setOnClickListener {
            dismissGuide()
        }

        checkGuideVisibility()

        binding.lendRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.lendRecyclerView.adapter = adapter

        setupSwipeActions()
        fetchLends()
    }

    private fun checkGuideVisibility() {
        val prefs = requireContext().getSharedPreferences("spendwise_prefs", Context.MODE_PRIVATE)
        val isDismissed = prefs.getBoolean("lend_guide_dismissed", false)
        binding.guideCard.visibility = if (isDismissed) View.GONE else View.VISIBLE
    }

    private fun dismissGuide() {
        binding.guideCard.visibility = View.GONE
        val prefs = requireContext().getSharedPreferences("spendwise_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("lend_guide_dismissed", true).apply()
    }

    private fun showManualDialog() {
        UIUtils.showAlertDialog(
            requireContext(),
            getString(R.string.title_lend_manual),
            getString(R.string.msg_lend_manual),
            getString(R.string.btn_got_it),
            null
        )
    }

    private fun setupSwipeActions() {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val lend = adapter.currentList[position]

                if (direction == ItemTouchHelper.LEFT) {
                    // Delete
                    showDeleteConfirmation(lend, position)
                } else if (direction == ItemTouchHelper.RIGHT) {
                    // Action Menu
                    showActionMenu(lend, position)
                }
            }
        }
        val itemTouchHelper = ItemTouchHelper(swipeHandler)
        itemTouchHelper.attachToRecyclerView(binding.lendRecyclerView)
    }

    private fun showActionMenu(lend: LendTransaction, position: Int) {
        val options = if (lend.isReturned) {
            arrayOf(getString(R.string.btn_edit_detail))
        } else {
            arrayOf(getString(R.string.btn_mark_returned), getString(R.string.btn_edit_detail))
        }

        UIUtils.showListDialog(requireContext(), getString(R.string.title_action_for, lend.name), options) { which ->
            when (options[which]) {
                getString(R.string.btn_mark_returned) -> markAsReturned(lend)
                getString(R.string.btn_edit_detail) -> {
                    val bundle = Bundle().apply {
                        putString("lendId", lend.id)
                    }
                    findNavController().navigate(R.id.action_history_to_addLend, bundle)
                }
            }
            adapter.notifyItemChanged(position)
        }
    }

    private fun markAsReturned(lend: LendTransaction) {
        val uid = SupabaseInstance.currentUserId() ?: return
        lifecycleScope.launch {
            supabaseRepository.updateLendStatus(lend.id!!, true)
            // ...
            _binding?.let {
                lend.id?.let { id ->
                    ReminderManager.cancelReminder(requireContext(), id)
                }
                fetchLends() // Refresh
            }
        }
    }

    private fun showDeleteConfirmation(lend: LendTransaction, position: Int) {
        UIUtils.showAlertDialog(
            requireContext(),
            getString(R.string.title_delete_record),
            getString(R.string.msg_delete_lend_confirm, lend.name),
            getString(R.string.btn_delete),
            getString(R.string.btn_cancel)
        ) {
            val uid = SupabaseInstance.currentUserId() ?: return@showAlertDialog
            lifecycleScope.launch {
                supabaseRepository.deleteLend(lend.id!!)
                _binding?.let {
                    lend.id?.let { id ->
                        ReminderManager.cancelReminder(requireContext(), id)
                    }
                    fetchLends() // Refresh list
                }
            }
        }
        // Special case: we need to reset swipe if canceled. 
        // Our showAlertDialog doesn't support cancel listeners yet, but we can assume dismiss behavior for now.
        // For production, we'd add it to UIUtils.
    }

    private fun fetchLends() {
        binding.shimmerViewContainer.visibility = View.VISIBLE
        binding.shimmerViewContainer.startShimmer()
        binding.lendRecyclerView.visibility = View.GONE
        binding.emptyState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val lends = supabaseRepository.fetchLends()
                _binding?.let { binding ->
                    binding.shimmerViewContainer.stopShimmer()
                    binding.shimmerViewContainer.visibility = View.GONE
                    binding.lendRecyclerView.visibility = View.VISIBLE
                    
                    if (lends.isEmpty()) {
                        binding.emptyState.visibility = View.VISIBLE
                    } else {
                        binding.emptyState.visibility = View.GONE
                        adapter.submitList(lends)
                    }
                }
            } catch (e: Exception) {
                Log.e("LendHistoryFragment", "Error fetching lends: ${e.message}")
                _binding?.let { binding ->
                    binding.shimmerViewContainer.stopShimmer()
                    binding.shimmerViewContainer.visibility = View.GONE
                    binding.emptyState.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
