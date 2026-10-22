package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/** Facts about one stored conversation, as the retroactive pass sees it. */
data class RetroConversationFacts(
    val isGroupConversation: Boolean,
    val manuallyFiled: Boolean,
    val currentMailbox: String,
    val facts: MessageFacts,
)

/**
 * The retroactive filing decision for one stored conversation: a pure
 * function from (conversation facts, rules) to a decision, so the "File
 * existing conversations" pass is unit-testable on the JVM.
 *
 * The pass files on sender facts plus the thread's most recent inbound
 * message, with three hard protections:
 *  - group conversations are skipped (sender rules don't describe a crowd),
 *  - conversations Ben filed by hand are never overridden,
 *  - the Locker is never touched in either direction: its conversations are
 *    left alone, and no rule can deliver a conversation into it.
 * A conversation the engine already agrees with is reported as
 * [Decision.AlreadyFiled], which is what makes re-runs idempotent.
 */
object RetroFiler {

    enum class SkipReason { GROUP, MANUALLY_FILED, LOCKER }

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
    ): SkipReason? = when {
        currentMailbox == Mailbox.LOCKER -> SkipReason.LOCKER
        manuallyFiled -> SkipReason.MANUALLY_FILED
        isGroupConversation -> SkipReason.GROUP
        else -> null
    }

    fun decide(conversation: RetroConversationFacts, rules: List<FilterRule>): Decision {
        skipReason(
            isGroupConversation = conversation.isGroupConversation,
            manuallyFiled = conversation.manuallyFiled,
            currentMailbox = conversation.currentMailbox
        )?.let { return Decision.Skip(it) }

        val verdict = RuleEngine.evaluate(rules, conversation.facts)
        return when {
            // The Locker is filled by hand only, never by a filing pass.
            verdict.mailbox == Mailbox.LOCKER -> Decision.Skip(SkipReason.LOCKER)
            verdict.mailbox == conversation.currentMailbox -> Decision.AlreadyFiled
            else -> Decision.File(verdict)
        }
    }
}
