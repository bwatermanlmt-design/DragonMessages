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
        business: Boolean = false,
        tags: Set<String> = emptySet()
    ) = MessageFacts(
        sender = sender,
        body = body,
        senderInContacts = inContacts,
        senderTags = tags,
        businessLabeled = business
    )

    private fun retro(
        facts: MessageFacts,
        isGroup: Boolean = false,
        manuallyFiled: Boolean = false,
        currentMailbox: String = Mailbox.CONTACTS,
        engaged: Boolean = false,
        hasInbound: Boolean = true
    ) = RetroConversationFacts(
        isGroupConversation = isGroup,
        manuallyFiled = manuallyFiled,
        currentMailbox = currentMailbox,
        engaged = engaged,
        hasInboundMessage = hasInbound,
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

    // region group conversations are judged by the same rules

    @Test
    fun `group conversations are filed by the same rules, spam included`() {
        val decision = RetroFiler.decide(
            retro(facts(body = "deal inside! Stop2End"), isGroup = true),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `group conversation with a contact sender files to contacts`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true), isGroup = true, currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(Mailbox.CONTACTS, filedMailbox(decision))
    }

    @Test
    fun `group conversation with no inbound message is skipped`() {
        val decision = RetroFiler.decide(
            retro(facts(body = "deal inside! Stop2End"), isGroup = true, hasInbound = false),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.NO_INBOUND), decision)
    }

    // endregion

    // region engaged threads: spam-immunity, and invisibility once in Not in Contacts

    @Test
    fun `engaged conversation with spam content falls through to not in contacts`() {
        val decision = RetroFiler.decide(
            retro(facts(body = "deal inside! Stop2End"), engaged = true),
            starterRules
        )
        assertEquals(Mailbox.QUARANTINE, filedMailbox(decision))
    }

    @Test
    fun `engaged conversation files to not in contacts on the plain verdict`() {
        val decision = RetroFiler.decide(
            retro(facts(), engaged = true),
            starterRules
        )
        assertEquals(Mailbox.QUARANTINE, filedMailbox(decision))
    }

    @Test
    fun `answered conversation in not in contacts is left alone`() {
        val decision = RetroFiler.decide(
            retro(
                facts(body = "Hi Gerrit! Reply Stop2End to opt out"),
                engaged = true,
                currentMailbox = Mailbox.QUARANTINE
            ),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.ENGAGED_IN_QUARANTINE), decision)
    }

    @Test
    fun `answered short code in not in contacts is left alone`() {
        val decision = RetroFiler.decide(
            retro(
                facts(sender = "34291", body = "Power update: all clear"),
                engaged = true,
                currentMailbox = Mailbox.QUARANTINE
            ),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.ENGAGED_IN_QUARANTINE), decision)
    }

    @Test
    fun `answered conversation in not in contacts with spam content is left alone`() {
        val decision = RetroFiler.decide(
            retro(
                facts(body = "deal inside! Stop2End"),
                engaged = true,
                currentMailbox = Mailbox.QUARANTINE
            ),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.ENGAGED_IN_QUARANTINE), decision)
    }

    @Test
    fun `engaged conversation can still be upgraded`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true), engaged = true, currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(Mailbox.CONTACTS, filedMailbox(decision))
    }

    @Test
    fun `business labeled contact files to auth`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true, business = true), currentMailbox = Mailbox.CONTACTS),
            starterRules
        )
        assertEquals(Mailbox.AUTH, filedMailbox(decision))
    }

    // endregion

    // region protections

    @Test
    fun `answered manually filed conversation keeps the manual protection`() {
        val decision = RetroFiler.decide(
            retro(facts(), manuallyFiled = true, engaged = true, currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.MANUALLY_FILED), decision)
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
