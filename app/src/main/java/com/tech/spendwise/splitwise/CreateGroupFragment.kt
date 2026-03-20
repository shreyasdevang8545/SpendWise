package com.tech.spendwise.splitwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.tech.spendwise.R
import com.tech.spendwise.databinding.FragmentCreateGroupBinding
import com.tech.spendwise.models.SplitGroup
import com.tech.spendwise.utils.UIUtils
import java.util.UUID

class CreateGroupFragment : Fragment() {

    private var _binding: FragmentCreateGroupBinding? = null
    private val binding get() = _binding!!

    private val memberInputs = mutableListOf<EditText>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCreateGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        // Start with one member input
        addMemberInput()

        binding.btnAddMember.setOnClickListener { addMemberInput() }

        binding.btnCreateGroup.setOnClickListener { createGroup() }
    }

    private fun addMemberInput() {
        val ctx = requireContext()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }

        val et = EditText(ctx).apply {
            hint = "Member ${memberInputs.size + 1}"
            setHintTextColor(resources.getColor(R.color.text_secondary, null))
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 15f
            setPadding(32, 24, 32, 24)
            setBackgroundResource(R.drawable.bg_edit_text_outlined)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnRemove = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(resources.getColor(R.color.error_red, null))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                memberInputs.remove(et)
                binding.membersContainer.removeView(row)
            }
        }

        row.addView(et)
        row.addView(btnRemove)
        binding.membersContainer.addView(row)
        memberInputs.add(et)
    }

    private fun createGroup() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.groupNameLayout.error = "Enter a group name"
            return
        }
        binding.groupNameLayout.error = null

        val members = mutableListOf("You")
        for (et in memberInputs) {
            val memberName = et.text.toString().trim()
            if (memberName.isNotEmpty()) members.add(memberName)
        }

        if (members.size < 2) {
            UIUtils.showErrorSnackbar(binding.root, "Add at least one more member")
            return
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            UIUtils.showErrorSnackbar(binding.root, "Please login first")
            return
        }

        val group = SplitGroup(
            id = UUID.randomUUID().toString(),
            name = name,
            members = members
        )

        SplitRepository.saveGroup(uid, group) { result ->
            activity?.runOnUiThread {
                if (result.isSuccess) {
                    UIUtils.showSuccessSnackbar(binding.root, "Group \"$name\" created!")
                    findNavController().popBackStack()
                } else {
                    UIUtils.showErrorSnackbar(binding.root, "Failed to create group")
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
