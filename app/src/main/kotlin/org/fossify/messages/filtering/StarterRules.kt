package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * The starter rule pack, seeded on first run and repaired to the current
 * version by the seed migration (see FilterRuleSeeder). The pack is
 * deliberately generic: contacts are settled first (ThreadPrecedence
 * then routes business-labeled contacts to Auth & Services and makes
 * contacts immune to everything below), then sender class, then the
 * default. Anything person-specific — keywords, tags, blocks — belongs
 * in rules the user creates, never in the shipped pack.
 *
 * Priorities encode the evaluation order: contacts first, sender class
 * after, the default last.
 */
object StarterRules {

    const val TAG_CAMPAIGNS = "campaigns"
    const val TAG_APPROVED = "approved"

    /** Reserved sender tag: a number the user silenced by hand. Notification-only. */
    const val TAG_MUTED = "muted"

    /**
     * Reserved sender tag: a number the user blocked permanently. The tag is the
     * app's own blocked-numbers record — it belongs to the number, survives
     * thread deletion, and (via ThreadPrecedence step 0) files every future
     * message from the number straight to Spam until the user unblocks it.
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

    /**
     * Generic starter rules missing from [existingNames], for the seed
     * repair. Pure function, so the upgrade-safety property is unit
     * testable: it only ever returns rules to ADD, never anything to
     * delete. Rules the user already has — including person-specific
     * rules from earlier packs — are never named here.
     */
    fun missingFrom(existingNames: Set<String>): List<FilterRule> {
        return defaultRules().filter { it.name !in existingNames }
    }
}
