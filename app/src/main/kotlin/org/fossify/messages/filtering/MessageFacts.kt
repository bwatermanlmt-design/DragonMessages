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
     * sender again. Null when the sender has no such record. The arrival
     * path and the retro pass fold this record into the hand-filed fact
     * (see [manuallyFiled]): a sender-level record counts as a hand
     * filing for rule conditions.
     */
    val senderFiledMailbox: String? = null,
    /**
     * The contact's label-mapped home (revision 7): the folder an
     * address-book label mapping assigns to this sender, resolved from
     * the sender's labels by [LabelHomes]. Null when no mapped label
     * applies. Informational since revision 10: placement no longer
     * consults it — a rule targeting a folder is how a label home is
     * expressed. Only meaningful when [senderInContacts] is true.
     */
    val labelHome: String? = null,
    /**
     * Ben has sent a message in this thread (revision 10): the
     * "Have replied to" rule condition reads this fact. It is filled
     * in by the caller that knows the thread — the arrival path and
     * the retro pass — and defaults to false for context-free
     * evaluations, where no thread is in view.
     */
    val repliedTo: Boolean = false,
    /**
     * The thread was filed by hand (revision 10): the "Filed by hand"
     * rule condition reads this fact. A thread counts as hand-filed
     * when its thread-level flag is set OR a sender-level filing
     * record exists for its sender; the caller that knows the thread
     * folds both into this one fact. Defaults to false for
     * context-free evaluations.
     */
    val manuallyFiled: Boolean = false,
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
