package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * The starter rule pack, seeded on first run. Built from Ben's real traffic:
 * campaign texts carry a "Stop2End" fingerprint his bank's messages lack, and
 * broker spam keeps arriving addressed to a nonexistent "Gerrit".
 */
object StarterRules {

    const val TAG_CAMPAIGNS = "campaigns"
    const val TAG_APPROVED = "approved"

    fun defaultRules(): List<FilterRule> {
        return listOf(
            FilterRule(
                name = "Campaign fingerprint (Stop2End) to Spam",
                priority = 10,
                keyword = "Stop2End",
                mailbox = Mailbox.SPAM,
                assignTag = TAG_CAMPAIGNS
            ),
            FilterRule(
                name = "Addressed to Gerrit to Quarantine",
                priority = 20,
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
                name = "Contacts to My Contacts",
                priority = 40,
                senderInContacts = true,
                mailbox = Mailbox.CONTACTS
            ),
            FilterRule(
                name = "Approved senders to My Contacts",
                priority = 45,
                senderTag = TAG_APPROVED,
                mailbox = Mailbox.CONTACTS
            ),
            FilterRule(
                name = "Default: unknown senders to Quarantine",
                priority = 50,
                mailbox = Mailbox.QUARANTINE
            )
        )
    }
}
