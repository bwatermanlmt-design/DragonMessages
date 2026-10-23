package org.fossify.messages.filtering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permanent blocked record matches a sender across the format variants
 * it can arrive in — and nothing else. A miss would let a blocked number
 * back in; a false match would silence a stranger.
 */
class BlockedNumbersTest {

    @Test
    fun `identical numbers match`() {
        assertTrue(BlockedNumbers.numbersMatch("+14805550137", "+14805550137"))
        assertTrue(BlockedNumbers.numbersMatch("34291", "34291"))
    }

    @Test
    fun `formatting and country prefix variants match`() {
        assertTrue(BlockedNumbers.numbersMatch("+1 480-555-0137", "4805550137"))
        assertTrue(BlockedNumbers.numbersMatch("+14805550137", "(480) 555-0137"))
        assertTrue(BlockedNumbers.numbersMatch("1-480-555-0137", "+1 (480) 555-0137"))
    }

    @Test
    fun `seven digit local number matches as a suffix`() {
        assertTrue(BlockedNumbers.numbersMatch("5550137", "+1 480-555-0137"))
    }

    @Test
    fun `different subscribers do not match`() {
        assertFalse(BlockedNumbers.numbersMatch("+14805550137", "+14805550138"))
        assertFalse(BlockedNumbers.numbersMatch("+14805550137", "+16025550137"))
        assertFalse(BlockedNumbers.numbersMatch("40404", "34291"))
    }

    @Test
    fun `short code does not match a long number merely sharing its digits`() {
        assertFalse(BlockedNumbers.numbersMatch("34291", "+1 (480) 342-9100"))
        assertFalse(BlockedNumbers.numbersMatch("50137", "+14805550137"))
    }

    @Test
    fun `alphanumeric sender ids match as whole strings ignoring case`() {
        assertTrue(BlockedNumbers.numbersMatch("USPS", "usps"))
        assertFalse(BlockedNumbers.numbersMatch("USPS", "UPS"))
        assertFalse(BlockedNumbers.numbersMatch("USPS", "34291"))
    }

    @Test
    fun `blank never matches`() {
        assertFalse(BlockedNumbers.numbersMatch("", "+14805550137"))
        assertFalse(BlockedNumbers.numbersMatch("+14805550137", "  "))
        assertFalse(BlockedNumbers.numbersMatch("", ""))
    }

    @Test
    fun `isBlocked scans the record`() {
        val record = listOf("+14805550137", "34291", "USPS")
        assertTrue(BlockedNumbers.isBlocked("480-555-0137", record))
        assertTrue(BlockedNumbers.isBlocked("34291", record))
        assertTrue(BlockedNumbers.isBlocked("usps", record))
        assertFalse(BlockedNumbers.isBlocked("+14805550138", record))
        assertFalse(BlockedNumbers.isBlocked("+14805550137", emptyList()))
    }
}
