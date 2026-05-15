package com.tech.spendwise

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.tabs.TabLayoutMediator
import com.tech.spendwise.databinding.ActivityOnboardingBinding
import com.tech.spendwise.databinding.ItemOnboardingSlideBinding
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)

        val slides = listOf(
            OnboardingSlide(
                "Smart Expense Tracking",
                "Take full control of your financial life with SpendWise. Our intelligent tracking system allows you to record expenses in seconds. Whether it's a small coffee or a major bill, every penny is accounted for. Experience real-time categorization that helps you understand exactly where your money goes each month."
            ),
            OnboardingSlide(
                "Automated SMS Scanning",
                "Eliminate the hassle of manual entry. SpendWise securely scans your incoming bank and wallet SMS alerts, instantly recording transactions as they happen. We support all major banks and digital wallets, ensuring your history is always up-to-date without you ever having to open the app to type a single number."
            ),
            OnboardingSlide(
                "Secure Cloud Sync",
                "Your financial data is sensitive and precious. SpendWise uses bank-grade encryption to sync your records to our secure cloud. Access your full financial history from any device, anywhere. Even if you lose your phone, your data remains safe, private, and fully recoverable at any time."
            ),
            OnboardingSlide(
                "AI Financial Insights",
                "Get more than just numbers. Our AI-driven analytics engine processes your spending habits to provide actionable insights. Set smart budgets, receive overspending alerts, and discover new ways to save. SpendWise isn't just a tracker; it's your personal financial coach designed to help you build wealth."
            )
        )

        val adapter = OnboardingAdapter(slides)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.dotIndicator, binding.viewPager) { _, _ -> }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == slides.size - 1) {
                    binding.btnNext.text = "Get Started"
                } else {
                    binding.btnNext.text = "Next"
                }
            }
        })

        binding.btnNext.setOnClickListener {
            if (binding.viewPager.currentItem < slides.size - 1) {
                binding.viewPager.currentItem += 1
            } else {
                completeOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener {
            completeOnboarding()
        }
    }

    private fun completeOnboarding() {
        lifecycleScope.launch {
            settingsManager.setBoolean(SettingsManager.ONBOARDING_COMPLETED, true)
            startActivity(Intent(this@OnboardingActivity, AuthActivity::class.java))
            finish()
        }
    }

    data class OnboardingSlide(val title: String, val desc: String)

    inner class OnboardingAdapter(private val slides: List<OnboardingSlide>) :
        RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
            val binding = ItemOnboardingSlideBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return SlideViewHolder(binding)
        }

        override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
            holder.bind(slides[position])
        }

        override fun getItemCount(): Int = slides.size

        inner class SlideViewHolder(private val itemBinding: ItemOnboardingSlideBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(slide: OnboardingSlide) {
                itemBinding.titleText.text = slide.title
                itemBinding.descText.text = slide.desc
            }
        }
    }
}
