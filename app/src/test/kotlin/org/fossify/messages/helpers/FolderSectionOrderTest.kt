package org.fossify.messages.helpers

import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSectionOrderTest {

    private val customs = listOf("folder:a", "folder:b")

    @Test
    fun defaultOrderIsContactsQuarantineAuthFoldersLocker() {
        assertEquals(
            listOf(
                Mailbox.CONTACTS, Mailbox.QUARANTINE, Mailbox.AUTH,
                "folder:a", "folder:b", Mailbox.LOCKER
            ),
            FolderSectionOrder.defaultOrder(customs)
        )
    }

    @Test
    fun emptyStoredOrderMeansDefault() {
        assertEquals(
            FolderSectionOrder.defaultOrder(customs),
            FolderSectionOrder.resolveOrder(emptyList(), customs)
        )
    }

    @Test
    fun storedOrderIsRespected() {
        val stored = listOf(
            Mailbox.LOCKER, Mailbox.AUTH, "folder:b",
            Mailbox.CONTACTS, Mailbox.QUARANTINE, "folder:a"
        )
        assertEquals(stored, FolderSectionOrder.resolveOrder(stored, customs))
    }

    @Test
    fun newFolderSlotsIntoItsDefaultPosition() {
        // Ben reordered before creating folder:c; the new folder lands
        // where the default puts it — before the Locker — not at an end.
        val stored = listOf(
            Mailbox.AUTH, Mailbox.CONTACTS, Mailbox.QUARANTINE,
            "folder:a", "folder:b", Mailbox.LOCKER
        )
        assertEquals(
            listOf(
                Mailbox.AUTH, Mailbox.CONTACTS, Mailbox.QUARANTINE,
                "folder:a", "folder:b", "folder:c", Mailbox.LOCKER
            ),
            FolderSectionOrder.resolveOrder(stored, customs + "folder:c")
        )
    }

    @Test
    fun storedEntriesForDeletedFoldersAreDropped() {
        val stored = listOf(
            Mailbox.CONTACTS, "folder:gone", Mailbox.QUARANTINE,
            Mailbox.AUTH, Mailbox.LOCKER
        )
        assertEquals(
            listOf(
                Mailbox.CONTACTS, Mailbox.QUARANTINE, Mailbox.AUTH, Mailbox.LOCKER
            ),
            FolderSectionOrder.resolveOrder(stored, emptyList())
        )
    }

    @Test
    fun moveUpAndDownSwapWithTheNeighbour() {
        val order = listOf(
            Mailbox.CONTACTS, Mailbox.QUARANTINE, Mailbox.AUTH, Mailbox.LOCKER
        )
        assertEquals(
            listOf(
                Mailbox.QUARANTINE, Mailbox.CONTACTS, Mailbox.AUTH, Mailbox.LOCKER
            ),
            FolderSectionOrder.move(order, Mailbox.CONTACTS, up = false)
        )
        assertEquals(
            listOf(
                Mailbox.CONTACTS, Mailbox.QUARANTINE, Mailbox.LOCKER, Mailbox.AUTH
            ),
            FolderSectionOrder.move(order, Mailbox.AUTH, up = false)
        )
        assertEquals(
            listOf(
                Mailbox.CONTACTS, Mailbox.AUTH, Mailbox.QUARANTINE, Mailbox.LOCKER
            ),
            FolderSectionOrder.move(order, Mailbox.AUTH, up = true)
        )
    }

    @Test
    fun moveAtAnEndOrOfAnUnknownEntryIsANoOp() {
        val order = listOf(
            Mailbox.CONTACTS, Mailbox.QUARANTINE, Mailbox.AUTH, Mailbox.LOCKER
        )
        assertEquals(order, FolderSectionOrder.move(order, Mailbox.CONTACTS, up = true))
        assertEquals(order, FolderSectionOrder.move(order, Mailbox.LOCKER, up = false))
        assertEquals(order, FolderSectionOrder.move(order, "folder:gone", up = true))
    }
}
