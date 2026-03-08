package com.tech.spendwise

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [VoiceExpenseParser] verifying all documented example phrases.
 */
class VoiceExpenseParserTest {

    @Test
    fun `spent 250 on lunch`() {
        val result = VoiceExpenseParser.parse("spent 250 on lunch")
        assertEquals(250.0, result.amount, 0.01)
        assertEquals("Food", result.category)
        assertEquals("expense", result.type)
    }

    @Test
    fun `300 groceries`() {
        val result = VoiceExpenseParser.parse("300 groceries")
        assertEquals(300.0, result.amount, 0.01)
        assertEquals("Groceries", result.category)
        assertEquals("expense", result.type)
    }

    @Test
    fun `paid 1200 rent`() {
        val result = VoiceExpenseParser.parse("paid 1200 rent")
        assertEquals(1200.0, result.amount, 0.01)
        assertEquals("Bills", result.category)
        assertEquals("expense", result.type)
    }

    @Test
    fun `got 5000 salary`() {
        val result = VoiceExpenseParser.parse("got 5000 salary")
        assertEquals(5000.0, result.amount, 0.01)
        assertEquals("Income", result.category)
        assertEquals("income", result.type)
    }

    @Test
    fun `uber 350`() {
        val result = VoiceExpenseParser.parse("uber 350")
        assertEquals(350.0, result.amount, 0.01)
        assertEquals("Transport", result.category)
        assertEquals("expense", result.type)
        assertTrue(result.merchant.contains("uber", ignoreCase = true))
    }

    @Test
    fun `received 5000 salary`() {
        val result = VoiceExpenseParser.parse("received 5000 salary")
        assertEquals(5000.0, result.amount, 0.01)
        assertEquals("income", result.type)
    }

    @Test
    fun `swiggy 180`() {
        val result = VoiceExpenseParser.parse("swiggy 180")
        assertEquals(180.0, result.amount, 0.01)
        assertEquals("Food", result.category)
        assertEquals("expense", result.type)
    }

    @Test
    fun `zero amount falls back gracefully`() {
        val result = VoiceExpenseParser.parse("bought something")
        assertEquals(0.0, result.amount, 0.01)
        assertEquals("expense", result.type)
    }
}
