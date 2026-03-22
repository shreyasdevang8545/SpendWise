package com.tech.spendwise.splitwise

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.R
import com.tech.spendwise.databinding.FragmentJoinGroupBinding
import kotlinx.coroutines.launch

class JoinGroupFragment : Fragment() {

    private var _binding: FragmentJoinGroupBinding? = null
    private val binding get() = _binding!!
    private val splitRepository = SupabaseSplitRepository()
    private var groupId: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentJoinGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        groupId = arguments?.getString("id") ?: ""
        if (groupId.isEmpty()) {
            Toast.makeText(context, "Invalid join link", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        loadGroupDetails()

        binding.btnConfirmJoin.setOnClickListener {
            joinGroup()
        }

        binding.btnCancelJoin.setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private fun loadGroupDetails() {
        lifecycleScope.launch {
            try {
                val group = splitRepository.fetchGroupById(groupId)
                if (group != null) {
                    binding.tvGroupName.text = group.name
                } else {
                    binding.tvGroupName.text = "Group not found"
                    binding.btnConfirmJoin.isEnabled = false
                }
            } catch (e: Exception) {
                Log.e("JoinGroup", "Error loading group", e)
                binding.tvGroupName.text = "Error loading group"
            }
        }
    }

    private fun joinGroup() {
        lifecycleScope.launch {
            try {
                splitRepository.joinGroup(groupId)
                Toast.makeText(context, "Joined successfully!", Toast.LENGTH_SHORT).show()
                val bundle = Bundle().apply { putString("groupId", groupId) }
                findNavController().navigate(R.id.action_global_groupDetail, bundle)
            } catch (e: Exception) {
                Log.e("JoinGroup", "Error joining", e)
                Toast.makeText(context, "Failed to join", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
