package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/** Facts about one stored conversation, as the retroactive pass sees it. */
data class RetroConversationFacts(
    val isGroupConversation: Boolean,
    val manuallyFiled: Boolean,
    val currentMailbox: String,
    /**
     * Ben has sent a message in this thread: the retro pass reports it
     * as the "Have replied to" fact for rule conditions (revision 10).
     */
    val engaged: Boolean = false,
    /** The thread has at least one inbound message to judge it by. */
    val hasInboundMessage: Boolean = true,
    /**
     * The judged sender is on Ben's permanent blocked-numbers record (or
     * the system block list): its threads file straight to Spam.
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
 * the rules decide, with no automatic immunities — a replied-to thread,
 * a contact, or a thread Ben filed by hand is judged like everything
 * else, and a rule can require or exclude replied-to or hand-filed
 * threads as a condition. Group conversations are judged by the same
 * rules as 1:1 threads — the only group-specific out is a group thread
 * with no inbound message at all, which offers nothing to judge. The
 * hard protections:
 *  - the Locker is never touched in either direction: its conversations
 *    are left alone, and no rule can deliver a conversation into it,
 *  - a conversation whose judged sender is permanently blocked files
 *    straight to Spam.
 * A conversation the engine already agrees with is reported as
 * [Decision.AlreadyFiled], which is what makes re-runs idempotent.
 */
object RetroFiler {

    enum class SkipReason { LOCKER, NO_INBOUND }

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
        currentMailbox: String,
        hasInboundMessage: Boolean,
    ): SkipReason? = when {
        currentMailbox == Mailbox.LOCKER -> SkipReason.LOCKER
        isGroupConversation && !hasInboundMessage -> SkipReason.NO_INBOUND
        else -> null
    }

    fun decide(conversation: RetroConversationFacts, rules: List<FilterRule>): Decision {
        skipReason(
            isGroupConversation = conversation.isGroupConversation,
            currentMailbox = conversation.currentMailbox,
            hasInboundMessage = conversation.hasInboundMessage
        )?.let { return Decision.Skip(it) }

        // The facts the rules see: the thread's reply and hand-filing
        // state are conditions like any other now (revision 10). A
        // thread counts as hand-filed when its thread-level flag is set
        // OR a sender-level filing record exists for its sender.
        val effectiveFacts = conversation.facts.copy(
            repliedTo = conversation.engaged,
            manuallyFiled = conversation.manuallyFiled ||
                conversation.facts.senderFiledMailbox != null
        )

        val verdict = RuleEngine.evaluate(rules, effectiveFacts)
        // The Locker is filled by hand only, never by a filing pass.
        if (verdict.mailbox == Mailbox.LOCKER) return Decision.Skip(SkipReason.LOCKER)

        val effective = ThreadPrecedence.resolve(
            currentMailbox = conversation.currentMailbox,
            verdict = verdict,
            senderBlocked = conversation.senderBlocked
        )
        if (effective.mailbox == Mailbox.LOCKER) return Decision.Skip(SkipReason.LOCKER)

        return if (effective.mailbox == conversation.currentMailbox) {
            Decision.AlreadyFiled
        } else {
            Decision.File(effective)
        }
    }
}
