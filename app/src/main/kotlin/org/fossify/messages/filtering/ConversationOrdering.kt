package org.fossify.messages.filtering

import org.fossify.messages.models.Conversation

/**
 * The conversation list order (revision 9, pins ordered in revision 10):
 * pins on top in the user's order, then — only when family-first sorting
 * is on — family threads, then everything else, newest first within each
 * section. With family-first off, the order is pins (user order), then
 * date. Pure and unit-tested: the activity only feeds it lists.
 */
object ConversationOrdering {

    fun orderConversations(
        conversations: List<Conversation>,
        pinnedIds: List<String>,
        familyThreadIds: Set<Long>,
        familyFirst: Boolean
    ): List<Conversation> {
        val pinIndex = pinnedIds.withIndex().associate { it.value to it.index }
        return conversations.sortedWith(
            compareBy<Conversation> { pinIndex[it.threadId.toString()] ?: Int.MAX_VALUE }
                .thenByDescending { familyFirst && it.threadId in familyThreadIds }
                .thenByDescending { it.date }
        )
    }

    /**
     * The position of the first rest item in an [orderConversations]
     * list — the separator sits above it — or -1 when no separator
     * applies: family-first off, no family section, or no rest section.
     * Pins count as the upper section alongside family: the divider
     * separates (pinned + family) from everyone else.
     */
    fun separatorIndex(
        ordered: List<Conversation>,
        pinnedIds: List<String>,
        familyThreadIds: Set<Long>,
        familyFirst: Boolean
    ): Int {
        if (!familyFirst) return -1
        val firstRest = ordered.indexOfFirst {
            it.threadId.toString() !in pinnedIds && it.threadId !in familyThreadIds
        }
        if (firstRest <= 0) return -1
        return firstRest
    }
}
