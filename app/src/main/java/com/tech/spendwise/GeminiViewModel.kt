package com.tech.spendwise

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tech.spendwise.models.Transaction
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.append
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val safetySettings: List<GeminiSafetySetting>? = null,
    val generationConfig: GeminiGenerationConfig? = null
)

@Serializable
data class GeminiSafetySetting(
    val category: String,
    val threshold: String
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = 0.7,
    val topP: Double? = 0.95,
    val topK: Int? = 40,
    val maxOutputTokens: Int? = 1024
)

@Serializable
data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
data class GeminiPart(val text: String)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val promptFeedback: GeminiPromptFeedback? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
    val safetyRatings: List<GeminiSafetyRating>? = null
)

@Serializable
data class GeminiPromptFeedback(
    val safetyRatings: List<GeminiSafetyRating>? = null
)

@Serializable
data class GeminiSafetyRating(
    val category: String,
    val probability: String
)

class GeminiViewModel : ViewModel() {

    private val _insights = MutableLiveData<String>()
    val insights: LiveData<String> = _insights

    private val _isGenerating = MutableLiveData<Boolean>(false)
    val isGenerating: LiveData<Boolean> = _isGenerating

    // User's Gemini API Key
    private val apiKey = "AIzaSyAy6iOFJz8ZpmheVRGFVeF-NgzeaaQYsvM"
    
    // Initialize Ktor HttpClient (Ktor 3.0 compatible)
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
            })
        }
    }

    private val jsonHelper = Json { 
        ignoreUnknownKeys = true 
        isLenient = true 
    }

    fun generateInsights(transactions: List<Transaction>) {
        if (transactions.isEmpty()) {
            _insights.postValue("Add some transactions to get AI-powered insights!")
            return
        }

        _isGenerating.value = true
        viewModelScope.launch {
            try {
                val promptText = "Analyze these transactions and give 3 short bullet points of financial advice. " +
                           "Be conversational but concise. Use currency ₹. " +
                           "Transactions: ${transactions.take(20).joinToString { "${it.merchant}: ₹${it.amount}" }}"
                
                val requestBody = GeminiRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(GeminiPart(text = promptText))
                        )
                    ),
                    safetySettings = listOf(
                        GeminiSafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_ONLY_HIGH"),
                        GeminiSafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_ONLY_HIGH"),
                        GeminiSafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_ONLY_HIGH"),
                        GeminiSafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_ONLY_HIGH")
                    )
                )

                val httpResponse = client.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent") {
                    headers {
                        append("x-goog-api-key", apiKey)
                    }
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                val rawResponse = httpResponse.bodyAsText()
                Log.d("GeminiViewModel", "Raw response string: $rawResponse")

                val response: GeminiResponse = jsonHelper.decodeFromString(rawResponse)
                Log.d("GeminiViewModel", "Parsed object: $response")

                val candidate = response.candidates?.firstOrNull()
                val responseText = candidate?.content?.parts?.firstOrNull()?.text
                
                if (responseText != null) {
                    _insights.postValue(responseText)
                } else {
                    val finishReason = candidate?.finishReason
                    val feedback = response.promptFeedback
                    Log.w("GeminiViewModel", "Blocked! FinishReason: $finishReason, Feedback: $feedback")
                    
                    val errorMessage = when (finishReason) {
                        "SAFETY" -> "Insights were restricted by safety filters. Try again with different transactions."
                        "OTHER" -> "AI was unable to generate insights for this context."
                        else -> "AI was unable to provide insights for this data. (${finishReason ?: "unknown"})"
                    }
                    _insights.postValue(errorMessage)
                }
            } catch (e: Exception) {
                Log.e("GeminiViewModel", "API Error", e)
                _insights.postValue("Unable to generate insights at this time. Please check your connection.")
            } finally {
                _isGenerating.postValue(false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client.close()
    }
}
