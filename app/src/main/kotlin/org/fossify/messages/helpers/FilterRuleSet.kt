package org.fossify.messages.helpers

import kotlinx.serialization.Serializable
import org.fossify.messages.filtering.RuleEngine
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Folder
import org.fossify.messages.models.LabelMapping
import org.fossify.messages.models.SenderFiling
import org.fossify.messages.models.SenderTags

/**
 * The portable rule-set file: every filter rule plus all sender tags in one
 * human-readable JSON document, moved through the system document picker.
 * A null condition means "does not constrain the match".
 *
 * Revision 9 additions (all optional, defaulted — so revision 8 files still
 * parse, and the version stays 1): multi-keyword lists, the condition mode,
 * the regex flag, and — for migration between installs — folders, sender
 * filings, and label mappings. The importer ignores unknown keys, so a file
 * with these fields still opens on older builds only if those builds also
 * ignore them; forward migration (old file into rev9) is the supported path.
 */
@Serializable
data class FilterRuleSet(
    val version: Int = CURRENT_VERSION,
    val rules: List<FilterRuleDto> = emptyList(),
    val senderTags: Map<String, List<String>> = emptyMap(),
    val folders: List<FolderDto> = emptyList(),
    val senderFilings: Map<String, String> = emptyMap(),
    val labelMappings: List<LabelMappingDto> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class FilterRuleDto(
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val senderExact: String? = null,
    val senderPattern: String? = null,
    val senderContains: String? = null,
    val senderInContacts: Boolean? = null,
    val senderTag: String? = null,
    val keyword: String? = null,
    val keywords: List<String> = emptyList(),
    val conditionMode: String = RuleEngine.CONDITION_MODE_ALL,
    val keywordRegex: Boolean = false,
    val containsLink: Boolean? = null,
    val senderClass: String? = null,
    val repliedTo: Boolean? = null,
    val manuallyFiled: Boolean? = null,
    val mailbox: String,
    val assignTag: String? = null,
    val isMacro: Boolean = false,
    val macroAutomatic: Boolean = false,
)

/** One user folder, portable across installs (see FilterRuleSet). */
@Serializable
data class FolderDto(
    val id: String,
    val name: String,
    val color: Int,
    val sortOrder: Int,
    val parentId: String? = null,
    val image: String? = null,
)

/** One address-book label → folder mapping, portable across installs. */
@Serializable
data class LabelMappingDto(
    val label: String,
    val mailbox: String,
    val sortOrder: Int,
)

fun FilterRule.toDto(): FilterRuleDto {
    return FilterRuleDto(
        name = name,
        enabled = enabled,
        priority = priority,
        senderExact = senderExact,
        senderPattern = senderPattern,
        senderContains = senderContains,
        senderInContacts = senderInContacts,
        senderTag = senderTag,
        keyword = keyword,
        keywords = RuleEngine.effectiveKeywords(this),
        conditionMode = conditionMode,
        keywordRegex = keywordRegex,
        containsLink = containsLink,
        senderClass = senderClass,
        repliedTo = repliedTo,
        manuallyFiled = manuallyFiled,
        mailbox = mailbox,
        assignTag = assignTag,
        isMacro = isMacro,
        macroAutomatic = macroAutomatic
    )
}

fun FilterRuleDto.toEntity(): FilterRule {
    return FilterRule(
        name = name,
        enabled = enabled,
        priority = priority,
        senderExact = senderExact,
        senderPattern = senderPattern,
        senderContains = senderContains,
        senderInContacts = senderInContacts,
        senderTag = senderTag,
        keyword = keywords.firstOrNull() ?: keyword,
        keywordsJson = if (keywords.isNotEmpty()) {
            RuleEngine.keywordsToJson(keywords)
        } else {
            null
        },
        conditionMode = conditionMode,
        keywordRegex = keywordRegex,
        containsLink = containsLink,
        senderClass = senderClass,
        repliedTo = repliedTo,
        manuallyFiled = manuallyFiled,
        mailbox = mailbox,
        assignTag = assignTag,
        isMacro = isMacro,
        macroAutomatic = macroAutomatic
    )
}

fun Folder.toDto(): FolderDto {
    return FolderDto(
        id = id,
        name = name,
        color = color,
        sortOrder = sortOrder,
        parentId = parentId,
        image = image
    )
}

fun FolderDto.toEntity(): Folder {
    return Folder(
        id = id,
        name = name,
        color = color,
        sortOrder = sortOrder,
        parentId = parentId,
        image = image
    )
}

fun LabelMapping.toDto(): LabelMappingDto {
    return LabelMappingDto(
        label = label,
        mailbox = mailbox,
        sortOrder = sortOrder
    )
}

fun LabelMappingDto.toEntity(): LabelMapping {
    return LabelMapping(
        label = label,
        mailbox = mailbox,
        sortOrder = sortOrder
    )
}

fun List<FilterRule>.toRuleSet(
    senderTags: List<SenderTags>,
    folders: List<Folder> = emptyList(),
    senderFilings: List<SenderFiling> = emptyList(),
    labelMappings: List<LabelMapping> = emptyList(),
): FilterRuleSet {
    return FilterRuleSet(
        rules = map { it.toDto() },
        senderTags = senderTags.associate { it.number to it.tagList },
        folders = folders.map { it.toDto() },
        senderFilings = senderFilings.associate { it.number to it.mailbox },
        labelMappings = labelMappings.map { it.toDto() }
    )
}
