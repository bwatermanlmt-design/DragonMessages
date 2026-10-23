package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * One stored conversation as an on-demand macro run sees it: the thread's
 * sender, its display name, its most recent message body (the stored
 * snippet), and the filing state the run's exclusions consult.
 */
data class MacroCandidate(
    val threadId: Long,
    val sender: String,
    val senderName: String,
    val body: String,
    val senderInContacts: Boolean,
    val currentMailbox: String,
    val manuallyFiled: Boolean,
    val senderBlocked: Boolean,
    val isGroupConversation: Boolean = false,
)

/**
 * On-demand macro runs (Ben's revision 7 ruling): a macro is a saved set
 * of criteria plus a target folder; running it lists every stored thread
 * that matches — known and unknown senders alike — and Ben ticks the
 * ones to file. The matching itself is the rule engine's (a macro IS a
 * rule row), so a macro's criteria mean exactly what a rule's conditions
 * mean. Three kinds of thread are never bulk-move fodder, whatever
 * matches:
 *  - threads in the Locker (Ben's hand only, ever),
 *  - threads Ben already filed by hand (his placements are settled),
 *  - threads whose sender is blocked (Spam owns them via the block).
 * Pure, so the selection is unit-testable on the JVM.
 */
object MacroRuns {

    fun isExcluded(candidate: MacroCandidate): Boolean {
        return candidate.currentMailbox == Mailbox.LOCKER ||
            candidate.manuallyFiled ||
            candidate.senderBlocked
    }

    fun matches(macro: FilterRule, candidate: MacroCandidate): Boolean {
        if (isExcluded(candidate)) {
            return false
        }

        val facts = MessageFacts(
            sender = candidate.sender,
            body = candidate.body,
            senderInContacts = candidate.senderInContacts,
            senderName = candidate.senderName
        )
        return RuleEngine.matches(macro, facts)
    }

    /** The threads a run of [macro] would offer Ben, in input order. */
    fun select(macro: FilterRule, candidates: List<MacroCandidate>): List<MacroCandidate> {
        return candidates.filter { matches(macro, it) }
    }
}
