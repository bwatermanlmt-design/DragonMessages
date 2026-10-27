package org.fossify.messages.filtering

import org.fossify.messages.models.FilterAuditEntry
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/** The observable facts about one incoming message that rules match against. */
data class MessageFacts(
    val sender: String,
    val body: String,
    /**
     * Text recognized on-device from attached images (revision 9 OCR):
     * keyword rules match against it exactly like [body], but it is
     * never stored, never shown, and never logged — it exists only for
     * this one evaluation. Empty unless OCR ran (non-contact sender,
     * image attached, empty/short body). Link rules keep reading [body]
     * only: recognized text must not accidentally trigger link rules.
     */
    val ocrText: String = "",
    val senderInContacts: Boolean,
    val senderTags: Set<String> = emptySet(),
    /**
     * The sender is in the address book under a business label (a Contacts
     * group titled "Businesses" or "Services"): a contact, but a business —
     * its home is Auth & Services, never My Contacts. Only meaningful when
     * [senderInContacts] is true.
     */
    val businessLabeled: Boolean = false,
    /**
     * The sender's display name (the address-book name for a contact, the
     * conversation title for a stored thread), matched by the
     * sender-contains rule condition alongside the raw address.
     */
    val senderName: String = "",
    /**
     * The mailbox Ben filed this sender to by hand, from the sender-level
     * filing record (revision 7): a manual placement belongs to the
     * sender, so it decides the home of every thread from this number —
     * present, future, and recreated after deletion — until he moves the
     * sender again. Null when the sender has no such record. Consulted by
     * [ThreadPrecedence] at the manual tier, above the address book.
     */
    val senderFiledMailbox: String? = null,
    /**
     * The contact's label-mapped home (revision 7): the folder an
     * address-book label mapping assigns to this sender, resolved from
     * the sender's labels by [LabelHomes]. Null when no mapped label
     * applies; [ThreadPrecedence] then falls back to the ordinary
     * contact homes (Auth & Services for businesses, My Contacts for
     * people). Only meaningful when [senderInContacts] is true.
     */
    val labelHome: String? = null,
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
