package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * The starter rule pack, seeded on first run and repaired to the current
 * version by the seed migration (see FilterRuleSeeder). Built from Ben's
 * real traffic: campaign texts carry a "Stop2End" fingerprint his bank's
 * messages lack, and broker spam keeps arriving addressed to a nonexistent
 * "Gerrit".
 *
 * Priorities encode Ben's evaluation order: contacts are settled first
 * (ThreadPrecedence then routes business-labeled contacts to Auth &
 * Services and makes contacts immune to everything below), then content
 * rules, then sender class, then the default. The old `approved`-tag rule
 * is retired — placement authority lives in the address book alone.
 */
object StarterRules {

    const val TAG_CAMPAIGNS = "campaigns"
    const val TAG_APPROVED = "approved"

    /** Reserved sender tag: a number Ben silenced by hand. Notification-only. */
    const val TAG_MUTED = "muted"

    /**
     * Reserved sender tag: a number Ben blocked permanently. The tag is the
     * app's own blocked-numbers record — it belongs to the number, survives
     * thread deletion, and (via ThreadPrecedence step 0) files every future
     * message from the number straight to Spam until Ben unblocks it.
     */
    const val TAG_BLOCKED = "blocked"

    fun defaultRules(): List<FilterRule> {
        return listOf(
            FilterRule(
                name = "Contacts to My Contacts",
                priority = 10,
                senderInContacts = true,
                mailbox = Mailbox.CONTACTS
            ),
            FilterRule(
                name = "Campaign fingerprint (Stop2End) to Spam",
                priority = 20,
                keyword = "Stop2End",
                mailbox = Mailbox.SPAM,
                assignTag = TAG_CAMPAIGNS
            ),
            FilterRule(
                name = "Addressed to Gerrit to Not in Contacts",
                priority = 25,
                keyword = "Gerrit",
                mailbox = Mailbox.QUARANTINE
            ),
            FilterRule(
                name = "Short codes to Auth & Services",
                priority = 30,
                senderClass = SenderClassifier.SHORT_CODE,
                mailbox = Mailbox.AUTH
            ),
            FilterRule(
                name = "Service IDs to Auth & Services",
                priority = 32,
                senderClass = SenderClassifier.ALPHANUMERIC,
                mailbox = Mailbox.AUTH
            ),
            FilterRule(
                name = "Default: unknown senders to Not in Contacts",
                priority = 50,
                mailbox = Mailbox.QUARANTINE
            )
        )
    }

    /** Rule names the seed migration recognises as its own, across versions. */
    val ALL_STARTER_NAMES: Set<String> = defaultRules().map { it.name }.toSet() + setOf(
        // Seed version 1 (phases 1–3): content rules outranked contacts and
        // an `approved` tag could promote a sender into My Contacts.
        "Addressed to Gerrit to Quarantine",
        "Approved senders to My Contacts",
        "Default: unknown senders to Quarantine"
    )
}
