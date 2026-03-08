package com.tech.spendwise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JUnit 5 unit tests for [TransactionExtractor].
 * Run on the host JVM — no Android device/emulator required.
 *
 * Run with:
 *   ./gradlew :app:testDebugUnitTest
 *
 * The parsed JSON is a flat object, e.g.:
 *   {"amount":800.0,"currency":"INR","type":"CREDIT",...}
 * We use a simple key-lookup helper to avoid any Android-SDK JSON dependency.
 */
class TransactionExtractorTest {

    // ── Helper ───────────────────────────────────────────────────────────────

    /**
     * Parses the [sms] string and returns a map of key → raw-value-string.
     *
     * Works with the flat JSON produced by [TransactionExtractor.parseSms]:
     *   {"amount":800.0,"currency":"INR","type":"CREDIT","entity":"...","date_time":"...","raw_sms":"..."}
     *
     * Strings are returned WITHOUT surrounding quotes.
     * Numbers are returned as-is (e.g. "800.0").
     */
    private fun parseToMap(sms: String): Map<String, String> {
        val json = TransactionExtractor.parseSms(sms)
        val result = mutableMapOf<String, String>()

        // Match every "key": value pair where value is either
        //   • a quoted string  → group 2 captures inner text
        //   • an unquoted value (number / boolean / null) → group 3
        val pattern = Regex(""""(\w+)"\s*:\s*(?:"((?:[^"\\]|\\.)*)"|([^,}\s]+))""")
        for (match in pattern.findAll(json)) {
            val key   = match.groupValues[1]
            val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2]
                        else match.groupValues[3]
            result[key] = value
        }
        return result
    }

    // ── Test 1: BOB UPI Credit ───────────────────────────────────────────────

    /**
     * SMS:
     *   "Dear BOB UPI User: Your account is credited with INR 800.00
     *    on 2025-11-09 07:01:25 PM by UPI Ref No 98765; AvlBal: Rs17987.32"
     *
     * Expected:
     *   amount   = 800.0
     *   currency = "INR"
     *   type     = "CREDIT"
     *   date_time contains "2025-11-09" and "19:01:25" (PM → 24 h)
     *   raw_sms  = original string
     */
    @Test
    fun `BOB UPI credit SMS is parsed correctly`() {
        val sms = "Dear BOB UPI User: Your account is credited with INR 800.00 " +
                  "on 2025-11-09 07:01:25 PM by UPI Ref No 98765; AvlBal: Rs17987.32"

        val map = parseToMap(sms)

        assertEquals(800.0,   map["amount"]?.toDoubleOrNull(),    "amount mismatch")
        assertEquals("INR",   map["currency"],                    "currency mismatch")
        assertEquals("CREDIT", map["type"],                       "type mismatch")
        assertEquals(17987.32, map["balance"]?.toDoubleOrNull(),   "balance mismatch")

        val dateTime = map["date_time"] ?: ""
        assertTrue(dateTime.contains("09-11-2025"),
            "date_time should contain '09-11-2025'. Actual: $dateTime")
        assertTrue(dateTime.contains("19:01:25"),
            "date_time should contain '19:01:25' (PM→24 h). Actual: $dateTime")

        // raw_sms must survive the JSON round-trip intact
        val rawSms = map["raw_sms"] ?: ""
        assertTrue(rawSms.contains("BOB UPI"),
            "raw_sms should contain original text. Actual: $rawSms")
    }

    // ── Test 2: AED POS Transaction ──────────────────────────────────────────

    /**
     * SMS:
     *   "Trx. of AED 451.20 on your card ending **0121
     *    at LuluHypermarket KHALID in Abu Dhabi, UAE is Approved."
     *
     * Expected:
     *   amount   = 451.20
     *   currency = "AED"
     *   entity   contains "LuluHypermarket"
     */
    @Test
    fun `AED POS transaction SMS is parsed correctly`() {
        val sms = "Trx. of AED 451.20 on your card ending **0121 " +
                  "at LuluHypermarket KHALID in Abu Dhabi, UAE is Approved."

        val map = parseToMap(sms)

        assertEquals(451.20, map["amount"]?.toDoubleOrNull(), "amount mismatch")
        assertEquals("AED",  map["currency"],                 "currency mismatch")

        val entity = map["entity"] ?: ""
        assertTrue(entity.contains("LuluHypermarket", ignoreCase = true),
            "entity should contain 'LuluHypermarket'. Actual: $entity")

        val rawSms = map["raw_sms"] ?: ""
        assertTrue(rawSms.contains("AED 451.20"),
            "raw_sms should contain original text. Actual: $rawSms")
    }

    // ── Test 3: AED Online Purchase ──────────────────────────────────────────

    /**
     * SMS:
     *   "Amount of AED 1 Online Purchase at ADNOC LPG."
     *
     * Expected:
     *   amount   = 1.0
     *   currency = "AED"
     *   type     = "DEBIT"   (keyword: "purchase")
     *   entity   contains "ADNOC"
     */
    @Test
    fun `AED online purchase SMS is parsed correctly`() {
        val sms = "Amount of AED 1 Online Purchase at ADNOC LPG."

        val map = parseToMap(sms)

        assertEquals(1.0,    map["amount"]?.toDoubleOrNull(), "amount mismatch")
        assertEquals("AED",  map["currency"],                 "currency mismatch")
        assertEquals("DEBIT", map["type"],                    "type mismatch")

        val entity = map["entity"] ?: ""
        assertTrue(entity.contains("ADNOC", ignoreCase = true),
            "entity should contain 'ADNOC'. Actual: $entity")

        val rawSms = map["raw_sms"] ?: ""
        assertTrue(rawSms.contains("ADNOC LPG"),
            "raw_sms should contain original text. Actual: $rawSms")
    }

    // ── Test 4: Is bank transaction filtering ───────────────────────────────

    @Test
    fun `isBankTransaction correctly filters messages`() {
        val extractor = TransactionExtractor

        // 1. Valid Bank Credit
        val bankCredit = "Dear BOB UPI User: Your account is credited with INR 800.00 on 2025-11-09."
        assertTrue(extractor.isBankTransaction(bankCredit, "AD-BOB-UPI"), "Should accept bank credit")

        // 2. Valid Bank Debit
        val bankDebit = "Amount of AED 451.20 debited from your card ending **0121 at Lulu."
        assertTrue(extractor.isBankTransaction(bankDebit, "AD-HDFCBK"), "Should accept bank debit")

        // 3. Promotional message (has amount and type, but also promo keywords)
        val promoMsg = "Congratulations! You won a gift voucher worth Rs 5000. Click here to claim."
        assertTrue(!extractor.isBankTransaction(promoMsg, "AD-PROMO"), "Should ignore promotional message")

        // 4. Personal message from phone number
        val personalMsg = "I sent you INR 500 via UPI. Please check."
        assertTrue(!extractor.isBankTransaction(personalMsg, "+919876543210"), "Should ignore personal message from number")

        // 5. Message with no amount
        val noAmount = "Your account has been activated."
        assertTrue(!extractor.isBankTransaction(noAmount, "AD-BOB-UPI"), "Should ignore message without amount")

        // 6. Message with no type
        val noType = "Your balance is INR 10,000.00"
        assertTrue(!extractor.isBankTransaction(noType, "AD-BOB-UPI"), "Should ignore message without credit/debit keyword")

        // 7. Special case: Loan EMI (contains 'loan' and 'debited')
        val loanEmi = "Your Loan EMI of Rs. 15,000 is debited from your account."
        assertTrue(extractor.isBankTransaction(loanEmi, "AD-BANK"), "Should accept Loan EMI debit")
    }

    @Test
    fun `Balance is extracted correctly from various formats`() {
        val extractor = TransactionExtractor
        
        // 1. AvlBal suffix
        val sms1 = "Account XX123 credited for INR 100. AvlBal: Rs.17987.32"
        assertEquals(17987.32, parseToMap(sms1)["balance"]?.toDoubleOrNull())

        // 2. Bal: format
        val sms2 = "Your account XX567 is debited for Rs. 500. Bal: INR 100.00"
        assertEquals(100.0, parseToMap(sms2)["balance"]?.toDoubleOrNull())

        // 3. Available Balance text
        val sms3 = "Dear Customer, available balance is INR 500.25 after transaction."
        assertEquals(500.25, parseToMap(sms3)["balance"]?.toDoubleOrNull())
        
        // 4. No balance in message
        val sms4 = "OTP for your transaction is 123456."
        assertEquals("null", parseToMap(sms4)["balance"])
    }
}
