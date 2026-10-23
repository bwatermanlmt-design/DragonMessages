package org.fossify.messages.filtering

import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sticky-filings merge: a provider-built conversation must never reset
 * the app-side state of the stored row for the same thread. Before this
 * merge was applied on every refresh path, browsing a folder re-registered
 * threads stored in other folders with factory defaults and their filings
 * (and Spam placements) evaporated into Contacts.
 */
class ConversationMergeTest {

    private fun conversation(
        threadId: Long = 42L,
        snippet: String = "stored snippet",
        date: Int = 100,
        read: Boolean = true,
        title: String = "Stored Title",
        mailbox: String = Mailbox.CONTACTS,
        manuallyFiled: Boolean = false,
        muted: Boolean = false,
        usesCustomTitle: Boolean = false
    ) = Conversation(
        threadId = threadId,
        snippet = snippet,
        date = date,
        read = read,
        title = title,
        photoUri = "",
        isGroupConversation = false,
        phoneNumber = "+14805550137",
        usesCustomTitle = usesCustomTitle,
        mailbox = mailbox,
        manuallyFiled = manuallyFiled,
        muted = muted
    )

    @Test
    fun `no stored row yields the provider conversation untouched`() {
        val provider = conversation()
        assertSame(provider, Conversation.mergeProviderConversation(null, provider))
    }

    @Test
    fun `mailbox filing survives a provider refresh`() {
        val stored = conversation(mailbox = Mailbox.QUARANTINE)
        val provider = conversation(mailbox = Mailbox.CONTACTS, snippet = "new message", date = 200)

        val merged = Conversation.mergeProviderConversation(stored, provider)

        assertEquals(Mailbox.QUARANTINE, merged.mailbox)
        // …while the provider's fresh message facts still win.
        assertEquals("new message", merged.snippet)
        assertEquals(200, merged.date)
    }

    @Test
    fun `spam filing survives a provider refresh`() {
        val stored = conversation(mailbox = Mailbox.SPAM, manuallyFiled = true)
        val provider = conversation(mailbox = Mailbox.CONTACTS)

        val merged = Conversation.mergeProviderConversation(stored, provider)

        assertEquals(Mailbox.SPAM, merged.mailbox)
    }

    @Test
    fun `locker filing survives a provider refresh`() {
        val stored = conversation(mailbox = Mailbox.LOCKER, manuallyFiled = true)
        val provider = conversation(mailbox = Mailbox.CONTACTS)

        val merged = Conversation.mergeProviderConversation(stored, provider)

        assertEquals(Mailbox.LOCKER, merged.mailbox)
    }

    @Test
    fun `hand-filed mark and mute survive a provider refresh`() {
        val stored = conversation(manuallyFiled = true, muted = true)
        val provider = conversation(manuallyFiled = false, muted = false)

        val merged = Conversation.mergeProviderConversation(stored, provider)

        assertTrue(merged.manuallyFiled)
        assertTrue(merged.muted)
    }

    @Test
    fun `provider state is not resurrected when the stored row cleared it`() {
        val stored = conversation(manuallyFiled = false, muted = false)
        val provider = conversation(manuallyFiled = true, muted = true)

        val merged = Conversation.mergeProviderConversation(stored, provider)

        assertFalse(merged.manuallyFiled)
        assertFalse(merged.muted)
    }

    @Test
    fun `custom title survives, generated title does not override the provider`() {
        val storedCustom = conversation(title = "Ben's name for it", usesCustomTitle = true)
        val provider = conversation(title = "Provider Title")

        val mergedCustom = Conversation.mergeProviderConversation(storedCustom, provider)
        assertEquals("Ben's name for it", mergedCustom.title)
        assertTrue(mergedCustom.usesCustomTitle)

        val storedGenerated = conversation(title = "Stale Provider Title", usesCustomTitle = false)
        val mergedGenerated = Conversation.mergeProviderConversation(storedGenerated, provider)
        assertEquals("Provider Title", mergedGenerated.title)
        assertFalse(mergedGenerated.usesCustomTitle)
    }
}
