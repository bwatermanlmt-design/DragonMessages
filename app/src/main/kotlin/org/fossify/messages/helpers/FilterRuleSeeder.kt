package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.StarterRules
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * Seeds the starter rule pack the first time the filter engine runs, and
 * adds any missing generic rules when the pack's version moves. The
 * repair is additive-only: it never deletes a rule. Rules the user
 * created or renamed, converted blocked keywords, sender tags, and the
 * audit log are never touched.
 *
 * Seed version history:
 *  1 — phases 1–3: content rules outranked contacts; an `approved` sender
 *      tag promoted senders into My Contacts.
 *  2 — revision 4: final evaluation order (contacts first, content
 *      rules after, sender class last); the `approved` rule is retired.
 *  3 — publish prep: the starter pack drops person-specific rules
 *      (the old Stop2End/Gerrit keyword rules); repair inserts missing
 *      generics only and deletes nothing, so existing users keep
 *      whatever rules they already have.
 */
object FilterRuleSeeder {

    const val CURRENT_SEED_VERSION = 3

    // Converted blocked keywords outrank everything else: before the rule
    // engine existed, a matching keyword dropped the message outright.
    private const val CONVERTED_KEYWORD_PRIORITY = 5

    fun seedIfNeeded(context: Context) {
        val config = context.config
        if (config.filterRulesSeedVersion >= CURRENT_SEED_VERSION) {
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
        } else if (config.filterRulesSeedVersion < CURRENT_SEED_VERSION) {
            repairStarterRules(context)
        }

        config.filterRulesSeedVersion = CURRENT_SEED_VERSION
        config.filterRulesSeeded = true
    }

    /**
     * Add any generic starter rules the user is missing, deleting
     * nothing. In particular, person-specific rules from earlier packs
     * (Stop2End, Gerrit) stay exactly where the user left them.
     */
    private fun repairStarterRules(context: Context) {
        val dao = context.filterRulesDB
        val existingNames = dao.getAll().map { it.name }.toSet()
        dao.insertAll(StarterRules.missingFrom(existingNames))
    }
}
