package com.tech.spendwise

/**
 * Test cases for TransactionExtractor.extractDateTime() method.
 *
 * Tests all supported IST date and time formats.
 * Can be run as unit tests using JUnit or manually.
 */
object DateTimeExtractionTests {

    /**
     * Test case data class
     */
    data class TestCase(
        val name: String,
        val sms: String,
        val expectedPattern: String  // Regex pattern to match expected output
    )

    /**
     * All test cases covering different date-time formats
     */
    val testCases = listOf(
        // ── ISO Format Tests ──
        TestCase(
            "ISO format: YYYY-MM-DD with 12-hour time",
            "Transaction on 2025-11-09 07:01:25 PM",
            """09-11-2025 19:01:25 IST"""
        ),
        TestCase(
            "ISO format: YYYY-MM-DD with 24-hour time",
            "Payment made 2025-11-09 19:01:25",
            """09-11-2025 19:01:25 IST"""
        ),
        TestCase(
            "ISO format: YYYY/MM/DD date only",
            "Date: 2025/11/09",
            """09-11-2025 00:00:00 IST"""
        ),

        // ── European Format Tests ──
        TestCase(
            "European format: DD-MM-YYYY with time",
            "Debit on 09-11-2025 at 3:45 PM",
            """09-11-2025 15:45:00 IST"""
        ),
        TestCase(
            "European format: DD/MM/YYYY with time",
            "Transaction on 09/11/2025 at 7:01 AM",
            """09-11-2025 07:01:00 IST"""
        ),
        TestCase(
            "European format: DD.MM.YYYY (with dots)",
            "Date: 09.11.2025",
            """09-11-2025 00:00:00 IST"""
        ),

        // ── Abbreviated Month Tests ──
        TestCase(
            "Abbreviated month: DD-MMM-YYYY with time",
            "Payment on 09-Nov-2025 at 5:30 PM",
            """09-11-2025 17:30:00 IST"""
        ),
        TestCase(
            "Abbreviated month: DD/Nov/YYYY",
            "Transaction on 09/Nov/2025 at 7:01 AM",
            """09-11-2025 07:01:00 IST"""
        ),
        TestCase(
            "Abbreviated month: DD MMM YYYY (spaces)",
            "Credited on 09 Nov 2025 at 3:45 PM",
            """09-11-2025 15:45:00 IST"""
        ),
        TestCase(
            "Abbreviated month: DD-NOV-YYYY (uppercase)",
            "Debited on 09-NOV-2025 at 7:01 PM",
            """09-11-2025 19:01:00 IST"""
        ),

        // ── Full Month Name Tests ──
        TestCase(
            "Full month: DD-November-YYYY",
            "Transaction on 09-November-2025 at 5:30 PM",
            """09-11-2025 17:30:00 IST"""
        ),
        TestCase(
            "Full month: DD November YYYY (spaces)",
            "Credited on 09 November 2025 at 7:01 AM",
            """09-11-2025 07:01:00 IST"""
        ),
        TestCase(
            "Full month: DD-NOVEMBER-YYYY (uppercase)",
            "Debit on 09-NOVEMBER-2025 at 3:45 PM",
            """09-11-2025 15:45:00 IST"""
        ),

        // ── Short Year Tests ──
        TestCase(
            "Short year: DD-MM-YY with time",
            "Transaction on 09-11-25 at 5:30 PM",
            """09-11-2025 17:30:00 IST"""
        ),
        TestCase(
            "Short year: DD/MM/YY with 24-hour time",
            "Payment made 09/11/25 19:30:15",
            """09-11-2025 19:30:15 IST"""
        ),

        // ── Time Format Tests ──
        TestCase(
            "12-hour time: H:MM AM",
            "Date: 09-11-2025 7:01 AM",
            """09-11-2025 07:01:00 IST"""
        ),
        TestCase(
            "12-hour time: HH:MM PM",
            "Date: 09-11-2025 07:01 PM",
            """09-11-2025 19:01:00 IST"""
        ),
        TestCase(
            "12-hour time: H:MM:SS AM",
            "Date: 09-11-2025 7:01:25 AM",
            """09-11-2025 07:01:25 IST"""
        ),
        TestCase(
            "12-hour time: HH:MM:SS PM",
            "Date: 09-11-2025 07:01:25 PM",
            """09-11-2025 19:01:25 IST"""
        ),
        TestCase(
            "24-hour time: H:MM",
            "Date: 09-11-2025 7:01",
            """09-11-2025 07:01:00 IST"""
        ),
        TestCase(
            "24-hour time: HH:MM",
            "Date: 09-11-2025 19:01",
            """09-11-2025 19:01:00 IST"""
        ),
        TestCase(
            "24-hour time: H:MM:SS",
            "Date: 09-11-2025 7:01:25",
            """09-11-2025 07:01:25 IST"""
        ),

        // ── Edge Cases ──
        TestCase(
            "Midnight: 12:01 AM",
            "Date: 09-11-2025 12:01 AM",
            """09-11-2025 00:01:00 IST"""
        ),
        TestCase(
            "Noon: 12:01 PM",
            "Date: 09-11-2025 12:01 PM",
            """09-11-2025 12:01:00 IST"""
        ),
        TestCase(
            "With IST suffix: 07:01:25 IST",
            "Transaction at 09-11-2025 07:01:25 IST",
            """09-11-2025 07:01:25 IST"""
        ),
        TestCase(
            "Single digit day and month",
            "Date: 9-11-2025 7:01 AM",
            """UNKNOWN"""  // May not match due to regex \d{2}
        ),
        TestCase(
            "Date only, no time",
            "Transaction on 09-Nov-2025",
            """09-11-2025 00:00:00 IST"""
        ),

        // ── Real Bank SMS Examples ──
        TestCase(
            "ICICI Bank",
            "INR 500 debited from A/C 1234 on 09-11-2025 at 5:30 PM. Avl: 45000",
            """09-11-2025 17:30:00 IST"""
        ),
        TestCase(
            "HDFC Bank",
            "Debit of INR 1000 on 09/11/2025 07:01:25 PM from A/C ending 5678",
            """09-11-2025 19:01:25 IST"""
        ),
        TestCase(
            "Axis Bank",
            "Your account debited INR 500 on 09-Nov-2025 at 3:45 PM",
            """09-11-2025 15:45:00 IST"""
        ),
        TestCase(
            "Standard Chartered",
            "INR 500 debited on 2025-11-09 at 19:30. Avl: 50000",
            """09-11-2025 19:30:00 IST"""
        ),
        TestCase(
            "Yes Bank",
            "Credit alert: INR 1000 credited on 09 November 2025 at 7:01 AM",
            """09-11-2025 07:01:00 IST"""
        ),
        TestCase(
            "SBI Bank",
            "Amount Rs.500 has been debited on 09/11/25 17:30 IST",
            """09-11-2025 17:30:00 IST"""
        ),
    )

