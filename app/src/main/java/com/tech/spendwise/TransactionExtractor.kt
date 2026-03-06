package com.tech.spendwise

import java.util.regex.Pattern

/**
 * Extracts structured transaction information from bank/wallet SMS messages.
 *
 * Uses only [java.util.regex] (no external libraries).
 * JSON is built manually so this class works on both the Android runtime
 * and the host JVM (unit-test environment) without requiring org.json mocks.
 */
object TransactionExtractor {

    // ── Currency & Amount ────────────────────────────────────────────────────

    /** Matches: INR 1,200.50  |  AED 451.20  |  Rs 800  |  Rs. 17987.32 */
    private val AMOUNT_PATTERN: Pattern = Pattern.compile(
        """(?i)(?:INR|AED|Rs\.?)\s*([\d,]+(?:\.\d+)?)"""
    )

    /** Matches the currency code/keyword itself. */
    private val CURRENCY_PATTERN: Pattern = Pattern.compile(
        """(?i)\b(INR|AED|Rs\.?)\b"""
    )

    // ── Transaction Type ─────────────────────────────────────────────────────

    private val CREDIT_PATTERN: Pattern = Pattern.compile(
        """(?i)\b(credited|credit|received|deposited)\b"""
    )
    private val DEBIT_PATTERN: Pattern = Pattern.compile(
        """(?i)\b(debited|debit|spent|purchase|paid)\b"""
    )

    // ── Entity (Merchant / Person) ───────────────────────────────────────────

    /** Noise suffixes to strip from entity names. */
    private val NOISE_PHRASES = listOf(
        Regex("""(?i)\s*is\s+approved"""),
        Regex("""(?i)\s*avl\s*bal.*"""),
        Regex("""(?i)\s*upi\s*ref\s*no\s*\d+.*"""),
        Regex("""(?i)\s*in\s+[A-Z][a-z]+.*"""),   // "in Abu Dhabi, UAE"
        Regex("""(?i)\s*\*+\d+\s*"""),             // "**0121"
    )

    // ── Date / Time ──────────────────────────────────────────────────────────

    /**
     * Comprehensive date format patterns for IST (Indian Standard Time).
     * Handles multiple formats:
     *   ISO:        2025-11-09, 2025/11/09
     *   European:   09-11-2025, 09/11/2025
     *   Text:       09-Nov-2025, 09-NOV-2025, 09-November-2025, 09 Nov 2025
     *   DMY:        09-11-25 (DD-MM-YY)
     *   With time:  09/11/2025 07:01:25, 09/11/2025 7:01 PM, etc.
     *   IST suffix: 09/11/2025 07:01:25 IST, 09/11/2025 7:01 PM IST
     */
    private val DATE_PATTERNS = listOf(
        // ISO format: YYYY-MM-DD or YYYY/MM/DD
        Pattern.compile(
            """(\d{4}[-/]\d{2}[-/]\d{2})""" +
            """(?:\s+(\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AP]M)?))?\s*(?:IST)?""",
            Pattern.CASE_INSENSITIVE
        ),
        // European/Indian format: DD-MM-YYYY or DD/MM/YYYY or DD.MM.YYYY
        Pattern.compile(
            """(\d{2}[-/.]\d{2}[-/.]\d{4})""" +
            """(?:\s+(\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AP]M)?))?\s*(?:IST)?""",
            Pattern.CASE_INSENSITIVE
        ),
        // Text format with abbreviated month: DD-MMM-YYYY or DD/MMM/YYYY or DD MMM YYYY
        Pattern.compile(
            """(\d{2}[-/\s](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[-/\s]\d{4})""" +
            """(?:\s+(\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AP]M)?))?\s*(?:IST)?""",
            Pattern.CASE_INSENSITIVE
        ),
        // Text format with full month: DD-MMMMMMMM-YYYY or DD MMMMMMMM YYYY
        Pattern.compile(
            """(\d{2}[-/\s](?:January|February|March|April|May|June|July|August|September|October|November|December)[-/\s]\d{4})""" +
            """(?:\s+(\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AP]M)?))?\s*(?:IST)?""",
            Pattern.CASE_INSENSITIVE
        ),
        // Short format: DD-MM-YY or DD/MM/YY
        Pattern.compile(
            """(\d{2}[-/]\d{2}[-/]\d{2})""" +
            """(?:\s+(\d{1,2}:\d{2}(?::\d{2})?\s*(?:[AP]M)?))?\s*(?:IST)?""",
            Pattern.CASE_INSENSITIVE
        )
    )


