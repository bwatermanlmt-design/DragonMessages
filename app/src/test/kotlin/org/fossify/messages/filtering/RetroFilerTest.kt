package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroFilerTest {

    private val starterRules = StarterRules.defaultRules()

    private fun facts(
        sender: String = "+14805550137",
        body: String = "hello there",
        inContacts: Boolean = false,
        tags: Set<String> = emptySet()
    ) = MessageFacts(
        sender = sender,
        body = body,
        senderInContacts = inContacts,
        senderTags = tags
    )

    private fun retro(
        facts: MessageFacts,
        isGroup: Boolean = false,
        manuallyFiled: Boolean = false,
        currentMailbox: String = Mailbox.CONTACTS
    ) = RetroConversationFacts(
        isGroupConversation = isGroup,
        manuallyFiled = manuallyFiled,
        currentMailbox = currentMailbox,
        facts = facts
    )

    private fun filedMailbox(decision: RetroFiler.Decision): String {
        assertTrue("expected File, got $decision", decision is RetroFiler.Decision.File)
        return (decision as RetroFiler.Decision.File).verdict.mailbox
    }

    // region facts map to mailboxes through the starter pack

    @Test
    fun `contact sender files to contacts`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true), currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(Mailbox.CONTACTS, filedMailbox(decision))
    }

    @Test
    fun `short code files to auth`() {
        val decision = RetroFiler.decide(
            retro(facts(sender = "40404", body = "Your code is 123456")),
            starterRules
        )
        assertEquals(Mailbox.AUTH, filedMailbox(decision))
    }

    @Test
    fun `stop2end in latest message files to spam`() {
        val decision = RetroFiler.decide(
            retro(facts(body = "Early voting is open! Reply Stop2End to opt out")),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `unknown sender files to quarantine`() {
        val decision = RetroFiler.decide(retro(facts()), starterRules)
        assertEquals(Mailbox.QUARANTINE, filedMailbox(decision))
    }

    // endregion

    // region protections

    @Test
    fun `group conversations are skipped even when content matches spam`() {
        val decision = RetroFiler.decide(
            retro(facts(body = "deal inside! Stop2End"), isGroup = true),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.GROUP), decision)
    }

    @Test
    fun `manually filed conversations are never overridden`() {
        val decision = RetroFiler.decide(
            retro(facts(body = "deal inside! Stop2End"), manuallyFiled = true),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.MANUALLY_FILED), decision)
    }

    @Test
    fun `locker conversations are left alone`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true), currentMailbox = Mailbox.LOCKER),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.LOCKER), decision)
    }

    @Test
    fun `a rule cannot file a conversation into the locker`() {
        val lockerRule = listOf(
            FilterRule(name = "to locker", priority = 1, keyword = "secret", mailbox = Mailbox.LOCKER)
        )
        val decision = RetroFiler.decide(retro(facts(body = "a secret plan")), lockerRule)
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.LOCKER), decision)
    }

    // endregion

    // region idempotency

    @Test
    fun `already correctly filed conversation is unchanged`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true), currentMailbox = Mailbox.CONTACTS),
            starterRules
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    @Test
    fun `already quarantined unknown sender is unchanged`() {
        val decision = RetroFiler.decide(
            retro(facts(), currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    // endregion
}