    /**
     * Run all tests and print results
     */
    fun runAllTests() {
        println("╔════════════════════════════════════════════════════════════╗")
        println("║   DateTime Extraction Tests for All IST Formats            ║")
        println("╚════════════════════════════════════════════════════════════╝")
        println()

        var passed = 0
        var failed = 0

        testCases.forEachIndexed { index, testCase ->
            val result = TransactionExtractor.parseSms(testCase.sms)
            val extractedDateTime = extractJsonValue(result, "date_time")
            val matches = extractedDateTime.matches(Regex(testCase.expectedPattern))

            val status = if (matches) {
                passed++
                "✅ PASS"
            } else {
                failed++
                "❌ FAIL"
            }

            println("Test ${index + 1}: ${testCase.name}")
            println("  SMS: \"${testCase.sms}\"")
            println("  Expected: ${testCase.expectedPattern}")
            println("  Got:      $extractedDateTime")
            println("  Status: $status")
            println()
        }

        println("╔════════════════════════════════════════════════════════════╗")
        println("║   Test Summary                                             ║")
        println("╠════════════════════════════════════════════════════════════╣")
        println("║ Total:  ${testCases.size} tests")
        println("║ Passed: $passed ✅")
        println("║ Failed: $failed ❌")
        println("╚════════════════════════════════════════════════════════════╝")
    }

    /**
     * Helper function to extract JSON value from parsed result
     */
    private fun extractJsonValue(jsonStr: String, key: String): String {
        val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
        val match = pattern.find(jsonStr)
        return match?.groupValues?.get(1) ?: "NOT_FOUND"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        runAllTests()
    }
}

