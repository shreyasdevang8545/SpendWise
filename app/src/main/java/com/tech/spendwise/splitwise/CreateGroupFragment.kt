package com.tech.spendwise.splitwise

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import com.tech.spendwise.SupabaseInstance
import kotlinx.coroutines.launch
import com.tech.spendwise.R
import com.tech.spendwise.databinding.FragmentCreateGroupBinding
import com.tech.spendwise.models.SplitGroup
import com.tech.spendwise.utils.UIUtils
import com.tech.spendwise.splitwise.SupabaseSplitRepository
import java.util.UUID

class CreateGroupFragment : Fragment() {

    private var _binding: FragmentCreateGroupBinding? = null
    private val binding get() = _binding!!
    private val splitRepository = SupabaseSplitRepository()

    private val memberEntries = mutableListOf<MemberEntry>()
    private var pendingPickIndex = -1

    private data class MemberEntry(
        val et: EditText,
        var phone: String = "",
        val row: View
    )

    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            uri?.let { handleContactResult(it) }
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pickContactLauncher.launch(null)
            } else {
                UIUtils.showErrorSnackbar(binding.root, "Permission denied to read contacts")
            }
        }

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
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }

        val et = EditText(ctx).apply {
            hint = "Member ${memberEntries.size + 1}"
            setHintTextColor(resources.getColor(R.color.text_secondary, null))
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 15f
            setPadding(32, 24, 32, 24)
            setBackgroundResource(R.drawable.bg_edit_text_outlined)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val entry = MemberEntry(et, "", row)
        val currentIndex = memberEntries.size
        memberEntries.add(entry)

        val btnPick = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_person) // Use person icon for contact picker
            try { setImageResource(R.drawable.ic_person) } catch(_: Exception) {}
            setColorFilter(resources.getColor(R.color.primary_green, null))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                pendingPickIndex = memberEntries.indexOf(entry)
                checkPermissionAndPick()
            }
        }

        val btnRemove = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(resources.getColor(R.color.error_red, null))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                memberEntries.remove(entry)
                binding.membersContainer.removeView(row)
            }
        }

        row.addView(et)
        row.addView(btnPick)
        row.addView(btnRemove)
        binding.membersContainer.addView(row)
    }

    private fun checkPermissionAndPick() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED) {
            pickContactLauncher.launch(null)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun handleContactResult(uri: Uri) {
        if (pendingPickIndex == -1 || pendingPickIndex >= memberEntries.size) return
        
        val contentResolver = requireContext().contentResolver
        var name = ""
        var phone = ""

        // Query contact name
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                
                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                if (idIndex != -1) {
                    val id = cursor.getString(idIndex)
                    // Query phone number
                    contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id),
                        null
                    )?.use { phoneCursor ->
                        if (phoneCursor.moveToFirst()) {
                            val pIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            if (pIndex != -1) phone = phoneCursor.getString(pIndex)
                        }
                    }
                }
            }
        }

        if (name.isNotEmpty()) {
            val entry = memberEntries[pendingPickIndex]
            entry.et.setText(name)
            entry.phone = phone.replace(Regex("[^0-9+]"), "") // Cleanup phone
        }
    }

    private fun createGroup() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.groupNameLayout.error = "Enter a group name"
            return
        }
        binding.groupNameLayout.error = null

        val myName = SupabaseInstance.currentUserDisplayName() ?: "Me"
        val members = mutableListOf("$myName|")
        for (entry in memberEntries) {
            val memberName = entry.et.text.toString().trim()
            if (memberName.isNotEmpty()) {
                members.add("$memberName|${entry.phone}")
            }
        }

        if (members.size < 2) {
            UIUtils.showErrorSnackbar(binding.root, "Add at least one more member")
            return
        }

        val uid = SupabaseInstance.currentUserId()
        if (uid == null) {
            UIUtils.showErrorSnackbar(binding.root, "Please login first")
            return
        }

        val group = SplitGroup(
            id = "", // Supabase will generate ID
            name = name,
            members = members
        )

        lifecycleScope.launch {
            val resultId = splitRepository.saveGroup(group)
            if (resultId != null) {
                UIUtils.showSuccessSnackbar(binding.root, "Group \"$name\" created!")
                findNavController().popBackStack()
            } else {
                UIUtils.showErrorSnackbar(binding.root, "Failed to create group")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
