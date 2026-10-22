package org.fossify.messages.filtering

import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadPrecedenceTest {

    private fun resolve(
        current: String?,
        verdict: String,
        engaged: Boolean = false,
        manuallyFiled: Boolean = false,
        senderBlocked: Boolean = false
    ) = ThreadPrecedence.resolve(
        currentMailbox = current,
        verdictMailbox = verdict,
        engaged = engaged,
        manuallyFiled = manuallyFiled,
        senderBlocked = senderBlocked
    )

    // region engaged threads cannot be demoted

    @Test
    fun `engaged thread keeps its mailbox against a spam verdict`() {
        assertEquals(
            Mailbox.CONTACTS,
            resolve(current = Mailbox.CONTACTS, verdict = Mailbox.SPAM, engaged = true)
        )
    }

    @Test
    fun `engaged thread keeps its mailbox against a quarantine verdict`() {
        assertEquals(
            Mailbox.CONTACTS,
            resolve(current = Mailbox.CONTACTS, verdict = Mailbox.QUARANTINE, engaged = true)
        )
    }

    @Test
    fun `engaged thread may still be upgraded by a contacts verdict`() {
        assertEquals(
            Mailbox.CONTACTS,
            resolve(current = Mailbox.QUARANTINE, verdict = Mailbox.CONTACTS, engaged = true)
        )
    }

    @Test
    fun `engaged thread in spam stays in spam on an agreeing verdict`() {
        assertEquals(
            Mailbox.SPAM,
            resolve(current = Mailbox.SPAM, verdict = Mailbox.SPAM, engaged = true)
        )
    }

    // endregion

    // region hand-filed and the Locker

    @Test
    fun `manually filed thread is unchanged by any verdict`() {
        assertEquals(
            Mailbox.AUTH,
            resolve(current = Mailbox.AUTH, verdict = Mailbox.SPAM, manuallyFiled = true)
        )
        assertEquals(
            Mailbox.AUTH,
            resolve(current = Mailbox.AUTH, verdict = Mailbox.CONTACTS, manuallyFiled = true)
        )
    }

    @Test
    fun `locker thread keeps its mailbox`() {
        assertEquals(
            Mailbox.LOCKER,
            resolve(current = Mailbox.LOCKER, verdict = Mailbox.SPAM, engaged = true)
        )
    }

    // endregion

    // region explicit blocks win over everything

    @Test
    fun `blocked sender files to spam regardless of engagement`() {
        assertEquals(
            Mailbox.SPAM,
            resolve(
                current = Mailbox.CONTACTS, verdict = Mailbox.CONTACTS,
                engaged = true, senderBlocked = true
            )
        )
    }

    @Test
    fun `blocked sender files to spam regardless of manual filing`() {
        assertEquals(
            Mailbox.SPAM,
            resolve(
                current = Mailbox.CONTACTS, verdict = Mailbox.QUARANTINE,
                manuallyFiled = true, senderBlocked = true
            )
        )
    }

    // endregion

    // region new and untouched threads take the verdict

    @Test
    fun `new thread takes a spam verdict`() {
        assertEquals(Mailbox.SPAM, resolve(current = null, verdict = Mailbox.SPAM))
    }

    @Test
    fun `new thread takes a contacts verdict`() {
        assertEquals(Mailbox.CONTACTS, resolve(current = null, verdict = Mailbox.CONTACTS))
    }

    @Test
    fun `unengaged thread is re-filed by the verdict`() {
        assertEquals(
            Mailbox.SPAM,
            resolve(current = Mailbox.CONTACTS, verdict = Mailbox.SPAM, engaged = false)
        )
    }

    // endregion
}