    /**
     * Parses a bank/wallet SMS [message] and returns a JSON string with keys:
     * `amount` (Double), `currency`, `type`, `entity`, `date_time`, `raw_sms`.
     *
     * JSON is built with a pure Kotlin helper so it works on both the Android
     * runtime and the host JVM test environment (no org.json stub required).
     *
     * @param message The full raw SMS body text.
     * @return A compact JSON string, or a JSON error object on failure.
     */
    fun parseSms(message: String): String {
        return try {
            buildJsonObject(
                "amount"    to extractAmount(message),
                "currency"  to extractCurrency(message),
                "type"      to extractType(message),
                "entity"    to extractEntity(message),
                "date_time" to extractDateTime(message),
                "raw_sms"   to message
            )
        } catch (e: Exception) {
            buildJsonObject(
                "error"   to (e.message ?: "unknown error"),
                "raw_sms" to message
            )
        }
    }

    /**
     * Identifies if an SMS is likely a bank transaction message.
     *
     * Criteria:
     * 1. Contains a recognized currency (INR, AED, Rs).
     * 2. Contains a transaction type (credit, debit, etc.).
     * 3. Does NOT contain high-confidence promotional keywords.
     * 4. Alphanumeric sender ID (optional check, as some banks use numbers in some regions).
     *
     * @param message The SMS body.
     * @param sender The sender ID (e.g., "AD-HDFCBK").
     * @return True if it's a valid bank transaction, false otherwise.
     */
    fun isBankTransaction(message: String, sender: String? = null): Boolean {
        if (message.isBlank()) return false

        // 1. Must have a currency and an amount
        val hasAmount = AMOUNT_PATTERN.matcher(message).find()
        if (!hasAmount) return false

        // 2. Must have a transaction type (Credit/Debit)
        val isCredit = CREDIT_PATTERN.matcher(message).find()
        val isDebit = DEBIT_PATTERN.matcher(message).find()
        if (!isCredit && !isDebit) return false

        // 3. Exclude promotional content
        val promoKeywords = listOf(
            "offer", "discount", "cashback", "apply now", "limited time",
            "congratulations", "win", "gift", "voucher", "click here",
            "subscription", "upgrade", "pre-approved", "loan", "emi starting"
        )
        val lowerMsg = message.lowercase()
        for (keyword in promoKeywords) {
            if (lowerMsg.contains(keyword)) {
                // Special case: "cashback" might be in a credit message, but usually "cashback credited"
                // is still a transaction. However, "Get 10% cashback" is promo.
                // For now, if it's a very clear promo, skip.
                if (keyword == "cashback" && isCredit) continue // Allow "Cashback credited"
                if (keyword == "loan" && (isCredit || isDebit)) continue // Allow "Loan EMI debited"
                
                return false
            }
        }

        // 4. Sender ID check (Banks usually have alphanumeric IDs like XX-XXXXXX)
        // If sender is a standard 10-digit mobile number, it's likely a personal message.
        sender?.let {
            if (it.matches(Regex("""^\+?\d{10,12}$"""))) {
                // Likely a personal phone number, not a bank header
                return false
            }
        }

        return true
    }

    /** Returns the numeric amount as a Double, or 0.0 if not found. */
    private fun extractAmount(msg: String): Double {
        val matcher = AMOUNT_PATTERN.matcher(msg)
        return if (matcher.find()) {
            matcher.group(1)
                ?.replace(",", "")   // remove thousand-separators
                ?.toDoubleOrNull() ?: 0.0
        } else 0.0
    }

    /**
     * Returns the canonical currency code ("INR" or "AED").
     * "Rs" / "Rs." are normalised to "INR".
     */
    private fun extractCurrency(msg: String): String {
        val matcher = CURRENCY_PATTERN.matcher(msg)
        return if (matcher.find()) {
            val raw = matcher.group(1) ?: return "UNKNOWN"
            when {
                raw.startsWith("Rs", ignoreCase = true) -> "INR"
                else -> raw.uppercase()
            }
        } else "UNKNOWN"
    }

    /** Returns "CREDIT", "DEBIT", or "UNKNOWN". */
    private fun extractType(msg: String): String = when {
        CREDIT_PATTERN.matcher(msg).find() -> "CREDIT"
        DEBIT_PATTERN.matcher(msg).find()  -> "DEBIT"
        else                               -> "UNKNOWN"
    }

