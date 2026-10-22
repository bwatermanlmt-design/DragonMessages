package org.fossify.messages.models

import org.fossify.messages.R

/**
 * The mailboxes a conversation can live in. Rules deliver conversations to a
 * mailbox; notification behaviour follows the mailbox, not the rule.
 * Stored as the [Conversation.mailbox] value and in filter rule actions.
 */
object Mailbox {
    /** Whitelisted people: contacts and approved senders. Rings normally. */
    const val CONTACTS = "contacts"

    /** Short codes and services: bank alerts, 2FA codes, delivery notices. */
    const val AUTH = "auth"

    /** Unknown and undecided. Silent review queue. */
    const val QUARANTINE = "quarantine"

    /** Rule-matched junk and explicitly blocked senders. Never notifies. */
    const val SPAM = "spam"

    /** Locked area. Assigned manually only; rules never deliver here. */
    const val LOCKER = "locker"

    val ALL = listOf(CONTACTS, AUTH, QUARANTINE, SPAM, LOCKER)

    /** Mailboxes whose incoming messages are stored without any notification. */
    fun silencesNotifications(mailbox: String): Boolean {
        return mailbox == QUARANTINE || mailbox == SPAM || mailbox == LOCKER
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
