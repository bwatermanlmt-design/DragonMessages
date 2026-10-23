package org.fossify.messages.filtering

/**
 * Number matching for the permanent blocked-numbers record (Ben's
 * requirement, 2026-10-22): blocking belongs to the number, not to a
 * thread, so the same sender has to be recognised across the format
 * variants it can arrive in — "+1 480-555-0137" when blocked from a
 * conversation, "4805550137" on a later message, and so on. Pure string
 * work, so the matching rules are unit-testable.
 */
object BlockedNumbers {

    /** Whether [sender] matches any entry of the blocked record. */
    fun isBlocked(sender: String, blockedNumbers: Collection<String>): Boolean {
        return blockedNumbers.any { numbersMatch(sender, it) }
    }

    fun numbersMatch(a: String, b: String): Boolean {
        val first = a.trim()
        val second = b.trim()
        if (first.isEmpty() || second.isEmpty()) {
            return false
        }

        val firstDigits = first.filter { it.isDigit() }
        val secondDigits = second.filter { it.isDigit() }

        // Alphanumeric sender IDs ("USPS", "Cox") carry no digits to
        // normalise: they match as whole strings, case-insensitively.
        if (firstDigits.isEmpty() || secondDigits.isEmpty()) {
            return first.equals(second, ignoreCase = true)
        }

        if (firstDigits == secondDigits) {
            return true
        }

        // Full national numbers: the last ten digits are the subscriber
        // number, whatever country/trunk prefix either side carries.
        if (firstDigits.length >= 10 && secondDigits.length >= 10) {
            return firstDigits.takeLast(10) == secondDigits.takeLast(10)
        }

        // Shorter numbers (short codes and the like) match only when one
        // side is a genuine suffix of the other and the shorter side is
        // long enough for that to mean something — seven digits is a
        // local number; anything shorter would false-match strangers.
        val shorter = minOf(firstDigits.length, secondDigits.length)
        if (shorter >= 7) {
            return firstDigits.endsWith(secondDigits) || secondDigits.endsWith(firstDigits)
        }

        return false
    }
}