    /**
     * Extracts the most meaningful entity (merchant / person) name.
     * Tries each preposition keyword in priority order: at > to > by > from.
     */
    private fun extractEntity(msg: String): String {
        val priorityKeywords = listOf("at", "to", "by", "from")
        for (keyword in priorityKeywords) {
            val p = Pattern.compile(
                """(?i)\b$keyword\s+([A-Za-z0-9 &'.\-]+?)(?=[.;,\n]|${'$'})"""
            )
            val m = p.matcher(msg)
            if (m.find()) {
                var entity = m.group(1)?.trim() ?: continue
                for (noise in NOISE_PHRASES) {
                    entity = noise.replace(entity, "").trim()
                }
                if (entity.isNotBlank()) return entity
            }
        }
        return "UNKNOWN"
    }

    /**
     * Extracts date-time from SMS in any IST format and normalizes to DD-MM-YYYY HH:MM:SS IST.
     *
     * Handles:
     * - ISO formats: 2025-11-09, 2025/11/09
     * - European formats: 09-11-2025, 09/11/2025, 09.11.2025
     * - Text formats: 09-Nov-2025, 09 November 2025
     * - Short formats: 09/11/25 (assumes DD/MM/YY)
     * - Time formats: HH:MM:SS, HH:MM, H:MM AM/PM, HH:MM:SS IST
     *
     * Output format: DD-MM-YYYY HH:MM:SS IST (e.g., 09-11-2025 07:01:25 IST)
     */
    private fun extractDateTime(msg: String): String {
        // Try each pattern in order
        for (pattern in DATE_PATTERNS) {
            val matcher = pattern.matcher(msg)
            if (matcher.find()) {
                val dateStr = matcher.group(1) ?: continue
                val timeStr = matcher.group(2)?.trim()

                // Normalize the date part
                val normalizedDate = normalizeDate(dateStr)
                if (normalizedDate == "UNKNOWN") continue

                // Normalize the time part if present
                val normalizedTime = if (!timeStr.isNullOrBlank()) {
                    normalizeTime(timeStr)
                } else {
                    "00:00:00"  // Default time if not provided
                }

                return if (normalizedTime != "UNKNOWN") {
                    "$normalizedDate $normalizedTime IST"
                } else {
                    normalizedDate
                }
            }
        }
        return "UNKNOWN"
    }

    /**
     * Normalizes various date formats to DD-MM-YYYY.
     *
     * Handles:
     * - YYYY-MM-DD → DD-MM-YYYY
     * - DD-MM-YYYY → DD-MM-YYYY (as-is)
     * - DD-MMM-YYYY → DD-MM-YYYY
     * - DD-MMMMMMMM-YYYY → DD-MM-YYYY
     * - DD-MM-YY → DD-MM-YYYY (adds century: 25 → 2025)
     */
    private fun normalizeDate(dateStr: String): String {
        val trimmed = dateStr.trim()

        // Try ISO format: YYYY-MM-DD or YYYY/MM/DD
        val isoMatch = Pattern.compile("""(\d{4})[-/](\d{2})[-/](\d{2})""").matcher(trimmed)
        if (isoMatch.find()) {
            val year = isoMatch.group(1)!!
            val month = isoMatch.group(2)!!
            val day = isoMatch.group(3)!!
            return "$day-$month-$year"  // Convert to DD-MM-YYYY
        }

        // Try European format: DD-MM-YYYY or DD/MM/YYYY or DD.MM.YYYY (4-digit year)
        val euroMatch = Pattern.compile("""(\d{2})[-/.](\d{2})[-/.](\d{4})""").matcher(trimmed)
        if (euroMatch.find()) {
            val day = euroMatch.group(1)!!
            val month = euroMatch.group(2)!!
            val year = euroMatch.group(3)!!
            return "$day-$month-$year"
        }

        // Try with month name (abbreviated): DD-MMM-YYYY or DD/MMM/YYYY or DD MMM YYYY
        val monthAbbrevMatch = Pattern.compile(
            """(\d{2})[-/\s]([A-Za-z]{3})[-/\s](\d{4})""",
            Pattern.CASE_INSENSITIVE
        ).matcher(trimmed)
        if (monthAbbrevMatch.find()) {
            val day = monthAbbrevMatch.group(1)!!
            val monthName = monthAbbrevMatch.group(2)!!.lowercase()
            val year = monthAbbrevMatch.group(3)!!
            val monthNum = getMonthNumber(monthName)
            if (monthNum != "00") {
                return "$day-$monthNum-$year"
            }
        }

        // Try with month name (full): DD-MMMMMMMM-YYYY or DD MMMMMMMM YYYY
        val monthFullMatch = Pattern.compile(
            """(\d{2})[-/\s]([A-Za-z]{4,})[-/\s](\d{4})""",
            Pattern.CASE_INSENSITIVE
        ).matcher(trimmed)
        if (monthFullMatch.find()) {
            val day = monthFullMatch.group(1)!!
            val monthName = monthFullMatch.group(2)!!.lowercase()
            val year = monthFullMatch.group(3)!!
            val monthNum = getMonthNumber(monthName)
            if (monthNum != "00") {
                return "$day-$monthNum-$year"
            }
        }

        // Try short format: DD-MM-YY or DD/MM/YY (2-digit year, assumes 20XX)
        val shortMatch = Pattern.compile("""(\d{2})[-/](\d{2})[-/](\d{2})$""").matcher(trimmed)
        if (shortMatch.find()) {
            val day = shortMatch.group(1)!!
            val month = shortMatch.group(2)!!
            val year = shortMatch.group(3)!!.toInt()
            // Assume 2000s for years 00-99
            val fullYear = if (year <= 99) 2000 + year else 1900 + year
            return "$day-$month-$fullYear"
        }

        return "UNKNOWN"
    }

