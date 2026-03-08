package com.tech.spendwise

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Structured result of parsing a voice-spoken expense phrase.
 */
@Parcelize
data class ParsedExpense(
    val amount: Double,
    val category: String,
    val merchant: String,
    val type: String           // "expense" or "income"
) : Parcelable

/**
 * Pure-Kotlin NLP parser that converts a natural language sentence
 * into a [ParsedExpense] without any cloud API or ML model.
 *
 * Example inputs:
 * - "spent 250 on lunch"        → {250.0, Food, "lunch", expense}
 * - "300 groceries"             → {300.0, Food, "",      expense}
 * - "paid 1200 rent"            → {1200.0, Bills, "rent", expense}
 * - "got 5000 salary"           → {5000.0, Income, "salary", income}
 * - "uber 350"                  → {350.0, Transport, "uber", expense}
 */
object VoiceExpenseParser {

    // ── Amount ─────────────────────────────────────────────────────────────
    private val AMOUNT_REGEX = Regex("""(?:₹|rs\.?\s*)?(\d[\d,]*(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)

    // ── Income trigger keywords ─────────────────────────────────────────────
    private val INCOME_KEYWORDS = setOf(
        "received", "got", "salary", "income", "credited", "earned",
        "refund", "cashback", "bonus", "stipend", "pension", "dividend"
    )

    // ── Expense trigger keywords (just used to strip them, default is expense) ─
    private val EXPENSE_KEYWORDS = setOf(
        "spent", "spend", "paid", "pay", "bought", "purchased", "charged",
        "debited", "transferred", "sent", "for", "on", "at", "to"
    )

    // ── Category keyword mapping ────────────────────────────────────────────
    private val CATEGORY_MAP: List<Pair<Set<String>, String>> = listOf(
        setOf("food", "lunch", "dinner", "breakfast", "snack", "meal", "restaurant",
              "eat", "zomato", "swiggy", "biryani", "pizza", "burger", "chai",
              "coffee", "cafe", "hotel", "canteen") to "Food",

        setOf("grocery", "groceries", "vegetables", "fruits", "supermarket",
              "market", "dmart", "bigbasket", "blinkit", "zepto", "milk",
              "eggs", "bread") to "Groceries",

        setOf("uber", "ola", "rapido", "auto", "cab", "taxi", "bus", "metro",
              "train", "flight", "petrol", "diesel", "fuel", "parking",
              "transport", "travel", "ticket", "rickshaw") to "Transport",

        setOf("electricity", "water", "gas", "internet", "wifi", "broadband",
              "mobile", "recharge", "phone", "bill", "rent", "emi",
              "subscription", "netflix", "amazon", "spotify", "hotstar") to "Bills",

        setOf("medicine", "doctor", "hospital", "pharmacy", "health", "medical",
              "gym", "fitness", "clinic", "dental", "eye") to "Health",

        setOf("movie", "cinema", "concert", "game", "entertainment", "netflix",
              "party", "club", "sport", "fun", "outing", "trip", "tour") to "Entertainment",

        setOf("shopping", "clothes", "shirt", "shoes", "bag", "amazon", "flipkart",
              "myntra", "dress", "watch", "gadget", "electronics", "mobile",
              "laptop", "earphones") to "Shopping",

        setOf("salary", "income", "freelance", "consulting", "revenue", "profit",
              "bonus", "stipend", "pension", "dividend", "interest") to "Income",

        setOf("invest", "investment", "mutual", "fund", "stock", "shares",
              "sip", "nps", "ppf", "fd", "gold", "crypto") to "Investment"
    )

    /**
     * Parses [text] into a [ParsedExpense].
     * Falls back gracefully: category defaults to "Others", merchant to "",
     * type to "expense".
     */
    fun parse(text: String): ParsedExpense {
        val lowerText = text.lowercase().trim()
        val tokens = lowerText.split(Regex("\\s+")).toMutableList()

        // ── 1. Detect type ──────────────────────────────────────────────────
        val type = if (tokens.any { it in INCOME_KEYWORDS }) "income" else "expense"

        // ── 2. Extract amount ───────────────────────────────────────────────
        val amountMatch = AMOUNT_REGEX.find(lowerText)
        val amount = amountMatch?.groupValues?.get(1)
            ?.replace(",", "")
            ?.toDoubleOrNull() ?: 0.0

        // Remove the amount token (and any ₹/rs prefix attached to it) from tokens
        if (amountMatch != null) {
            val matchedValue = amountMatch.value.trim()
            tokens.removeAll { matchedValue.contains(it) && it.matches(Regex("""[\d,₹\.rs]+""")) }
        }

        // ── 3. Detect category ──────────────────────────────────────────────
        var category = "Others"
        var categoryMatchedTokens = emptySet<String>()
        for ((keywords, cat) in CATEGORY_MAP) {
            val matched = tokens.filter { it in keywords }.toSet()
            if (matched.isNotEmpty()) {
                category = cat
                categoryMatchedTokens = matched
                break
            }
        }

        // ── 4. Extract merchant ─────────────────────────────────────────────
        val stopWords = INCOME_KEYWORDS + EXPENSE_KEYWORDS + categoryMatchedTokens +
                setOf("on", "at", "for", "to", "from", "a", "an", "the", "in", "of", "rs", "rupees", "inr")
        val merchantTokens = tokens.filter { it !in stopWords && it.length > 1 }
        val merchant = merchantTokens.joinToString(" ").trim()

        return ParsedExpense(
            amount = amount,
            category = if (type == "income" && category == "Others") "Income" else category,
            merchant = merchant,
            type = type
        )
    }
}
