package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.extensions.foldersDB
import org.fossify.messages.extensions.senderTagsDB
import org.fossify.messages.models.SenderTags

/**
 * The single code path for rule-set export and import, used by both the
 * Settings rows and the rules screen — so the two entrances cannot
 * diverge. Import replaces: the file's rules and sender tags become the
 * whole set (the long-standing behavior). Folders, sender filings, and
 * label mappings are merged, never wiped, by an import: folders and
 * filings upsert by id/number, and mappings are replaced ONLY when the
 * file actually carries mappings (an empty list means "no mappings in
 * this file", not "delete Ben's mappings"). A shared rule file without
 * folders must never eat Ben's folders.
 *
 * Callers must already be on a background thread.
 */
object RuleSetStore {

    /** Everything exportable, as one rule-set document. */
    fun buildRuleSet(context: Context): FilterRuleSet {
        return context.filterRulesDB.getAll().toRuleSet(
            senderTags = context.senderTagsDB.getAll(),
            folders = try {
                context.foldersDB.getAllFolders()
            } catch (e: Exception) {
                emptyList()
            },
            senderFilings = try {
                context.foldersDB.getAllSenderFilings()
            } catch (e: Exception) {
                emptyList()
            },
            labelMappings = try {
                context.foldersDB.getAllLabelMappings()
            } catch (e: Exception) {
                emptyList()
            }
        )
    }

    /** Import: replace rules and tags wholesale; merge folders, filings, mappings (see above). */
    fun replaceAll(context: Context, ruleSet: FilterRuleSet) {
        context.filterRulesDB.deleteAll()
        context.filterRulesDB.insertAll(ruleSet.rules.map { it.toEntity() })
        context.senderTagsDB.deleteAll()
        ruleSet.senderTags.forEach { (number, tags) ->
            context.senderTagsDB.insertOrUpdate(SenderTags.of(number, tags))
        }
        if (ruleSet.folders.isNotEmpty()) {
            ruleSet.folders.forEach { folder ->
                context.foldersDB.insertOrUpdateFolder(folder.toEntity())
            }
        }
        if (ruleSet.senderFilings.isNotEmpty()) {
            ruleSet.senderFilings.forEach { (number, mailbox) ->
                context.foldersDB.insertOrUpdateSenderFiling(
                    org.fossify.messages.models.SenderFiling(number = number, mailbox = mailbox)
                )
            }
        }
        if (ruleSet.labelMappings.isNotEmpty()) {
            context.foldersDB.deleteAllLabelMappings()
            ruleSet.labelMappings.forEach { mapping ->
                context.foldersDB.insertLabelMapping(mapping.toEntity())
            }
        }
    }
}
