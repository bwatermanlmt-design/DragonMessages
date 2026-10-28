package org.fossify.messages.filtering

import org.fossify.messages.models.Mailbox

/**
 * Final filing semantics (Ben's rulings, 2026-10-28): the rules decide
 * the destination. There are no automatic immunities anymore — contacts
 * no longer settle to an address-book home, replied-to threads carry no
 * spam-immunity, answered threads in Not in Contacts are no longer
 * invisible, and short codes are no longer rewritten to Auth & Services:
 * every one of those is expressible as a rule condition (in-contacts,
 * have-replied-to, sender class), so a rule that says it wins. Pure, so
 * the receive path and the retroactive pass share exactly this
 * behaviour:
 *  0. A conversation in the Locker keeps the Locker, and no rule ever
 *     delivers a conversation into the Locker: it is filled by hand
 *     only. (The retro pass additionally skips Locker threads outright.)
 *     The Locker outranks even a block: a blocked sender's Locker
 *     thread stays in the Locker on arrival, matching the retro pass,
 *     which skips Locker threads before any block is considered.
 *  1. A sender Ben blocked files to Spam — always. Blocking is a
 *     property of the number and permanent (Ben's requirement,
 *     2026-10-22): the blocked fact is the app's own record (the
 *     `blocked` sender tag) or the system BlockedNumberProvider, and
 *     it is genuinely evaluated on every path — blocked messages are
 *     stored and filed to Spam, never silently dropped, so an unblock
 *     can always be audited against what actually arrived.
 *  2. When no rule matched (the verdict carries no rule), a brand-new
 *     thread — one with no current mailbox — files to Not in Contacts,
 *     the built-in default; an existing thread stays in its current
 *     mailbox. A pass that matches nothing moves nothing.
 *  3. Anything else takes the verdict as evaluated.
 */
object ThreadPrecedence {

    fun resolve(
        currentMailbox: String?,
        verdict: RuleVerdict,
        senderBlocked: Boolean,
    ): RuleVerdict {
        if (currentMailbox == Mailbox.LOCKER) return verdict.copy(mailbox = Mailbox.LOCKER)
        if (senderBlocked) return verdict.copy(mailbox = Mailbox.SPAM)
        if (verdict.matchedRule == null) {
            // No rule matched: a new thread takes the default (the
            // verdict already names Not in Contacts), an existing
            // thread stays put.
            return if (currentMailbox != null) verdict.copy(mailbox = currentMailbox) else verdict
        }
        return verdict
    }
}
