package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * Final filing semantics (Ben's rulings, 2026-10-22): one evaluation order
 * that turns the rule engine's raw verdict into the mailbox a conversation
 * actually ends up in. Pure, so the receive path and the retroactive pass
 * share exactly this behaviour:
 *
 *  0. A sender Ben blocked files to Spam — always, contacts included.
 *     Blocking is a property of the number and permanent (Ben's
 *     requirement, 2026-10-22): the blocked fact is the app's own record
 *     (the `blocked` sender tag) or the system BlockedNumberProvider, and
 *     it is genuinely evaluated on every path — blocked messages are
 *     stored and filed to Spam, never silently dropped, so an unblock can
 *     always be audited against what actually arrived.
 *  1. A conversation in the Locker keeps the Locker, and one Ben filed by
 *     hand keeps his filing: his hand outranks the address book below.
 *  2. Contacts are settled before content rules: a person contact's home
 *     is My Contacts, a business-labeled contact's home is Auth & Services,
 *     and no verdict — Spam, Quarantine, anything — can move a contact
 *     anywhere else.
 *  3. Answered threads already sitting in Not in Contacts are invisible
 *     to the filters (Ben's clarification, 2026-10-22): on an **engaged**
 *     thread (Ben has sent a message in it) whose home is Quarantine, no
 *     verdict — content or class — re-files it. It stays until Ben moves
 *     it, adds the sender to his contacts (arm 2 outranks this), or blocks
 *     the sender (arm 0 outranks this).
 *  4. Participation is spam-immunity, nothing more: on an engaged thread
 *     a Spam verdict falls through — the rules are re-evaluated with
 *     every Spam-delivering rule removed and that verdict is used
 *     instead. Engagement confers no other protection: an engaged
 *     non-contact still files to Not in Contacts.
 *  5. Guard: a Contacts verdict for a short-code or alphanumeric sender
 *     becomes Auth & Services — services are informational, never people.
 *  6. Anything else takes the verdict as evaluated.
 */
object ThreadPrecedence {

    fun resolve(
        currentMailbox: String?,
        verdict: RuleVerdict,
        facts: MessageFacts,
        rules: List<FilterRule>,
        engaged: Boolean,
        manuallyFiled: Boolean,
        senderBlocked: Boolean,
    ): RuleVerdict {
        if (senderBlocked) return verdict.copy(mailbox = Mailbox.SPAM)
        if (currentMailbox == Mailbox.LOCKER) return verdict.copy(mailbox = Mailbox.LOCKER)
        if (manuallyFiled && currentMailbox != null) return verdict.copy(mailbox = currentMailbox)

        if (facts.senderInContacts) {
            val home = if (facts.businessLabeled) Mailbox.AUTH else Mailbox.CONTACTS
            return verdict.copy(mailbox = home)
        }

        // Answered threads sitting in Not in Contacts are invisible to the
        // filters: the raw verdict is discarded whole, tag assignment
        // included — the filters did not act on this thread at all.
        if (engaged && currentMailbox == Mailbox.QUARANTINE) {
            return verdict.copy(mailbox = Mailbox.QUARANTINE, assignTag = null)
        }

        if (engaged && verdict.mailbox == Mailbox.SPAM) {
            val nonSpamRules = rules.filterNot { it.mailbox == Mailbox.SPAM }
            return guardServiceClass(RuleEngine.evaluate(nonSpamRules, facts), facts)
        }

        return guardServiceClass(verdict, facts)
    }

    /** Short codes and alphanumeric IDs are services, never people. */
    private fun guardServiceClass(verdict: RuleVerdict, facts: MessageFacts): RuleVerdict {
        if (verdict.mailbox != Mailbox.CONTACTS) return verdict
        val senderClass = SenderClassifier.classify(facts.sender)
        return if (senderClass == SenderClassifier.SHORT_CODE || senderClass == SenderClassifier.ALPHANUMERIC) {
            verdict.copy(mailbox = Mailbox.AUTH)
        } else {
            verdict
        }
    }
}
