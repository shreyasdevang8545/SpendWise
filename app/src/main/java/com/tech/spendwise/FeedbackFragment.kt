package com.tech.spendwise

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tech.spendwise.databinding.FragmentFeedbackBinding
import com.tech.spendwise.databinding.ItemFeedbackBinding
import com.tech.spendwise.utils.UIUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

class FeedbackFragment : Fragment() {

    private var _binding: FragmentFeedbackBinding? = null
    private val binding get() = _binding!!
    private val repository = SupabaseRepository()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.feedbackToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnSubmit.setOnClickListener {
            val message = binding.etFeedback.text.toString().trim()
            if (message.isEmpty()) {
                binding.tilFeedback.error = getString(R.string.error_feedback_empty)
                return@setOnClickListener
            }
            binding.tilFeedback.error = null
            submitFeedback(message)
        }

        setupRecyclerView()
        loadFeedbacks()
    }

    private fun submitFeedback(message: String) {
        lifecycleScope.launch {
            binding.btnSubmit.isEnabled = false
            binding.loadingIndicator.visibility = View.VISIBLE
            repository.saveFeedback(message)
            binding.etFeedback.text?.clear()
            UIUtils.showSuccessSnackbar(binding.root, getString(R.string.msg_feedback_sent))
            loadFeedbacks()
            binding.btnSubmit.isEnabled = true
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    private fun setupRecyclerView() {
        binding.rvFeedbacks.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun loadFeedbacks() {
        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            val feedbacks = repository.fetchFeedbacks()
            if (feedbacks.isNotEmpty()) {
                binding.tvPreviousFeedbackTitle.visibility = View.VISIBLE
                binding.rvFeedbacks.adapter = FeedbackAdapter(feedbacks)
            } else {
                binding.tvPreviousFeedbackTitle.visibility = View.GONE
            }
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class FeedbackAdapter(private val items: List<JsonObject>) :
        RecyclerView.Adapter<FeedbackAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemFeedbackBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemFeedbackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val message = item["message"]?.jsonPrimitive?.content ?: ""
            val replyObj = item["reply"]
            val reply = if (replyObj is kotlinx.serialization.json.JsonNull) null else replyObj?.jsonPrimitive?.contentOrNull
            val status = item["status"]?.jsonPrimitive?.content ?: "pending"
            
            val isReplied = status.equals("replied", ignoreCase = true) || !reply.isNullOrBlank()

            val rawDate = item["created_at"]?.jsonPrimitive?.content ?: ""
            val createdAt = rawDate.toLongOrNull() ?: try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(rawDate.take(19))?.time ?: 0L
            } catch (e: Exception) { 0L }

            holder.binding.tvMessage.text = message
            holder.binding.tvDate.text = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(createdAt))
            
            holder.binding.tvStatus.text = if (isReplied) getString(R.string.label_status_replied) else getString(R.string.label_status_pending)
            
            if (isReplied) {
                holder.binding.tvStatus.setTextColor(0xFF2E7D32.toInt())
                holder.binding.tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0x332E7D32.toInt())
                
                if (!reply.isNullOrBlank()) {
                    holder.binding.layoutReply.visibility = View.VISIBLE
                    holder.binding.tvReply.text = reply
                } else {
                    holder.binding.layoutReply.visibility = View.GONE
                }
            } else {
                holder.binding.tvStatus.setTextColor(0xFFFF9800.toInt())
                holder.binding.tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(0x33FF9800.toInt())
                holder.binding.layoutReply.visibility = View.GONE
            }
        }

        override fun getItemCount() = items.size
    }
}
