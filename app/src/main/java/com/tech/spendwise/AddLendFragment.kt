package com.tech.spendwise

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import androidx.lifecycle.lifecycleScope
import com.tech.spendwise.SupabaseInstance
import kotlinx.coroutines.launch
import com.tech.spendwise.databinding.FragmentAddLendBinding
import com.tech.spendwise.models.LendTransaction
import com.tech.spendwise.utils.UIUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class AddLendFragment : Fragment() {

    private var _binding: FragmentAddLendBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransactionViewModel by activityViewModels()
    private val supabaseRepository = SupabaseRepository()

    private var selectedReturnDate: Long = 0
    private var contactPhoneNumber: String? = null
    private val dateFormatter = SimpleDateFormat("dd MMM, yyyy", Locale.getDefault())

    // ─── Launchers ────────────────────────────────────────────────────────────

    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
            uri?.let { handleContactResult(it) }
        }

    private val requestContactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pickContactLauncher.launch(null)
            } else {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
                    // Permanently denied
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Contacts Permission Required")
                        .setMessage("You have denied contacts access. This is required to pick people directly from your phonebook. Please enable it in app settings.")
                        .setPositiveButton("Go to Settings") { _, _ -> openAppSettings() }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    UIUtils.showErrorSnackbar(
                        binding.root,
                        "Contacts permission needed to pick a person"
                    )
                }
            }
        }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddLendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val lendId = arguments?.getString("lendId")

        if (lendId != null) {
            // Edit mode
            binding.toolbar.title            = "Edit Lend Detail"
            binding.btnSave.text             = "Update Lend Detail"
            binding.switchSendSms.visibility = View.GONE
            loadLend(lendId)
        } else {
            // New lend — default return date is tomorrow
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            updateDate(calendar.timeInMillis)
            binding.switchSendSms.visibility = View.VISIBLE

            // Update switch label to say WhatsApp instead of SMS
            binding.switchSendSms.text = "Send WhatsApp reminder"
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.etReturnDate.setOnClickListener     { showDatePicker() }
        binding.btnSave.setOnClickListener          { saveLend(lendId) }
        binding.btnPickContact.setOnClickListener   { checkContactsPermissionAndPick() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─── Load lend (edit mode) ────────────────────────────────────────────────

    private fun loadLend(lendId: String) {
        val uid = SupabaseInstance.currentUserId() ?: return
        lifecycleScope.launch {
            val lend = supabaseRepository.getLendById(lendId)
            lend ?: return@launch
            binding.etName.setText(lend.name)
            binding.etAmount.setText(lend.amount.toString())
            updateDate(lend.returnDate)
            val chipId = when (lend.paymentMode.uppercase()) {
                "UPI"  -> R.id.chipUpi
                "CASH" -> R.id.chipCash
                "CARD" -> R.id.chipCard
                else   -> R.id.chipOther
            }
            binding.paymentChipGroup.check(chipId)
        }
    }

    // ─── Date picker ──────────────────────────────────────────────────────────

    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        if (selectedReturnDate > 0) calendar.timeInMillis = selectedReturnDate

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                Calendar.getInstance().also {
                    it.set(year, month, day)
                    updateDate(it.timeInMillis)
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
            show()
        }
    }

    private fun updateDate(timestamp: Long) {
        selectedReturnDate = timestamp
        binding.etReturnDate.setText(dateFormatter.format(timestamp))
    }

    // ─── Contact picker ───────────────────────────────────────────────────────

    private fun checkContactsPermissionAndPick() {
        val permission = Manifest.permission.READ_CONTACTS
        when {
            ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED -> {
                pickContactLauncher.launch(null)
            }
            shouldShowRequestPermissionRationale(permission) -> {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Contacts Access")
                    .setMessage("SpendWise needs contacts access to let you pick a person directly from your phonebook and get their phone number for WhatsApp reminders.")
                    .setPositiveButton("Grant Access") { _, _ ->
                        requestContactsPermissionLauncher.launch(permission)
                    }
                    .setNegativeButton("Not Now", null)
                    .show()
            }
            else -> {
                requestContactsPermissionLauncher.launch(permission)
            }
        }
    }

    private fun handleContactResult(contactUri: Uri) {
        val cursor = requireContext().contentResolver.query(
            contactUri, null, null, null, null
        ) ?: run {
            Log.e("AddLendFragment", "Contact cursor is null")
            return
        }

        cursor.use {
            if (!it.moveToFirst()) {
                Log.e("AddLendFragment", "Contact cursor is empty")
                return
            }

            val name      = it.getString(it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)) ?: ""
            val contactId = it.getString(it.getColumnIndex(ContactsContract.Contacts._ID)) ?: ""

            Log.d("AddLendFragment", "Contact picked — name=$name  id=$contactId")
            binding.etName.setText(name)

            // Fetch phone number for this contact
            val phoneCursor = requireContext().contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )

            if (phoneCursor == null || !phoneCursor.moveToFirst()) {
                Log.w("AddLendFragment", "No phone number found for $name")
                contactPhoneNumber                   = null
                binding.tvSelectedContact.text       = "Selected: $name (no phone number)"
                binding.tvSelectedContact.visibility = View.VISIBLE
                phoneCursor?.close()
                return
            }

            phoneCursor.use { pc ->
                val raw     = pc.getString(0) ?: ""
                val cleaned = cleanPhoneNumber(raw)
                contactPhoneNumber                   = cleaned
                Log.d("AddLendFragment", "Raw='$raw'  Cleaned='$cleaned'")
                binding.tvSelectedContact.text       = "Selected: $name ($cleaned)"
                binding.tvSelectedContact.visibility = View.VISIBLE
            }
        }
    }

    // ─── Phone number cleaner ─────────────────────────────────────────────────

    /**
     * Cleans a raw phone number for use in WhatsApp deep links.
     * WhatsApp wa.me links need: international format WITHOUT the +
     * Examples:
     *   "+91 94484 35790"  →  "919448435790"
     *   "094484 35790"     →  "919448435790"  (Indian local with 0 prefix)
     *   "+919448435790"    →  "919448435790"
     *   "9448435790"       →  "919448435790"  (10-digit Indian number)
     */
    private fun cleanPhoneNumber(raw: String): String {
        // Strip everything except digits and leading +
        var cleaned = raw.replace("\\s".toRegex(), "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        // Remove leading +
        if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1)
        }

        // Handle Indian local format: starts with 0 → replace with 91
        if (cleaned.startsWith("0")) {
            cleaned = "91" + cleaned.substring(1)
        }

        // Handle bare 10-digit Indian number → prepend 91
        if (cleaned.length == 10) {
            cleaned = "91$cleaned"
        }

        Log.d("AddLendFragment", "cleanPhoneNumber: '$raw' → '$cleaned'")
        return cleaned
    }

    // ─── Save lend ────────────────────────────────────────────────────────────

    private fun saveLend(existingId: String? = null) {

        // ── Validate inputs ──
        val name      = binding.etName.text.toString().trim()
        val amountStr = binding.etAmount.text.toString().trim()
        val amount    = amountStr.toDoubleOrNull() ?: 0.0

        if (name.isEmpty()) {
            binding.nameLayout.error = "Enter person name"
            return
        }
        binding.nameLayout.error = null

        if (amount <= 0) {
            binding.amountLayout.error = "Enter valid amount"
            return
        }
        binding.amountLayout.error = null

        // ── Validate contact if WhatsApp switch is ON ──
        val wantsWhatsApp = existingId == null && binding.switchSendSms.isChecked
        if (wantsWhatsApp && contactPhoneNumber == null) {
            UIUtils.showErrorSnackbar(
                binding.root,
                "Please pick a contact to send WhatsApp reminder"
            )
            return
        }

        // ── Auth check ──
        val uid = SupabaseInstance.currentUserId()
        if (uid == null) {
            UIUtils.showErrorSnackbar(binding.root, "Please login to save")
            return
        }

        // ── Build payment mode ──
        val checkedChipId = binding.paymentChipGroup.checkedChipId
        val paymentMode   = if (checkedChipId != View.NO_ID) {
            binding.paymentChipGroup.findViewById<Chip>(checkedChipId).text.toString()
        } else "Other"

        // ── Build lend object ──
        val lendId = existingId ?: UUID.randomUUID().toString()
        val lend   = LendTransaction(
            id          = lendId,
            name        = name,
            amount      = amount,
            paymentMode = paymentMode,
            returnDate  = selectedReturnDate,
            phoneNumber = contactPhoneNumber, // Use the class-level variable
            note        = "" // Default empty note for now, as there's no input field
        )

        // ── Save to Supabase (and linked transaction) ──
        viewModel.saveLend(lend)

        // ── Add to local UI flow without triggering another cloud sync ──
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()
        ).format(Date())

        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")

        val lendJson = """
            {
              "amount": $amount,
              "type": "DEBIT",
              "merchant": "${name.esc()}",
              "category": "Lend",
              "payment_mode": "${paymentMode.esc()}",
              "currency": "INR",
              "saved_at": "$timestamp",
              "is_lend": true,
              "lend_name": "${name.esc()}"
            }
        """.trimIndent()
        viewModel.addConfirmedTransaction(lendJson, syncToCloud = false)

        // ── Schedule return date reminder notification ──
        ReminderManager.scheduleReminder(
            requireContext(), lendId, name, amount, selectedReturnDate
        )

        val successMsg = if (existingId != null) "Lend detail updated" else "Lend saved for $name"

        // ── Send WhatsApp reminder if requested ──
        if (wantsWhatsApp && contactPhoneNumber != null) {
            val formattedAmount = "%.0f".format(amount)
            val dateText        = binding.etReturnDate.text.toString()

            UIUtils.showSuccessSnackbar(binding.root, "$successMsg · Preparing link...")

            // Shorten URL on background thread, then open WhatsApp
            val longUrl = buildLendPageUrl(lendId, contactPhoneNumber!!, name, formattedAmount, dateText)

            Thread {
                val shortUrl = shortenUrl(longUrl)
                activity?.runOnUiThread {
                    openWhatsApp(
                        phone  = contactPhoneNumber!!,
                        name   = name,
                        amount = formattedAmount,
                        date   = dateText,
                        lendPageUrl = shortUrl
                    )
                    findNavController().popBackStack()
                }
            }.start()
        } else {
            UIUtils.showSuccessSnackbar(binding.root, successMsg)
            findNavController().popBackStack()
        }
    }

    // ─── URL Shortener ────────────────────────────────────────────────────────

    /**
     * Shortens a URL using TinyURL's free API (no API key required).
     * Falls back to the original URL if the shortening service is unreachable.
     */
    private fun shortenUrl(longUrl: String): String {
        return try {
            val apiUrl = "https://tinyurl.com/api-create.php?url=${java.net.URLEncoder.encode(longUrl, "UTF-8")}"
            val connection = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val shortUrl = connection.inputStream.bufferedReader().readText().trim()
                Log.d("AddLendFragment", "URL shortened: $shortUrl")
                shortUrl
            } else {
                Log.w("AddLendFragment", "TinyURL returned ${connection.responseCode}, using long URL")
                longUrl
            }
        } catch (e: Exception) {
            Log.w("AddLendFragment", "URL shortening failed: ${e.message}, using long URL")
            longUrl
        }
    }

    // ─── WhatsApp Intent ──────────────────────────────────────────────────────

    /**
     * Opens WhatsApp with a pre-filled lend reminder message.
     */
    private fun openWhatsApp(phone: String, name: String, amount: String, date: String, lendPageUrl: String) {
        val message = buildWhatsAppMessage(name, amount, date, lendPageUrl)

        Log.d("AddLendFragment", "Opening WhatsApp")
        Log.d("AddLendFragment", "Phone  : $phone")
        Log.d("AddLendFragment", "Message: $message")

        val url    = "https://wa.me/$phone?text=${Uri.encode(message)}"
        val uri    = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            intent.setPackage("com.whatsapp.w4b")
            try {
                startActivity(intent)
            } catch (e2: ActivityNotFoundException) {
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(null)
                }
                try {
                    startActivity(browserIntent)
                } catch (e3: ActivityNotFoundException) {
                    Toast.makeText(
                        requireContext(),
                        "WhatsApp is not installed on this device",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Builds a lend details page URL with transaction data as query parameters.
     */
    private fun buildLendPageUrl(lendId: String, phone: String, name: String, amount: String, date: String): String {
        val isoDate = if (selectedReturnDate > 0) {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(selectedReturnDate))
        } else ""
        val lentOnFormatted = dateFormatter.format(Date())
        val returnLabel = binding.etReturnDate.text.toString()

        val paymentMode = run {
            val chipId = binding.paymentChipGroup.checkedChipId
            if (chipId != View.NO_ID) {
                binding.paymentChipGroup.findViewById<Chip>(chipId).text.toString()
            } else "Other"
        }

        return Uri.Builder()
            .scheme("https")
            .authority("shreyasdevang8545.github.io")
            .path("/SpendWise/lend.html")
            .appendQueryParameter("id", lendId)
            .appendQueryParameter("name", name)
            .appendQueryParameter("phone", phone)
            .appendQueryParameter("amount", amount)
            .appendQueryParameter("date", isoDate)
            .appendQueryParameter("returnLabel", returnLabel)
            .appendQueryParameter("lentOn", lentOnFormatted)
            .appendQueryParameter("mode", paymentMode)
            .appendQueryParameter("note", "Lend via SpendWise")
            .appendQueryParameter("status", "pending")
            .build()
            .toString()
    }

    /**
     * Builds the WhatsApp message with a (shortened) link to the lend details page.
     */
    private fun buildWhatsAppMessage(name: String, amount: String, date: String, lendPageUrl: String): String {
        return """
            Hi $name! 👋
            
            Just a quick note — I've lent you ₹$amount and recorded it in SpendWise.
            
            📅 Suggested return date: *$date*
            
            📋 View full details:
            $lendPageUrl
            
            No rush, just keeping track! 😊
            
            — Sent via SpendWise 💚
        """.trimIndent()
    }
}