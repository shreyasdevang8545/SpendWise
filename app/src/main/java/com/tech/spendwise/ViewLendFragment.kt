package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.tech.spendwise.databinding.FragmentViewLendBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * Displays lend details when opened via a deep link.
 *
 * The deep link URL is:
 *   https://shreyasdevang8545.github.io/SpendWise/lend.html?name=X&phone=Y&amount=Z&...
 *
 * NavController automatically extracts query parameters as fragment arguments.
 */
class ViewLendFragment : Fragment() {

    private var _binding: FragmentViewLendBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewLendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        populateFromArgs()
    }

    private fun populateFromArgs() {
        val args = arguments ?: return

        val name        = args.getString("name", "Unknown")
        val phone       = args.getString("phone", "")
        val amountStr   = args.getString("amount", "0")
        val lentOn      = args.getString("lentOn", "—")
        val returnLabel = args.getString("returnLabel", "—")
        val mode        = args.getString("mode", "UPI")
        val note        = args.getString("note", "—")
        val status      = args.getString("status", "pending")

        val amount = amountStr.toDoubleOrNull() ?: 0.0
        val formatter = NumberFormat.getNumberInstance(Locale("en", "IN"))

        // ── Hero ──
        binding.tvName.text   = name
        binding.tvPhone.text  = if (phone.isNotBlank()) "+$phone" else ""
        binding.tvAmount.text = "₹${formatter.format(amount)}"

        // Initials
        val initials = name.split(" ")
            .filter { it.isNotBlank() }
            .map { it.first().uppercase() }
            .take(2)
            .joinToString("")
        binding.tvAvatar.text = initials

        // Status badge
        binding.tvStatus.text = when (status) {
            "settled"  -> "Settled ✓"
            "overdue"  -> "Overdue"
            "partial"  -> "Partial"
            else       -> "Pending"
        }

        // ── Details ──
        binding.tvLentOn.text      = lentOn
        binding.tvReturnDate.text  = returnLabel
        binding.tvPaymentMode.text = mode
        binding.tvNote.text        = note
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
