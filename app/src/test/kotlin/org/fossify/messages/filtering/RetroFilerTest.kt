package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revision 10 semantics for the retro pass: the rules decide, with no
 * automatic immunities. Replied-to threads, contacts, and hand-filed
 * threads are all judged by the rules; only the Locker and blocks are
 * hard placements. The old immunity tests are rewritten here to assert
 * the new behaviour, not deleted.
 */
class RetroFilerTest {

    // Keyword rules the old starter pack used to ship; kept here as an
    // explicit engine fixture now that the public pack is generic-only.
    private val starterRules = StarterRules.defaultRules() + listOf(
        FilterRule(name = "Test keyword to Spam", priority = 20, keyword = "Stop2End", mailbox = Mailbox.SPAM, assignTag = StarterRules.TAG_CAMPAIGNS),
        FilterRule(name = "Test keyword to Quarantine", priority = 25, keyword = "Gerrit", mailbox = Mailbox.QUARANTINE)
    )

    private fun facts(
        sender: String = "+14805550137",
        body: String = "hello there",
        inContacts: Boolean = false,
        business: Boolean = false,
        tags: Set<String> = emptySet(),
        labelHome: String? = null,
        senderFiled: String? = null
    ) = MessageFacts(
        sender = sender,
        body = body,
        senderInContacts = inContacts,
        senderTags = tags,
        businessLabeled = business,
        labelHome = labelHome,
        senderFiledMailbox = senderFiled
    )

