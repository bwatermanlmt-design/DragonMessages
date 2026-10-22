package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.StarterRules
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/** Seeds the starter rule pack the first time the filter engine runs. */
object FilterRuleSeeder {

    // Converted blocked keywords outrank everything else: before the rule
    // engine existed, a matching keyword dropped the message outright.
    private const val CONVERTED_KEYWORD_PRIORITY = 5

    fun seedIfNeeded(context: Context) {
        val config = context.config
        if (config.filterRulesSeeded) {
            return
        }

        if (context.filterRulesDB.getCount() == 0) {
            val rules = ArrayList<FilterRule>()

            // Keep the user's existing blocked keywords working: each one
            // becomes a rule delivering matching messages to Spam.
            config.blockedKeywords.sorted().forEach { keyword ->
                rules += FilterRule(
                    name = "Blocked keyword: $keyword",
                    priority = CONVERTED_KEYWORD_PRIORITY,
                    keyword = keyword,
                    mailbox = Mailbox.SPAM
                )
            }

            rules += StarterRules.defaultRules()
            context.filterRulesDB.insertAll(rules)
        }

        config.filterRulesSeeded = true
    }
}
