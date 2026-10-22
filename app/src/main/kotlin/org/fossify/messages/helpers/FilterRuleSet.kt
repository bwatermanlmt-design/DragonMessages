package org.fossify.messages.helpers

import kotlinx.serialization.Serializable
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.SenderTags

/**
 * The portable rule-set file: every filter rule plus all sender tags in one
 * human-readable JSON document, moved through the system document picker.
 * A null condition means "does not constrain the match".
 */
@Serializable
data class FilterRuleSet(
    val version: Int = CURRENT_VERSION,
    val rules: List<FilterRuleDto> = emptyList(),
    val senderTags: Map<String, List<String>> = emptyMap(),
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
    val senderInContacts: Boolean? = null,
    val senderTag: String? = null,
    val keyword: String? = null,
    val containsLink: Boolean? = null,
    val senderClass: String? = null,
    val mailbox: String,
    val assignTag: String? = null,
)

fun FilterRule.toDto(): FilterRuleDto {
    return FilterRuleDto(
        name = name,
        enabled = enabled,
        priority = priority,
        senderExact = senderExact,
        senderPattern = senderPattern,
        senderInContacts = senderInContacts,
        senderTag = senderTag,
        keyword = keyword,
        containsLink = containsLink,
        senderClass = senderClass,
        mailbox = mailbox,
        assignTag = assignTag
    )
}

fun FilterRuleDto.toEntity(): FilterRule {
    return FilterRule(
        name = name,
        enabled = enabled,
        priority = priority,
        senderExact = senderExact,
        senderPattern = senderPattern,
        senderInContacts = senderInContacts,
        senderTag = senderTag,
        keyword = keyword,
        containsLink = containsLink,
        senderClass = senderClass,
        mailbox = mailbox,
        assignTag = assignTag
    )
}

fun List<FilterRule>.toRuleSet(senderTags: List<SenderTags>): FilterRuleSet {
    return FilterRuleSet(
        rules = map { it.toDto() },
        senderTags = senderTags.associate { it.number to it.tagList }
    )
}
