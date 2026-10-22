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
    /**
     * The sender is in the address book under a business label (a Contacts
     * group titled "Businesses" or "Services"): a contact, but a business —
     * its home is Auth & Services, never My Contacts. Only meaningful when
     * [senderInContacts] is true.
     */
    val businessLabeled: Boolean = false,
)

/** The outcome of evaluating the rule set against a message. */
data class RuleVerdict(
    val mailbox: String,
    val assignTag: String?,
    val matchedRule: FilterRule?,
    /**
     * A hand-set mute (conversation, number, or folder) suppresses this
     * delivery's notification. Muting never changes filing, storage, or
     * unread state — it only takes the buzz away.
     */
    val notificationMuted: Boolean = false,
) {
    /** Name recorded in the audit log for this verdict. */
    val ruleName: String
        get() = matchedRule?.name ?: FilterAuditEntry.DEFAULT_RULE_NAME

    /** Whether the delivery is a filter catch that arrives silently (Spam). */
    val silencesNotifications: Boolean
        get() = Mailbox.silencesNotifications(mailbox)

    /** Whether a normal notification may be shown for this delivery. */
    val shouldNotify: Boolean
        get() = !silencesNotifications && !notificationMuted
}
