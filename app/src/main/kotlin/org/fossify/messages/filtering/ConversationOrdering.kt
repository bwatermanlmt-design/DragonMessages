package org.fossify.messages.filtering

import org.fossify.messages.models.Conversation

/**
 * The conversation list order (revision 9): pins on top for the
 * hand-picked few, then — only when family-first sorting is on — family
 * threads, then everything else, newest first within each section. With
 * family-first off, the order is exactly the old one: pins, then date.
 * Pure and unit-tested: the activity only feeds it lists and sets.
 */
object ConversationOrdering {

    fun orderConversations(
        conversations: List<Conversation>,
        pinnedIds: Set<String>,
        familyThreadIds: Set<Long>,
        familyFirst: Boolean
    ): List<Conversation> {
        return conversations.sortedWith(
            compareByDescending<Conversation> { it.threadId.toString() in pinnedIds }
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
        pinnedIds: Set<String>,
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
