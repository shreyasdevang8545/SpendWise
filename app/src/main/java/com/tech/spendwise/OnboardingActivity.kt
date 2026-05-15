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
                "Effortlessly track every penny you spend. SpendWise categorizes your expenses automatically, giving you a clear picture of your finances.",
                R.drawable.onboarding_track
            ),
            OnboardingSlide(
                "Automated SMS Scan",
                "Stop entering transactions manually! SpendWise securely scans your bank and wallet SMS alerts to record payments instantly as they happen.",
                R.drawable.onboarding_scan
            ),
            OnboardingSlide(
                "Secure Cloud Sync",
                "Your financial data is your own. We encrypt and sync your records to the cloud so you can access your history safely from any device.",
                R.drawable.onboarding_sync
            ),
            OnboardingSlide(
                "Insightful Analytics",
                "Visualize your financial health with beautiful charts. Set budgets, track trends, and save more with data-driven insights.",
                R.drawable.onboarding_insights
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

    data class OnboardingSlide(val title: String, val desc: String, val imageRes: Int)

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
                itemBinding.slideImage.setImageResource(slide.imageRes)
            }
        }
    }
}
