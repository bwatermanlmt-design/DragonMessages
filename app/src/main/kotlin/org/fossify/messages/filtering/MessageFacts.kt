package org.fossify.messages.filtering

import org.fossify.messages.models.FilterAuditEntry
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/** The observable facts about one incoming message that rules match against. */
data class MessageFacts(
    val sender: String,
    val body: String,
    val senderInContacts: Boolean,
    val senderTags: Set<String> = emptySet(),
)

/** The outcome of evaluating the rule set against a message. */
data class RuleVerdict(
    val mailbox: String,
    val assignTag: String?,
    val matchedRule: FilterRule?,
) {
    /** Name recorded in the audit log for this verdict. */
    val ruleName: String
        get() = matchedRule?.name ?: FilterAuditEntry.DEFAULT_RULE_NAME

    /** Whether the message should be stored without any notification. */
    val silencesNotifications: Boolean
        get() = Mailbox.silencesNotifications(mailbox)
}
