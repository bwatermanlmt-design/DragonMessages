package org.fossify.messages.filtering

/** Classifies a sender address and offers the small matching helpers rules need. */
object SenderClassifier {
    /** Short codes: at most [MAX_SHORT_CODE_DIGITS] digits, e.g. bank/2FA senders. */
    const val SHORT_CODE = "short_code"

    /** Ordinary phone numbers. */
    const val LONG_NUMBER = "long_number"

    /** Alphanumeric sender IDs, e.g. "BANK-ALERT". */
    const val ALPHANUMERIC = "alphanumeric"

    const val MAX_SHORT_CODE_DIGITS = 6

    private val LINK_REGEX = Regex(
        """(?i)\b(?:https?://|www\.)\S+|\b[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.(?:com|net|org|io|co|me|tv|us|info|biz|xyz|top|click|link)(?:/\S*)?"""
    )

    fun classify(sender: String): String {
        val trimmed = sender.trim()
        if (trimmed.any { it.isLetter() }) {
            return ALPHANUMERIC
        }

        val digits = trimmed.filter { it.isDigit() }
        val isPlainNumber = trimmed.all { it.isDigit() || it == '+' || it == ' ' }
        return if (isPlainNumber && digits.length in 1..MAX_SHORT_CODE_DIGITS) {
            SHORT_CODE
        } else {
            LONG_NUMBER
        }
    }

    fun containsLink(body: String): Boolean {
        return LINK_REGEX.containsMatchIn(body)
    }
}

/** Phone-number comparisons shared by the rule conditions. */
object PhoneNumbers {

    fun digitsOnly(value: String): String = value.filter { it.isDigit() }

    /**
     * Number equality tolerant of formatting and country codes: two numbers
     * with at least 10 digits compare by their last 10 digits.
     */
    fun equal(first: String, second: String): Boolean {
        val firstDigits = digitsOnly(first)
        val secondDigits = digitsOnly(second)
        if (firstDigits.isEmpty() || secondDigits.isEmpty()) {
            return first.equals(second, ignoreCase = true)
        }

        return if (firstDigits.length >= 10 && secondDigits.length >= 10) {
            firstDigits.takeLast(10) == secondDigits.takeLast(10)
        } else {
            firstDigits == secondDigits
        }
    }

    /**
     * Wildcard match where '*' stands for any run of characters (the same
     * convention as the blocked-number patterns in Fossify Commons). Patterns
     * made only of digits and '*' are matched against the sender's digits.
     */
    fun matchesPattern(pattern: String, sender: String): Boolean {
        val digitsAndStarsOnly = pattern.all { it.isDigit() || it == '*' }
        val target = if (digitsAndStarsOnly) digitsOnly(sender) else sender
        val regex = StringBuilder("^")
        for (char in pattern) {
            regex.append(if (char == '*') ".*" else Regex.escape(char.toString()))
        }
        regex.append("$")
        return Regex(regex.toString(), RegexOption.IGNORE_CASE).matches(target)
    }
}