    private fun retro(
        facts: MessageFacts,
        isGroup: Boolean = false,
        manuallyFiled: Boolean = false,
        currentMailbox: String = Mailbox.CONTACTS,
        engaged: Boolean = false,
        hasInbound: Boolean = true,
        senderBlocked: Boolean = false
    ) = RetroConversationFacts(
        isGroupConversation = isGroup,
        manuallyFiled = manuallyFiled,
        currentMailbox = currentMailbox,
        engaged = engaged,
        hasInboundMessage = hasInbound,
        senderBlocked = senderBlocked,
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

    // region replied-to threads are judged by the rules, immunity gone

    @Test
    fun `engaged conversation with spam content files to spam`() {
        val decision = RetroFiler.decide(
            retro(facts(body = "deal inside! Stop2End"), engaged = true),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
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
    fun `answered conversation in not in contacts is judged by the rules`() {
        val decision = RetroFiler.decide(
            retro(
                facts(body = "Hi Gerrit! Reply Stop2End to opt out"),
                engaged = true,
                currentMailbox = Mailbox.QUARANTINE
            ),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `answered short code in not in contacts is judged by the rules`() {
        val decision = RetroFiler.decide(
            retro(
                facts(sender = "34291", body = "Power update: all clear"),
                engaged = true,
                currentMailbox = Mailbox.QUARANTINE
            ),
            starterRules
        )
        assertEquals(Mailbox.AUTH, filedMailbox(decision))
    }

    @Test
    fun `answered conversation in not in contacts with spam content files to spam`() {
        val decision = RetroFiler.decide(
            retro(
                facts(body = "deal inside! Stop2End"),
                engaged = true,
                currentMailbox = Mailbox.QUARANTINE
            ),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
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
    fun `business labeled contact files to contacts by the contacts rule`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true, business = true), currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(Mailbox.CONTACTS, filedMailbox(decision))
    }

    // endregion

    // region the new conditions: replied-to and hand-filed

    private val handFiledSpamRule = listOf(
        FilterRule(name = "hand filed to spam", priority = 1, manuallyFiled = true, mailbox = Mailbox.SPAM)
    )

    private val notHandFiledSpamRule = listOf(
        FilterRule(name = "not hand filed to spam", priority = 1, manuallyFiled = false, mailbox = Mailbox.SPAM)
    )

    private val repliedSpamRule = listOf(
        FilterRule(name = "replied to spam", priority = 1, repliedTo = true, mailbox = Mailbox.SPAM)
    )

    @Test
    fun `rule requiring hand-filed matches a hand-filed thread`() {
        val decision = RetroFiler.decide(
            retro(facts(), manuallyFiled = true, currentMailbox = Mailbox.CONTACTS),
            handFiledSpamRule
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `rule requiring hand-filed does not match a thread that was not filed by hand`() {
        // No rule matches, so the thread stays put (not a skip anymore).
        val decision = RetroFiler.decide(
            retro(facts(), manuallyFiled = false, currentMailbox = Mailbox.CONTACTS),
            handFiledSpamRule
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    @Test
    fun `sender-level filing record counts as hand-filed`() {
        val familyFolder = Mailbox.customMailboxId("folder-family")
        val decision = RetroFiler.decide(
            retro(facts(senderFiled = familyFolder), manuallyFiled = false, currentMailbox = Mailbox.CONTACTS),
            handFiledSpamRule
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `rule excluding hand-filed matches a thread that was not filed by hand`() {
        val decision = RetroFiler.decide(
            retro(facts(), manuallyFiled = false, currentMailbox = Mailbox.CONTACTS),
            notHandFiledSpamRule
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `rule excluding hand-filed does not match a hand-filed thread`() {
        val decision = RetroFiler.decide(
            retro(facts(), manuallyFiled = true, currentMailbox = Mailbox.CONTACTS),
            notHandFiledSpamRule
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    @Test
    fun `rule requiring replied-to matches an answered thread`() {
        val decision = RetroFiler.decide(
            retro(facts(), engaged = true, currentMailbox = Mailbox.CONTACTS),
            repliedSpamRule
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `rule requiring replied-to does not match an unanswered thread`() {
        val decision = RetroFiler.decide(
            retro(facts(), engaged = false, currentMailbox = Mailbox.CONTACTS),
            repliedSpamRule
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    // endregion

    // region protections: only the Locker still holds

    @Test
    fun `answered manually filed conversation with no matching rule stays put`() {
        // The catch-all agrees with the current mailbox: unchanged, not a skip.
        val decision = RetroFiler.decide(
            retro(facts(), manuallyFiled = true, engaged = true, currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    @Test
    fun `manually filed conversations are judged by the rules`() {
        val decision = RetroFiler.decide(
            retro(facts(body = "deal inside! Stop2End"), manuallyFiled = true),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
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

    // region permanent, number-level blocking

    @Test
    fun `blocked sender files to spam`() {
        val decision = RetroFiler.decide(
            retro(facts(), currentMailbox = Mailbox.QUARANTINE, senderBlocked = true),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `blocked contact files to spam`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true), currentMailbox = Mailbox.CONTACTS, senderBlocked = true),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `blocked answered thread in not in contacts files to spam, not skipped`() {
        val decision = RetroFiler.decide(
            retro(
                facts(),
                currentMailbox = Mailbox.QUARANTINE,
                engaged = true,
                senderBlocked = true
            ),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `blocked hand-filed thread files to spam`() {
        // Hand filing no longer protects against a block: the block wins.
        val decision = RetroFiler.decide(
            retro(
                facts(),
                currentMailbox = Mailbox.QUARANTINE,
                manuallyFiled = true,
                senderBlocked = true
            ),
            starterRules
        )
        assertEquals(Mailbox.SPAM, filedMailbox(decision))
    }

    @Test
    fun `blocked thread already in spam is unchanged`() {
        val decision = RetroFiler.decide(
            retro(facts(), currentMailbox = Mailbox.SPAM, senderBlocked = true),
            starterRules
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    @Test
    fun `unblocked sender is judged normally again`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true), currentMailbox = Mailbox.QUARANTINE, senderBlocked = false),
            starterRules
        )
        assertEquals(Mailbox.CONTACTS, filedMailbox(decision))
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

    // region revision 7 homes no longer decide: label and sender records are facts, not placements

    private val familyFolder = Mailbox.customMailboxId("folder-family")
    private val workFolder = Mailbox.customMailboxId("folder-work")

    @Test
    fun `contact with a label-mapped home files to contacts by the contacts rule`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true, labelHome = familyFolder), currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(Mailbox.CONTACTS, filedMailbox(decision))
    }

    @Test
    fun `label change no longer re-homes a contact without a rule`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true, labelHome = workFolder), currentMailbox = familyFolder),
            starterRules
        )
        assertEquals(Mailbox.CONTACTS, filedMailbox(decision))
    }

    @Test
    fun `thread away from its sender-filed home is judged by the rules`() {
        val decision = RetroFiler.decide(
            retro(facts(senderFiled = familyFolder), currentMailbox = Mailbox.CONTACTS),
            starterRules
        )
        assertEquals(Mailbox.QUARANTINE, filedMailbox(decision))
    }

    @Test
    fun `sender filing record does not move an answered thread`() {
        // Engaged, sitting in Not in Contacts, with a sender record: the
        // catch-all agrees with the current mailbox, so nothing moves.
        val decision = RetroFiler.decide(
            retro(
                facts(senderFiled = familyFolder),
                currentMailbox = Mailbox.QUARANTINE,
                engaged = true
            ),
            starterRules
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    @Test
    fun `thread at its sender-filed home is judged by the rules`() {
        val decision = RetroFiler.decide(
            retro(facts(senderFiled = familyFolder), currentMailbox = familyFolder),
            starterRules
        )
        assertEquals(Mailbox.QUARANTINE, filedMailbox(decision))
    }

    @Test
    fun `non-contact hand-filed to contacts by sender record is judged by the rules`() {
        val decision = RetroFiler.decide(
            retro(facts(senderFiled = Mailbox.CONTACTS), currentMailbox = Mailbox.CONTACTS),
            starterRules
        )
        assertEquals(Mailbox.QUARANTINE, filedMailbox(decision))
    }

    // endregion
}
