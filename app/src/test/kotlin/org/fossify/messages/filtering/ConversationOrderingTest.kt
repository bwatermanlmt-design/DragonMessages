package org.fossify.messages.filtering

import org.fossify.messages.models.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

/** The revision 9 list order, with revision 10 pin ordering: pins in the user's order, then family (when family-first is on), then date. */
class ConversationOrderingTest {

    private fun conv(threadId: Long, date: Int, phone: String = "+14805550137") = Conversation(
        threadId = threadId,
        snippet = "",
        date = date,
        read = true,
        title = "",
        photoUri = "",
        isGroupConversation = false,
        phoneNumber = phone
    )

    @Test
    fun `pins first, then date descending`() {
        val ordered = ConversationOrdering.orderConversations(
            listOf(conv(1, 300), conv(2, 100), conv(3, 200)),
            pinnedIds = listOf("2"),
            familyThreadIds = emptySet(),
            familyFirst = false
        )
        assertEquals(listOf(2L, 1L, 3L), ordered.map { it.threadId })
    }

    @Test
    fun `pins render in user order, not date order`() {
        val ordered = ConversationOrdering.orderConversations(
            listOf(conv(1, 300), conv(2, 100), conv(3, 200), conv(4, 400)),
            pinnedIds = listOf("3", "1"),
            familyThreadIds = emptySet(),
            familyFirst = false
        )
        assertEquals(listOf(3L, 1L, 4L, 2L), ordered.map { it.threadId })
    }

    @Test
    fun `family first puts family between pins and the rest`() {
        val ordered = ConversationOrdering.orderConversations(
            listOf(conv(1, 300), conv(2, 100), conv(3, 200), conv(4, 400)),
            pinnedIds = listOf("2"),
            familyThreadIds = setOf(3L),
            familyFirst = true
        )
        assertEquals(listOf(2L, 3L, 4L, 1L), ordered.map { it.threadId })
    }

    @Test
    fun `family first off leaves family intermingled by date`() {
        val ordered = ConversationOrdering.orderConversations(
            listOf(conv(1, 300), conv(3, 200)),
            pinnedIds = emptyList(),
            familyThreadIds = setOf(3L),
            familyFirst = false
        )
        assertEquals(listOf(1L, 3L), ordered.map { it.threadId })
    }

    @Test
    fun `date descends within each section`() {
        val ordered = ConversationOrdering.orderConversations(
            listOf(conv(1, 100), conv(2, 300), conv(3, 200), conv(4, 400)),
            pinnedIds = emptyList(),
            familyThreadIds = setOf(1L, 2L),
            familyFirst = true
        )
        assertEquals(listOf(2L, 1L, 4L, 3L), ordered.map { it.threadId })
    }

    @Test
    fun `separator sits above the first rest item`() {
        val ordered = ConversationOrdering.orderConversations(
            listOf(conv(1, 300), conv(2, 100), conv(3, 200)),
            pinnedIds = listOf("2"),
            familyThreadIds = setOf(3L),
            familyFirst = true
        )
        assertEquals(2, ConversationOrdering.separatorIndex(ordered, listOf("2"), setOf(3L), true))
    }

    @Test
    fun `separator absent when family first is off`() {
        val ordered = listOf(conv(1, 300), conv(3, 200))
        assertEquals(-1, ConversationOrdering.separatorIndex(ordered, emptyList(), setOf(3L), false))
    }

    @Test
    fun `separator absent with no family section`() {
        val ordered = listOf(conv(1, 300), conv(2, 100))
        assertEquals(-1, ConversationOrdering.separatorIndex(ordered, emptyList(), emptySet(), true))
    }

    @Test
    fun `separator absent with no rest section`() {
        val ordered = listOf(conv(1, 300), conv(2, 100))
        assertEquals(-1, ConversationOrdering.separatorIndex(ordered, listOf("1"), setOf(2L), true))
    }
}
