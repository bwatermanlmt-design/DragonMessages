package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Revision 10 semantics: the rules decide. There are no automatic
 * immunities anymore — contacts, replied-to threads, and answered
 * threads in Not in Contacts are all judged by the rules, and only a
 * block and the Locker are hard placements. The old immunity tests are
 * rewritten here to assert the new behaviour, not deleted: each one
 * names what the rules do now.
 */
class ThreadPrecedenceTest {

    // Keyword rules the old starter pack used to ship; kept here as an
    // explicit engine fixture now that the public pack is generic-only.
    private val starterRules = StarterRules.defaultRules() + listOf(
        FilterRule(name = "Test keyword to Spam", priority = 20, keyword = "Stop2End", mailbox = Mailbox.SPAM, assignTag = StarterRules.TAG_CAMPAIGNS),
        FilterRule(name = "Test keyword to Quarantine", priority = 25, keyword = "Gerrit", mailbox = Mailbox.QUARANTINE)
    )

    /** One rule only, so "no rule matches" is reachable (no catch-all). */
    private val spamOnlyRules = listOf(
        FilterRule(name = "Test keyword to Spam", priority = 20, keyword = "Stop2End", mailbox = Mailbox.SPAM)
    )

    private fun facts(
        sender: String = "+14805550137",
        body: String = "hello there",
        inContacts: Boolean = false,
        business: Boolean = false,
        tags: Set<String> = emptySet(),
        labelHome: String? = null,
        senderFiled: String? = null,
        repliedTo: Boolean = false,
        manuallyFiled: Boolean = false
    ) = MessageFacts(
        sender = sender,
        body = body,
        senderInContacts = inContacts,
        senderTags = tags,
        businessLabeled = business,
        labelHome = labelHome,
        senderFiledMailbox = senderFiled,
        repliedTo = repliedTo,
        manuallyFiled = manuallyFiled
    )

    /** Evaluate the raw verdict, then resolve it through precedence. */
    private fun resolve(
        current: String?,
        facts: MessageFacts,
        rules: List<FilterRule> = starterRules,
        senderBlocked: Boolean = false
    ): RuleVerdict {
        val raw = RuleEngine.evaluate(rules, facts)
        return ThreadPrecedence.resolve(
            currentMailbox = current,
            verdict = raw,
            senderBlocked = senderBlocked
        )
    }

    // region the rules decide: contacts are ordinary rule matches now

