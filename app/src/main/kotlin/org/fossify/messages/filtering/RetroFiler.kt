package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/** Facts about one stored conversation, as the retroactive pass sees it. */
data class RetroConversationFacts(
    val isGroupConversation: Boolean,
    val manuallyFiled: Boolean,
    val currentMailbox: String,
    /**
     * Ben has sent a message in this thread: participation is
     * spam-immunity — and, once the thread sits in Not in Contacts,
     * invisibility to the filters (see [RetroFiler]).
     */
    val engaged: Boolean = false,
    /** The thread has at least one inbound message to judge it by. */
    val hasInboundMessage: Boolean = true,
    /**
     * The judged sender is on Ben's permanent blocked-numbers record (or
     * the system block list): its threads file straight to Spam, ahead of
     * the answered-thread ignore — a block breaks the ignore.
     */
    val senderBlocked: Boolean = false,
    val facts: MessageFacts,
)

/**
 * The retroactive filing decision for one stored conversation: a pure
 * function from (conversation facts, rules) to a decision, so the "File
 * existing conversations" pass is unit-testable on the JVM.
 *
 * The pass files on the facts of the thread's most recent inbound message,
 * under exactly the receive path's semantics (see [ThreadPrecedence]):
 * contacts settle to their address-book home, an engaged thread's Spam
 * verdict falls through to the next rule, and everyone else files where
 * the rules put them. Group conversations are judged by the same rules
 * as 1:1 threads — the only group-specific out is a group thread with no
 * inbound message at all, which offers nothing to judge. The hard
 * protections:
 *  - conversations Ben filed by hand are never overridden,
 *  - the Locker is never touched in either direction: its conversations
 *    are left alone, and no rule can deliver a conversation into it,
 *  - a conversation Ben has answered that already sits in Not in Contacts
 *    is invisible to the filters and left alone — unless its sender is in
 *    his contacts, because the address book outranks the ignore and the
 *    thread is still brought home,
 *  - a conversation whose judged sender is permanently blocked files
 *    straight to Spam, ahead of the answered-thread ignore — but the two
 *    hand protections above still win, exactly as the skips order them.
 * A conversation the engine already agrees with is reported as
 * [Decision.AlreadyFiled], which is what makes re-runs idempotent.
 */
object RetroFiler {

    enum class SkipReason { MANUALLY_FILED, LOCKER, NO_INBOUND, ENGAGED_IN_QUARANTINE }

    sealed interface Decision {
        /** Deliver the conversation to the verdict's mailbox. */
        data class File(val verdict: RuleVerdict) : Decision

        /** Leave the conversation untouched. */
        data class Skip(val reason: SkipReason) : Decision

        /** The engine agrees with the current filing; nothing to change. */
        data object AlreadyFiled : Decision
    }

    /** Why a conversation must be left alone, or null if it may be filed. */
    fun skipReason(
        isGroupConversation: Boolean,
        manuallyFiled: Boolean,
        currentMailbox: String,
        hasInboundMessage: Boolean,
    ): SkipReason? = when {
        currentMailbox == Mailbox.LOCKER -> SkipReason.LOCKER
        manuallyFiled -> SkipReason.MANUALLY_FILED
        isGroupConversation && !hasInboundMessage -> SkipReason.NO_INBOUND
        else -> null
    }

    fun decide(conversation: RetroConversationFacts, rules: List<FilterRule>): Decision {
        skipReason(
            isGroupConversation = conversation.isGroupConversation,
            manuallyFiled = conversation.manuallyFiled,
            currentMailbox = conversation.currentMailbox,
            hasInboundMessage = conversation.hasInboundMessage
        )?.let { return Decision.Skip(it) }

        // A blocked sender files straight to Spam. This outranks the
        // answered-thread ignore below (a block breaks the ignore) but not
        // the hand protections above: a hand-filed or Locker thread keeps
        // Ben's filing even for a blocked sender, as the skips ordered it.
        if (conversation.senderBlocked) {
            val verdict = RuleEngine.evaluate(rules, conversation.facts)
            if (verdict.mailbox == Mailbox.LOCKER) return Decision.Skip(SkipReason.LOCKER)
            val effective = ThreadPrecedence.resolve(
                currentMailbox = conversation.currentMailbox,
                verdict = verdict,
                facts = conversation.facts,
                rules = rules,
                engaged = conversation.engaged,
                manuallyFiled = conversation.manuallyFiled,
                senderBlocked = true
            )
            return if (effective.mailbox == conversation.currentMailbox) {
                Decision.AlreadyFiled
            } else {
                Decision.File(effective)
            }
        }

        // Answered threads already sitting in Not in Contacts are
        // invisible to the filters (Ben's clarification, 2026-10-22) and
        // left alone. This is decided here rather than in skipReason()
        // because the address book outranks the ignore — a contact is
        // still brought home by the evaluation below — and skipReason()
        // has no facts to consult. A sender-level hand filing (revision
        // 7) outranks the ignore the same way: if Ben filed this sender
        // somewhere, the scan takes the thread home below.
        if (conversation.engaged &&
            conversation.currentMailbox == Mailbox.QUARANTINE &&
            !conversation.facts.senderInContacts &&
            conversation.facts.senderFiledMailbox == null
        ) {
            return Decision.Skip(SkipReason.ENGAGED_IN_QUARANTINE)
        }

        val verdict = RuleEngine.evaluate(rules, conversation.facts)
        // The Locker is filled by hand only, never by a filing pass.
        if (verdict.mailbox == Mailbox.LOCKER) return Decision.Skip(SkipReason.LOCKER)

        val effective = ThreadPrecedence.resolve(
            currentMailbox = conversation.currentMailbox,
            verdict = verdict,
            facts = conversation.facts,
            rules = rules,
            engaged = conversation.engaged,
            manuallyFiled = conversation.manuallyFiled,
            senderBlocked = false
        )
        if (effective.mailbox == Mailbox.LOCKER) return Decision.Skip(SkipReason.LOCKER)

        return if (effective.mailbox == conversation.currentMailbox) {
            Decision.AlreadyFiled
        } else {
            Decision.File(effective)
        }
    }
}
