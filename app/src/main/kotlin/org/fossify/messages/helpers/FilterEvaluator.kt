package org.fossify.messages.helpers

import android.content.Context
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.filterAuditDB
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.extensions.senderTagsDB
import org.fossify.messages.filtering.MessageFacts
import org.fossify.messages.filtering.RuleEngine
import org.fossify.messages.filtering.RuleVerdict
import org.fossify.messages.models.FilterAuditEntry
import org.fossify.messages.models.SenderTags

/**
 * Android-side wrapper around the pure [RuleEngine]: gathers the message
 * facts (contacts lookup, stored sender tags), evaluates the enabled rules,
 * records the verdict in the audit log and applies any tag assignment.
 * Callers must already be on a background thread.
 */
object FilterEvaluator {

    fun evaluate(context: Context, sender: String, body: String, channel: String): RuleVerdict {
        FilterRuleSeeder.seedIfNeeded(context)

        val facts = MessageFacts(
            sender = sender,
            body = body,
            senderInContacts = isSenderInContacts(context, sender),
            senderTags = context.senderTagsDB.getForNumber(sender)?.tagList?.toSet() ?: emptySet()
        )

        val verdict = RuleEngine.evaluate(context.filterRulesDB.getAllEnabled(), facts)

        runCatching {
            context.filterAuditDB.insert(
                FilterAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    sender = sender,
                    ruleName = verdict.ruleName,
                    mailbox = verdict.mailbox,
                    channel = channel
                )
            )
        }

        verdict.assignTag?.let { tag ->
            runCatching { assignTag(context, sender, tag) }
        }

        return verdict
    }

    private fun isSenderInContacts(context: Context, sender: String): Boolean {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        val result = SimpleContactsHelper(context).existsSync(sender, privateCursor)
        return result != ContactLookupResult.NotFound
    }

    private fun assignTag(context: Context, sender: String, tag: String) {
        val existing = context.senderTagsDB.getForNumber(sender)
        val merged = (existing?.tagList ?: emptyList()) + tag
        context.senderTagsDB.insertOrUpdate(SenderTags.of(sender, merged))
    }
}
