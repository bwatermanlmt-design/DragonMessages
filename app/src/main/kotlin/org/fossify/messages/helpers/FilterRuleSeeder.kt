package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.StarterRules
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * Seeds the starter rule pack the first time the filter engine runs, and
 * repairs it when the pack's version moves.
 *
 * Seed version history:
 *  1 — phases 1–3: content rules outranked contacts; an `approved` sender
 *      tag promoted senders into My Contacts.
 *  2 — revision 4: Ben's final evaluation order (contacts first, content
 *      rules after, sender class last); the `approved` rule is retired.
 *
 * The repair is deliberately narrow: it deletes only rows whose names are
 * known starter-pack names (either version) and re-inserts the current
 * pack. Rules Ben created or renamed himself, his converted blocked
 * keywords, sender tags, and the audit log are never touched.
 */
object FilterRuleSeeder {

    const val CURRENT_SEED_VERSION = 2

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

    /** Replace the starter pack in place, leaving every other rule alone. */
    private fun repairStarterRules(context: Context) {
        val dao = context.filterRulesDB
        dao.getAll()
            .filter { it.name in StarterRules.ALL_STARTER_NAMES }
            .forEach { dao.delete(it) }
        dao.insertAll(StarterRules.defaultRules())
    }
}
