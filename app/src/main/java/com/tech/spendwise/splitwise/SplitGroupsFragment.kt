package com.tech.spendwise.splitwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.tech.spendwise.R
import com.tech.spendwise.databinding.FragmentSplitGroupsBinding
import com.tech.spendwise.models.SplitGroup
import com.tech.spendwise.utils.UIUtils

class SplitGroupsFragment : Fragment() {

    private var _binding: FragmentSplitGroupsBinding? = null
    private val binding get() = _binding!!

    private val adapter = SplitGroupAdapter(
        onClick = { group -> openGroupDetail(group) },
        onLongClick = { group -> confirmDeleteGroup(group) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSplitGroupsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.rvGroups.layoutManager = LinearLayoutManager(requireContext())
        binding.rvGroups.adapter = adapter

        binding.fabCreateGroup.setOnClickListener {
            findNavController().navigate(R.id.action_splitGroups_to_createGroup)
        }

        loadGroups()
    }

    override fun onResume() {
        super.onResume()
        loadGroups()
    }

    private fun loadGroups() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        SplitRepository.fetchGroups(uid) { result ->
            result.onSuccess { groups ->
                activity?.runOnUiThread {
                    adapter.submitList(groups)
                    binding.emptyState.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvGroups.visibility = if (groups.isEmpty()) View.GONE else View.VISIBLE
                }
            }
            result.onFailure {
                activity?.runOnUiThread {
                    UIUtils.showErrorSnackbar(binding.root, "Failed to load groups")
                }
            }
        }
    }

    private fun openGroupDetail(group: SplitGroup) {
        val bundle = Bundle().apply { putString("groupId", group.id) }
        findNavController().navigate(R.id.action_splitGroups_to_groupDetail, bundle)
    }

    private fun confirmDeleteGroup(group: SplitGroup) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Group")
            .setMessage("Delete \"${group.name}\" and all its expenses?")
            .setPositiveButton("Delete") { _, _ ->
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@setPositiveButton
                SplitRepository.deleteGroup(uid, group.id) { loadGroups() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
