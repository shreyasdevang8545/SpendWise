package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.tech.spendwise.databinding.FragmentPremiumBinding
import com.tech.spendwise.utils.BillingManager
import com.tech.spendwise.SettingsManager
import kotlinx.coroutines.launch

class PremiumFragment : Fragment() {

    private var _binding: FragmentPremiumBinding? = null
    private val binding get() = _binding!!
    private lateinit var billingManager: BillingManager
    private var selectedProductId = BillingManager.PRODUCT_PRO_YEARLY

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPremiumBinding.inflate(inflater, container, false)
        billingManager = BillingManager(requireContext(), lifecycleScope)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.cardMonthly.setOnClickListener {
            selectPlan(BillingManager.PRODUCT_PRO_MONTHLY)
        }

        binding.cardYearly.setOnClickListener {
            selectPlan(BillingManager.PRODUCT_PRO_YEARLY)
        }

        binding.btnUpgrade.setOnClickListener {
            initiatePurchase()
        }

        // Observe Pro status to disable button if already active
        viewLifecycleOwner.lifecycleScope.launch {
            val sm = SettingsManager(requireContext())
            sm.isProUser.collect { isPro ->
                if (isPro) {
                    binding.btnUpgrade.text = "Subscription Active"
                    binding.btnUpgrade.isEnabled = false
                    binding.btnUpgrade.alpha = 0.5f
                } else {
                    binding.btnUpgrade.text = "Get Pro Now"
                    binding.btnUpgrade.isEnabled = true
                    binding.btnUpgrade.alpha = 1.0f
                }
            }
        }

        // Fetch product details to update price text
        billingManager.getProductDetails { productDetailsList ->
            activity?.runOnUiThread {
                productDetailsList.forEach { details ->
                    val price = details.subscriptionOfferDetails?.firstOrNull()
                        ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                    
                    if (details.productId == BillingManager.PRODUCT_PRO_MONTHLY) {
                        binding.tvMonthlyPrice.text = price ?: "₹49"
                    } else if (details.productId == BillingManager.PRODUCT_PRO_YEARLY) {
                        binding.tvYearlyPrice.text = price ?: "₹449"
                    }
                }
            }
        }
    }

    private fun selectPlan(productId: String) {
        selectedProductId = productId
        if (productId == BillingManager.PRODUCT_PRO_MONTHLY) {
            binding.cardMonthly.setBackgroundResource(R.drawable.bg_premium_card_selected)
            binding.cardYearly.setBackgroundResource(R.drawable.bg_premium_card)
        } else {
            binding.cardMonthly.setBackgroundResource(R.drawable.bg_premium_card)
            binding.cardYearly.setBackgroundResource(R.drawable.bg_premium_card_selected)
        }
    }

    private fun initiatePurchase() {
        if (!billingManager.isReady()) {
            Toast.makeText(requireContext(), "Billing system is not ready. Please try again later.", Toast.LENGTH_SHORT).show()
            return
        }

        billingManager.getProductDetails { productDetailsList ->
            activity?.runOnUiThread {
                val productDetails = productDetailsList.firstOrNull { it.productId == selectedProductId }
                if (productDetails != null) {
                    billingManager.launchPurchaseFlow(requireActivity(), productDetails)
                } else {
                    Toast.makeText(requireContext(), "Product details not found.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
