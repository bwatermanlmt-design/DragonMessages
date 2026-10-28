package org.fossify.messages.filtering

import org.fossify.messages.models.FilterAuditEntry

/**
 * Per-thread filing reason (revision 10): why a thread sits where it
 * sits, from the latest audit entry for its sender. Pure, so the
 * hand-moved rule is unit-testable: a hand-moved thread whose current
 * mailbox differs from the latest audit entry's mailbox was NOT put
 * there by the rule — the stale rule name is never reported. Group
 * threads use the conversation's recorded audit entry, which carries
 * the group marker as a rule-name suffix (stripped for display).
 */
object FilingReason {

    sealed interface Reason {
        /** Moved by hand (or by a block), not by any rule. */
        data object HandMoved : Reason

        /** No audit entry exists for this thread's sender. */
        data object NoRecord : Reason

        /** The rule (or the default) that filed it, and when. */
        data class Filed(val ruleName: String, val mailbox: String, val timestamp: Long) : Reason
    }

    fun reason(
        manuallyFiled: Boolean,
        currentMailbox: String,
        entry: FilterAuditEntry?,
    ): Reason {
        if (manuallyFiled && (entry == null || entry.mailbox != currentMailbox)) {
            return Reason.HandMoved
        }
        if (entry == null) return Reason.NoRecord
        return Reason.Filed(
            ruleName = entry.ruleName
                .removeSuffix(FilterAuditEntry.RETRO_GROUP_SUFFIX)
                .removeSuffix(FilterAuditEntry.PRECEDENCE_KEPT_SUFFIX),
            mailbox = entry.mailbox,
            timestamp = entry.timestamp
        )
    }
}