    /**
     * Converts month name (abbreviated or full) to month number (01-12).
     * Handles both English full names and abbreviations.
     */
    private fun getMonthNumber(monthName: String): String {
        return when (monthName.lowercase()) {
            "jan", "january"   -> "01"
            "feb", "february"  -> "02"
            "mar", "march"     -> "03"
            "apr", "april"     -> "04"
            "may"              -> "05"
            "jun", "june"      -> "06"
            "jul", "july"      -> "07"
            "aug", "august"    -> "08"
            "sep", "september" -> "09"
            "oct", "october"   -> "10"
            "nov", "november"  -> "11"
            "dec", "december"  -> "12"
            else               -> "00"
        }
    }

    /**
     * Normalizes time to HH:MM:SS format (24-hour).
     *
     * Handles:
     * - 12-hour: 7:01 AM, 07:01 AM, 7:01:25 PM, 07:01:25 PM
     * - 24-hour: 07:01, 07:01:25, 7:01, 7:01:25
     * - IST suffix: 07:01:25 IST, 07:01 PM IST
     *
     * Output: HH:MM:SS (e.g., 19:01:25 for 7:01:25 PM)
     */
    private fun normalizeTime(time: String): String {
        val trimmed = time.trim().uppercase()

        // Pattern for 12-hour format: H(H):MM(:SS) (AM|PM)
        val pattern12hr = Pattern.compile(
            """(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(?:AM|PM)""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher12 = pattern12hr.matcher(trimmed)
        if (matcher12.find()) {
            var hour = matcher12.group(1)!!.toInt()
            val minute = matcher12.group(2)!!
            val second = matcher12.group(3) ?: "00"
            val ampm = if (trimmed.contains("PM")) "PM" else "AM"

            // Convert 12-hour to 24-hour format
            if (ampm == "PM" && hour != 12) {
                hour += 12
            } else if (ampm == "AM" && hour == 12) {
                hour = 0
            }

            return "%02d:%s:%s".format(hour, minute, second)
        }

        // Pattern for 24-hour format: HH:MM(:SS)
        val pattern24hr = Pattern.compile(
            """(\d{1,2}):(\d{2})(?::(\d{2}))?"""
        )
        val matcher24 = pattern24hr.matcher(trimmed)
        if (matcher24.find()) {
            val hour = matcher24.group(1)!!.toInt()
            val minute = matcher24.group(2)!!
            val second = matcher24.group(3) ?: "00"

            if (hour < 24 && hour >= 0) {
                return "%02d:%s:%s".format(hour, minute, second)
            }
        }

        return "UNKNOWN"
    }

    // ────────────────────────────────────────────────────────────────────────
    // Pure-Kotlin JSON builder (no Android SDK dependency)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Builds a flat JSON object string from [pairs] of key→value.
     * Supported value types: String, Double, Float, Int, Long, Boolean, null.
     */
    private fun buildJsonObject(vararg pairs: Pair<String, Any?>): String {
        val entries = pairs.joinToString(separator = ",") { (key, value) ->
            val jsonKey   = "\"${key.escapeJson()}\""
            val jsonValue = when (value) {
                null           -> "null"
                is Boolean     -> value.toString()
                is Number      -> value.toString()          // Double/Int/Long stay numeric
                is String      -> "\"${value.escapeJson()}\""
                else           -> "\"${value.toString().escapeJson()}\""
            }
            "$jsonKey:$jsonValue"
        }
        return "{$entries}"
    }

    /** Escapes special characters inside a JSON string value. */
    private fun String.escapeJson(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
