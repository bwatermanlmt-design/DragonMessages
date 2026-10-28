package org.fossify.messages.filtering

import org.fossify.messages.models.FilterAuditEntry
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-moved rule: a stale rule name is never reported for a hand-moved thread. */
class FilingReasonTest {

    private fun entry(
        ruleName: String = "Test keyword to Spam",
        mailbox: String = Mailbox.SPAM,
        timestamp: Long = 1_700_000_000_000L
    ) = FilterAuditEntry(
        timestamp = timestamp,
        sender = "+14805550137",
        ruleName = ruleName,
        mailbox = mailbox,
        channel = FilterAuditEntry.CHANNEL_SMS
    )

    @Test
    fun `rule filing reports the rule, destination, and time`() {
        val reason = FilingReason.reason(
            manuallyFiled = false,
            currentMailbox = Mailbox.SPAM,
            entry = entry()
        )
        assertTrue(reason is FilingReason.Reason.Filed)
        reason as FilingReason.Reason.Filed
        assertEquals("Test keyword to Spam", reason.ruleName)
        assertEquals(Mailbox.SPAM, reason.mailbox)
        assertEquals(1_700_000_000_000L, reason.timestamp)
    }

    @Test
    fun `hand-moved thread with a stale audit entry reports the hand, not the rule`() {
        val reason = FilingReason.reason(
            manuallyFiled = true,
            currentMailbox = Mailbox.SPAM,
            entry = entry(mailbox = Mailbox.QUARANTINE)
        )
        assertEquals(FilingReason.Reason.HandMoved, reason)
    }

    @Test
    fun `hand-moved thread with no audit entry reports the hand`() {
        val reason = FilingReason.reason(
            manuallyFiled = true,
            currentMailbox = Mailbox.SPAM,
            entry = null
        )
        assertEquals(FilingReason.Reason.HandMoved, reason)
    }

    @Test
    fun `hand-moved thread whose mailbox still matches the audit entry reports the rule`() {
        // The rule only claims the hand when the mailboxes differ.
        val reason = FilingReason.reason(
            manuallyFiled = true,
            currentMailbox = Mailbox.SPAM,
            entry = entry(mailbox = Mailbox.SPAM)
        )
        assertTrue(reason is FilingReason.Reason.Filed)
    }

    @Test
    fun `no audit entry and no hand filing reports no record`() {
        val reason = FilingReason.reason(
            manuallyFiled = false,
            currentMailbox = Mailbox.SPAM,
            entry = null
        )
        assertEquals(FilingReason.Reason.NoRecord, reason)
    }

    @Test
    fun `group marker suffix is stripped from the rule name`() {
        val reason = FilingReason.reason(
            manuallyFiled = false,
            currentMailbox = Mailbox.SPAM,
            entry = entry(ruleName = "Test keyword to Spam" + FilterAuditEntry.RETRO_GROUP_SUFFIX)
        )
        assertTrue(reason is FilingReason.Reason.Filed)
        reason as FilingReason.Reason.Filed
        assertEquals("Test keyword to Spam", reason.ruleName)
    }

    @Test
    fun `precedence-kept suffix is stripped from the rule name`() {
        val reason = FilingReason.reason(
            manuallyFiled = false,
            currentMailbox = Mailbox.SPAM,
            entry = entry(ruleName = "Test keyword to Spam" + FilterAuditEntry.PRECEDENCE_KEPT_SUFFIX)
        )
        assertTrue(reason is FilingReason.Reason.Filed)
        reason as FilingReason.Reason.Filed
        assertEquals("Test keyword to Spam", reason.ruleName)
    }
}
