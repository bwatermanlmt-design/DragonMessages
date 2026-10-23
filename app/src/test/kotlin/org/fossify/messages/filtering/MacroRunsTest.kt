package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroRunsTest {

    private val familyFolder = Mailbox.customMailboxId("folder-family")

    private fun macro(
        senderContains: String? = null,
        keyword: String? = null,
        senderClass: String? = null,
        senderInContacts: Boolean? = null,
        containsLink: Boolean? = null,
        mailbox: String = familyFolder
    ) = FilterRule(
        name = "Macro",
        priority = 15,
        senderContains = senderContains,
        keyword = keyword,
        senderClass = senderClass,
        senderInContacts = senderInContacts,
        containsLink = containsLink,
        mailbox = mailbox,
        isMacro = true
    )

    private fun candidate(
        threadId: Long = 1,
        sender: String = "+14805550137",
        senderName: String = "",
        body: String = "hello there",
        inContacts: Boolean = false,
        currentMailbox: String = Mailbox.QUARANTINE,
        manuallyFiled: Boolean = false,
        senderBlocked: Boolean = false,
        isGroup: Boolean = false
    ) = MacroCandidate(
        threadId = threadId,
        sender = sender,
        senderName = senderName,
        body = body,
        senderInContacts = inContacts,
        currentMailbox = currentMailbox,
        manuallyFiled = manuallyFiled,
        senderBlocked = senderBlocked,
        isGroupConversation = isGroup
    )

    // region each criterion on its own

    @Test
    fun `sender contains matches a number substring`() {
        val m = macro(senderContains = "555")
        assertTrue(MacroRuns.matches(m, candidate(sender = "+14805550137")))
        assertFalse(MacroRuns.matches(m, candidate(sender = "+14806660137")))
    }

    @Test
    fun `sender contains matches the display name`() {
        val m = macro(senderContains = "landlord")
        assertTrue(MacroRuns.matches(m, candidate(senderName = "The Landlord")))
        assertFalse(MacroRuns.matches(m, candidate(senderName = "The Tenant")))
    }

    @Test
    fun `sender class criterion`() {
        val m = macro(senderClass = SenderClassifier.SHORT_CODE)
        assertTrue(MacroRuns.matches(m, candidate(sender = "40404")))
        assertFalse(MacroRuns.matches(m, candidate(sender = "+14805550137")))
    }

    @Test
    fun `keyword criterion reads the latest message body`() {
        val m = macro(keyword = "invoice")
        assertTrue(MacroRuns.matches(m, candidate(body = "Your INVOICE is ready")))
        assertFalse(MacroRuns.matches(m, candidate(body = "Your receipt is ready")))
    }

    @Test
    fun `contains link criterion`() {
        val m = macro(containsLink = true)
        assertTrue(MacroRuns.matches(m, candidate(body = "see https://example.com/x")))
        assertFalse(MacroRuns.matches(m, candidate(body = "see the attached note")))
    }

    // endregion

    // region known and unknown senders, as Ben specified

    @Test
    fun `in-contacts yes selects contacts only`() {
        val m = macro(senderInContacts = true)
        assertTrue(MacroRuns.matches(m, candidate(inContacts = true)))
        assertFalse(MacroRuns.matches(m, candidate(inContacts = false)))
    }

    @Test
    fun `in-contacts no selects strangers only`() {
        val m = macro(senderInContacts = false)
        assertTrue(MacroRuns.matches(m, candidate(inContacts = false)))
        assertFalse(MacroRuns.matches(m, candidate(inContacts = true)))
    }

    @Test
    fun `in-contacts either selects both`() {
        val m = macro(keyword = "rent")
        assertTrue(MacroRuns.matches(m, candidate(body = "rent due", inContacts = true)))
        assertTrue(MacroRuns.matches(m, candidate(body = "rent due", inContacts = false)))
    }

    // endregion

    // region set criteria combine with AND

    @Test
    fun `every set criterion must match`() {
        val m = macro(senderContains = "555", keyword = "rent", senderInContacts = false)
        assertTrue(
            MacroRuns.matches(
                m,
                candidate(sender = "+14805550137", body = "rent due", inContacts = false)
            )
        )
        assertFalse(
            MacroRuns.matches(
                m,
                candidate(sender = "+14805550137", body = "rent due", inContacts = true)
            )
        )
        assertFalse(
            MacroRuns.matches(
                m,
                candidate(sender = "+14805550137", body = "hello", inContacts = false)
            )
        )
        assertFalse(
            MacroRuns.matches(
                m,
                candidate(sender = "+14806660137", body = "rent due", inContacts = false)
            )
        )
    }

    // endregion

    // region exclusions: locker, hand filings, blocked senders

    @Test
    fun `locker threads are excluded`() {
        val m = macro(keyword = "rent")
        val c = candidate(body = "rent due", currentMailbox = Mailbox.LOCKER)
        assertTrue(MacroRuns.isExcluded(c))
        assertFalse(MacroRuns.matches(m, c))
    }

    @Test
    fun `hand-filed threads are excluded`() {
        val m = macro(keyword = "rent")
        val c = candidate(body = "rent due", manuallyFiled = true)
        assertTrue(MacroRuns.isExcluded(c))
        assertFalse(MacroRuns.matches(m, c))
    }

    @Test
    fun `blocked senders are excluded`() {
        val m = macro(keyword = "rent")
        val c = candidate(body = "rent due", senderBlocked = true)
        assertTrue(MacroRuns.isExcluded(c))
        assertFalse(MacroRuns.matches(m, c))
    }

    @Test
    fun `select returns only the matching, unexcluded threads`() {
        val m = macro(keyword = "rent")
        val candidates = listOf(
            candidate(threadId = 1, body = "rent due"),
            candidate(threadId = 2, body = "rent due", currentMailbox = Mailbox.LOCKER),
            candidate(threadId = 3, body = "rent due", manuallyFiled = true),
            candidate(threadId = 4, body = "rent due", senderBlocked = true),
            candidate(threadId = 5, body = "unrelated"),
            candidate(threadId = 6, body = "rent due", inContacts = true)
        )
        val selected = MacroRuns.select(m, candidates).map { it.threadId }
        assertEquals(listOf(1L, 6L), selected)
    }

    // endregion
}
