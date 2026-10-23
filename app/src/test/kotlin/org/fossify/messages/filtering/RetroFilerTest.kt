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
        // A block breaks the answered-thread ignore.
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
    fun `blocked hand-filed thread is still left alone`() {
        // The retro pass's hand protections order ahead of the block.
        val decision = RetroFiler.decide(
            retro(
                facts(),
                currentMailbox = Mailbox.QUARANTINE,
                manuallyFiled = true,
                senderBlocked = true
            ),
            starterRules
        )
        assertEquals(RetroFiler.Decision.Skip(RetroFiler.SkipReason.MANUALLY_FILED), decision)
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

    // region revision 7: label homes and sender-level filings in the scan

    private val familyFolder = Mailbox.customMailboxId("folder-family")
    private val workFolder = Mailbox.customMailboxId("folder-work")

    @Test
    fun `contact with a label-mapped home is filed to the folder`() {
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true, labelHome = familyFolder), currentMailbox = Mailbox.QUARANTINE),
            starterRules
        )
        assertEquals(familyFolder, filedMailbox(decision))
    }

    @Test
    fun `relabelled contact is re-homed by the scan`() {
        // The thread sits in the folder the old label said; the contact's
        // labels changed, so the scan takes it to the new label's home.
        val decision = RetroFiler.decide(
            retro(facts(inContacts = true, labelHome = workFolder), currentMailbox = familyFolder),
            starterRules
        )
        assertEquals(workFolder, filedMailbox(decision))
    }

    @Test
    fun `thread away from its sender-filed home is filed home`() {
        // E.g. the thread was recreated after deletion, landing at the
        // install default, while the sender's record says Family.
        val decision = RetroFiler.decide(
            retro(facts(senderFiled = familyFolder), currentMailbox = Mailbox.CONTACTS),
            starterRules
        )
        assertEquals(familyFolder, filedMailbox(decision))
    }

    @Test
    fun `sender filing outranks the answered-thread ignore`() {
        // Engaged and sitting in Not in Contacts would normally be left
        // alone — but Ben filed this sender, so the scan takes it home.
        val decision = RetroFiler.decide(
            retro(
                facts(senderFiled = familyFolder),
                currentMailbox = Mailbox.QUARANTINE,
                engaged = true
            ),
            starterRules
        )
        assertEquals(familyFolder, filedMailbox(decision))
    }

    @Test
    fun `thread already at its sender-filed home is unchanged`() {
        val decision = RetroFiler.decide(
            retro(facts(senderFiled = familyFolder), currentMailbox = familyFolder),
            starterRules
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    @Test
    fun `non-contact hand-filed to contacts by sender record survives the scan`() {
        // Ben pulled one stranger thread into My Contacts by hand; the
        // sender record makes the scan agree it belongs there.
        val decision = RetroFiler.decide(
            retro(facts(senderFiled = Mailbox.CONTACTS), currentMailbox = Mailbox.CONTACTS),
            starterRules
        )
        assertEquals(RetroFiler.Decision.AlreadyFiled, decision)
    }

    // endregion
}
