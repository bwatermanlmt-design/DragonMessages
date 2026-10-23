package org.fossify.messages.models

import org.fossify.messages.R

/**
 * The mailboxes a conversation can live in. Rules deliver conversations to
 * a mailbox; the folders are organization only — they carry no volume
 * policy. Every folder notifies normally except Spam, which is where the
 * filters send what they catch, and the Locker, which notifies as a
 * content-free warning instead of a preview.
 * Stored as the [Conversation.mailbox] value and in filter rule actions.
 */
object Mailbox {
    /** Whitelisted people: person contacts (not business-labeled). */
    const val CONTACTS = "contacts"

    /** Business-labeled contacts, short codes, and service IDs. */
    const val AUTH = "auth"

    /** Senders who aren't in the address book: "Not in Contacts". */
    const val QUARANTINE = "quarantine"

    /** Filter-caught junk and explicitly blocked senders. Never notifies. */
    const val SPAM = "spam"

    /** Locked area. Assigned manually only; rules never deliver here. */
    const val LOCKER = "locker"

    val ALL = listOf(CONTACTS, AUTH, QUARANTINE, SPAM, LOCKER)

    /** Prefix of a user-created folder's mailbox id: `folder:<folder id>`. */
    const val CUSTOM_FOLDER_PREFIX = "folder:"

    /** The mailbox id of the custom folder with the given folder id. */
    fun customMailboxId(folderId: String): String = CUSTOM_FOLDER_PREFIX + folderId

    /** Whether this mailbox id names a user-created folder. */
    fun isCustomMailbox(mailbox: String): Boolean = mailbox.startsWith(CUSTOM_FOLDER_PREFIX)

    /** The folder id inside a custom mailbox id, or null for system mailboxes. */
    fun folderIdOf(mailbox: String): String? =
        if (isCustomMailbox(mailbox)) mailbox.removePrefix(CUSTOM_FOLDER_PREFIX) else null

    /** Whether this mailbox id is one the app can be filtered to by name alone. */
    fun isSystemMailbox(mailbox: String): Boolean = mailbox in ALL

    /**
     * Mailboxes whose incoming messages arrive silently: only Spam, the
     * filter output. The Locker is not silent either — it announces itself
     * as a warning with no content (see NotificationHelper).
     */
    fun silencesNotifications(mailbox: String): Boolean {
        return mailbox == SPAM
    }

    /** String resource naming the mailbox, for folder rows and list titles. */
    fun displayNameRes(mailbox: String): Int = when (mailbox) {
        CONTACTS -> R.string.my_contacts
        AUTH -> R.string.auth_and_services
        QUARANTINE -> R.string.quarantine
        SPAM -> R.string.spam
        else -> R.string.locker
    }
}
