package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadPrecedenceTest {

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

    /** Evaluate the raw verdict, then resolve it through precedence. */
    private fun resolve(
        current: String?,
        facts: MessageFacts,
        rules: List<FilterRule> = starterRules,
        engaged: Boolean = false,
        manuallyFiled: Boolean = false,
        senderBlocked: Boolean = false
    ): RuleVerdict {
        val raw = RuleEngine.evaluate(rules, facts)
        return ThreadPrecedence.resolve(
            currentMailbox = current,
            verdict = raw,
            facts = facts,
            rules = rules,
            engaged = engaged,
            manuallyFiled = manuallyFiled,
            senderBlocked = senderBlocked
        )
    }

    // region contacts are settled before content rules

    @Test
    fun `contact with stop2end body and no engagement files to contacts`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "Early voting is open! Reply Stop2End to opt out", inContacts = true)
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `contact with gerrit body files to contacts`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "Hi Gerrit, your brokerage account needs attention", inContacts = true)
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `business contact on a long number with stop2end files to auth`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "Your statement is ready. Stop2End", inContacts = true, business = true)
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    @Test
    fun `business contact with a clean body files to auth`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(inContacts = true, business = true)
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    // endregion

    // region participation is spam-immunity, nothing more

    @Test
    fun `engaged non-contact with stop2end and gerrit falls through to not in contacts`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(body = "Hi Gerrit! Reply Stop2End to opt out"),
            engaged = true
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
    }

    @Test
    fun `engaged short code with stop2end falls through to auth`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(sender = "34291", body = "Power update. Stop2End"),
            engaged = true
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    @Test
    fun `engaged thread takes a not-in-contacts verdict as evaluated`() {
        val verdict = resolve(
            current = Mailbox.CONTACTS,
            facts = facts(body = "Hi Gerrit, your brokerage account needs attention"),
            engaged = true
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
    }

    @Test
    fun `engaged thread in spam leaves spam when the fall-through verdict says so`() {
        val verdict = resolve(
            current = Mailbox.SPAM,
            facts = facts(body = "deal inside! Stop2End"),
            engaged = true
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
    }

    @Test
    fun `unengaged stranger with stop2end files to spam`() {
        val verdict = resolve(current = null, facts = facts(body = "deal inside! Stop2End"))
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    // endregion

    // region services are never people

    @Test
    fun `a rule delivering a short code to contacts is corrected to auth`() {
        val rules = listOf(
            FilterRule(
                name = "misguided", priority = 1,
                senderClass = SenderClassifier.SHORT_CODE, mailbox = Mailbox.CONTACTS
            )
        )
        val verdict = resolve(current = null, facts = facts(sender = "40404"), rules = rules)
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    // endregion

    // region answered threads in Not in Contacts are invisible to the filters

    @Test
    fun `answered short code in not in contacts stays there`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(sender = "34291", body = "Power update: all clear"),
            engaged = true
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
    }

    @Test
    fun `answered thread in not in contacts with stop2end stays there`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "deal inside! Stop2End"),
            engaged = true
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
    }

    @Test
    fun `answered thread in not in contacts discards the raw verdict's tag`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(body = "deal inside! Stop2End"),
            engaged = true
        )
        assertEquals(null, verdict.assignTag)
    }

    @Test
    fun `answered contact in not in contacts is brought home`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(inContacts = true),
            engaged = true
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `answered business contact in not in contacts files to auth`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(inContacts = true, business = true),
            engaged = true
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    @Test
    fun `unanswered short code in not in contacts still files to auth`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(sender = "34291", body = "Power update: all clear")
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    @Test
    fun `blocked answered thread in not in contacts files to spam`() {
        val verdict = resolve(
            current = Mailbox.QUARANTINE,
            facts = facts(),
            engaged = true,
            senderBlocked = true
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    // endregion

    // region Ben's hand outranks everything

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
    fun `blocked sender files to spam regardless of manual filing`() {
        val verdict = resolve(
            current = Mailbox.AUTH,
            facts = facts(),
            manuallyFiled = true,
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
    fun `manually filed thread keeps Ben's filing over the address book`() {
        val verdict = resolve(
            current = Mailbox.AUTH,
            facts = facts(inContacts = true),
            manuallyFiled = true
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    @Test
    fun `locker thread keeps the locker`() {
        val verdict = resolve(
            current = Mailbox.LOCKER,
            facts = facts(body = "deal inside! Stop2End"),
            engaged = true
        )
        assertEquals(Mailbox.LOCKER, verdict.mailbox)
    }

    // endregion
}
