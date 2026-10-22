package org.fossify.messages.filtering

import org.fossify.messages.models.Mailbox

/**
 * Thread-state precedence (Ben's ruling, 2026-10-22): filtering judges
 * conversations, never individual messages, and participation is approval.
 *
 * A pure function that takes the rule engine's verdict for an incoming
 * message and the conversation's existing state, and returns the mailbox
 * the conversation must actually end up in:
 *
 *  - An explicitly blocked sender files to Spam regardless of everything.
 *    (On the receive path such senders never even reach evaluation — the
 *    system BlockedNumberProvider drops them first — but the rule is part
 *    of the contract, so it lives here and is tested here.)
 *  - A conversation Ben filed by hand keeps its mailbox.
 *  - A conversation in the Locker keeps its mailbox: the Locker is
 *    hand-filled only, in both directions.
 *  - An **engaged** conversation — one containing a message Ben sent —
 *    cannot be demoted to Spam or Quarantine by a receive-time verdict,
 *    whatever the message contains. Verdicts that agree with the current
 *    mailbox, or move the thread to My Contacts / Auth & Services, are
 *    applied as usual.
 *  - Anything else (a genuinely new or untouched thread) takes the verdict.
 */
object ThreadPrecedence {

    fun resolve(
        currentMailbox: String?,
        verdictMailbox: String,
        engaged: Boolean,
        manuallyFiled: Boolean,
        senderBlocked: Boolean,
    ): String {
        if (senderBlocked) return Mailbox.SPAM
        if (currentMailbox == null) return verdictMailbox
        if (manuallyFiled) return currentMailbox
        if (currentMailbox == Mailbox.LOCKER) return currentMailbox
        if (engaged && (verdictMailbox == Mailbox.SPAM || verdictMailbox == Mailbox.QUARANTINE)) {
            return currentMailbox
        }
        return verdictMailbox
    }
}
