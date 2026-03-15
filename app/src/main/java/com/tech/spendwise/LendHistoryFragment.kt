package com.tech.spendwise

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.tech.spendwise.utils.UIUtils
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.tech.spendwise.databinding.FragmentLendHistoryBinding
import com.tech.spendwise.models.LendTransaction

class LendHistoryFragment : Fragment() {

    private var _binding: FragmentLendHistoryBinding? = null
    private val binding get() = _binding!!

    private val firestoreRepository = FirestoreRepository()
    private val adapter = LendListAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLendHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
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
            "User Manual: Lend History",
            "Swipe Gestures:\n\n" +
                    "👉 Swipe RIGHT:\n" +
                    "• Mark as Returned: Quickly update status.\n" +
                    "• Edit Detail: Change amount or date.\n\n" +
                    "👈 Swipe LEFT:\n" +
                    "• Delete: Remove record permanently.\n\n" +
                    "Note: Deleting a record also cancels any pending notifications.",
            "Got it",
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
            arrayOf("Edit Detail")
        } else {
            arrayOf("Mark as Returned", "Edit Detail")
        }

        UIUtils.showListDialog(requireContext(), "Action for ${lend.name}", options) { which ->
            when (options[which]) {
                "Mark as Returned" -> markAsReturned(lend)
                "Edit Detail" -> {
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
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        firestoreRepository.updateLendStatus(uid, lend.id!!, true) {
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
            "Delete Record",
            "Are you sure you want to delete this lend record for ${lend.name}?",
            "Delete",
            "Cancel"
        ) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@showAlertDialog
            firestoreRepository.deleteLend(uid, lend.id!!) {
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
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        binding.shimmerViewContainer.visibility = View.VISIBLE
        binding.shimmerViewContainer.startShimmer()
        binding.lendRecyclerView.visibility = View.GONE
        binding.emptyState.visibility = View.GONE

        firestoreRepository.fetchLends(uid) { lends ->
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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