    @Test
    fun `contact with stop2end body files to spam when the spam rule outranks the contacts rule`() {
        val rules = listOf(
            FilterRule(name = "Spam first", priority = 5, keyword = "Stop2End", mailbox = Mailbox.SPAM)
        ) + starterRules
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "Early voting is open! Reply Stop2End to opt out", inContacts = true),
            rules = rules
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `contact with stop2end body files to contacts by the contacts rule`() {
        // The starter contacts rule (priority 10) outranks the spam rule
        // (priority 20): contact protection is rule order now, not immunity.
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "Early voting is open! Reply Stop2End to opt out", inContacts = true)
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `contact with gerrit body files to contacts by the contacts rule`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "Hi Gerrit, your brokerage account needs attention", inContacts = true)
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `business contact files to contacts by the contacts rule, not auth`() {
        // No business rule ships in the starter pack, so the contacts
        // rule takes it: the old business-to-auth immunity is gone.
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "Your statement is ready. Stop2End", inContacts = true, business = true)
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `business contact with a clean body files to contacts by the contacts rule`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(inContacts = true, business = true)
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    // endregion

    // region replied-to threads carry no spam-immunity anymore

    @Test
    fun `replied-to non-contact with stop2end files to spam`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(body = "Hi Gerrit! Reply Stop2End to opt out", repliedTo = true)
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `replied-to short code with stop2end files to spam`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(sender = "34291", body = "Power update. Stop2End", repliedTo = true)
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `replied-to thread takes a not-in-contacts verdict as evaluated`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(body = "Hi Gerrit, your brokerage account needs attention", repliedTo = true)
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
    }

    @Test
    fun `replied-to thread in spam stays in spam by the rule`() {
        val verdict = resolve(
            current = Mailbox.SPAM,
            facts = facts(body = "deal inside! Stop2End", repliedTo = true)
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `unengaged stranger with stop2end files to spam`() {
        val verdict = resolve(current = null, facts = facts(body = "deal inside! Stop2End"))
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    // endregion

    // region the service-class guard is gone

    @Test
    fun `a rule delivering a short code to contacts is no longer corrected to auth`() {
        val rules = listOf(
            FilterRule(
                name = "misguided", priority = 1,
                senderClass = SenderClassifier.SHORT_CODE, mailbox = Mailbox.CONTACTS
            )
        )
        val verdict = resolve(current = null, facts = facts(sender = "40404"), rules = rules)
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `short code files to auth by the short-code rule`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(sender = "34291", body = "Power update: all clear")
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    // endregion

    // region answered threads in Not in Contacts are judged by the rules

    @Test
    fun `answered short code in not in contacts is judged by the rules`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(sender = "34291", body = "Power update: all clear", repliedTo = true)
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    @Test
    fun `answered thread in not in contacts with stop2end files to spam`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "deal inside! Stop2End", repliedTo = true)
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `answered thread in not in contacts keeps the rule verdict's tag`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "deal inside! Stop2End", repliedTo = true)
        )
        assertEquals(StarterRules.TAG_CAMPAIGNS, verdict.assignTag)
    }

    @Test
    fun `answered contact in not in contacts files to contacts by the contacts rule`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(inContacts = true, repliedTo = true)
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `answered business contact in not in contacts files to contacts by the contacts rule`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(inContacts = true, business = true, repliedTo = true)
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `blocked answered thread in not in contacts files to spam`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(repliedTo = true),
            senderBlocked = true
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    // endregion

    // region no-match semantics: new threads take the default, existing threads stay put

    @Test
    fun `no matching rule files a brand-new thread to not in contacts`() {
        val verdict = resolve(
            current = null,
            facts = facts(body = "nothing matching here"),
            rules = spamOnlyRules
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
        assertNull(verdict.matchedRule)
    }

    @Test
    fun `no matching rule leaves an existing thread where it is`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(body = "nothing matching here"),
            rules = spamOnlyRules
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
        assertNull(verdict.matchedRule)
    }

    @Test
    fun `no matching rule leaves an existing spam thread in spam`() {
        val verdict = resolve(
            current = Mailbox.SPAM,
            facts = facts(body = "nothing matching here"),
            rules = spamOnlyRules
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    // endregion

    // region block still outranks, and the Locker still holds

    @Test
    fun `blocked sender files to spam even when a contact`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(inContacts = true),
            senderBlocked = true
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `blocked sender with no existing conversation files to spam`() {
        // The thread was deleted after the block (or never existed): the
        // block belongs to the number, so its next arrival still files
        // straight to Spam.
        val verdict = resolve(
            current = null,
            facts = facts(),
            senderBlocked = true
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `blocked locker thread stays in the locker`() {
        // The Locker outranks even a block, so arrival and retro agree.
        val verdict = resolve(
            current = Mailbox.LOCKER,
            facts = facts(),
            senderBlocked = true
        )
        assertEquals(Mailbox.LOCKER, verdict.mailbox)
    }

    @Test
    fun `locker thread keeps the locker`() {
        val verdict = resolve(
            current = Mailbox.LOCKER,
            facts = facts(body = "deal inside! Stop2End", repliedTo = true)
        )
        assertEquals(Mailbox.LOCKER, verdict.mailbox)
    }

    @Test
    fun `thread in the locker stays in the locker despite a sender filing`() {
        val verdict = resolve(
            current = Mailbox.LOCKER,
            facts = facts(senderFiled = Mailbox.customMailboxId("folder-family"))
        )
        assertEquals(Mailbox.LOCKER, verdict.mailbox)
    }

    // endregion

    // region hand filing no longer protects, and label/sender homes no longer decide

    @Test
    fun `hand-filed thread is moved by a matching rule`() {
        val verdict = resolve(
            current = Mailbox.AUTH,
            facts = facts(body = "deal inside! Stop2End", manuallyFiled = true)
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `hand-filed thread with no matching rule stays put`() {
        val verdict = resolve(
            current = Mailbox.AUTH,
            facts = facts(body = "nothing matching here", manuallyFiled = true),
            rules = spamOnlyRules
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    @Test
    fun `label-mapped home no longer moves a contact without a rule`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(inContacts = true, labelHome = Mailbox.customMailboxId("folder-family"))
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `sender filing record no longer moves a thread without a rule`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(senderFiled = Mailbox.customMailboxId("folder-family"))
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
    }

    @Test
    fun `blocked beats a sender filing`() {
        val verdict = resolve(
            current = Mailbox.customMailboxId("folder-family"),
            facts = facts(senderFiled = Mailbox.customMailboxId("folder-family")),
            senderBlocked = true
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `short code hand-filed to contacts is judged by the rules`() {
        val verdict = resolve(
            current = null,
            facts = facts(sender = "34291", senderFiled = Mailbox.CONTACTS)
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    // endregion

    // region automatic macros (revision 7): rules with a friendlier face

    private fun macroRule(
        mailbox: String,
        keyword: String? = null,
        senderContains: String? = null,
        priority: Int = 15
    ) = FilterRule(
        name = "Macro",
        priority = priority,
        keyword = keyword,
        senderContains = senderContains,
        mailbox = mailbox,
        isMacro = true,
        macroAutomatic = true
    )

    @Test
    fun `automatic macro files a non-contact arrival to its folder`() {
        val rules = starterRules + macroRule(Mailbox.customMailboxId("folder-family"), keyword = "rent")
        val verdict = resolve(
            current = null,
            facts = facts(body = "Your rent is due Friday"),
            rules = rules
        )
        assertEquals(Mailbox.customMailboxId("folder-family"), verdict.mailbox)
    }

    @Test
    fun `automatic macro outranking the contacts rule moves a contact arrival`() {
        val rules = starterRules + macroRule(Mailbox.customMailboxId("folder-family"), keyword = "rent", priority = 5)
        val verdict = resolve(
            current = null,
            facts = facts(body = "Your rent is due Friday", inContacts = true),
            rules = rules
        )
        assertEquals(Mailbox.customMailboxId("folder-family"), verdict.mailbox)
    }

    @Test
    fun `automatic macro targeting spam files a non-contact there`() {
        val rules = starterRules + macroRule(Mailbox.SPAM, senderContains = "prize")
        val verdict = resolve(
            current = null,
            facts = facts(sender = "PRIZE-WINNER", body = "hello"),
            rules = rules
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    // endregion
}
