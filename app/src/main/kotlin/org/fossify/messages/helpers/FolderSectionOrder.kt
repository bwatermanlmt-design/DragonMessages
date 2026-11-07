package org.fossify.messages.helpers

import org.fossify.messages.models.Mailbox

/**
 * The order of the Folders section on the landing page (the folder
 * view), 2026-11-07 spec: Contacts, Not in Contacts, Auth and
 * Businesses, the user's own folders, Locker — each movable up and
 * down by the user. Spam and the actions live in their own sections
 * and are not part of this order.
 *
 * An entry is a mailbox id: one of the four section mailboxes
 * ([Mailbox.CONTACTS], [Mailbox.QUARANTINE], [Mailbox.AUTH],
 * [Mailbox.LOCKER]) or a user folder's mailbox id
 * (`folder:<folder id>`). The stored order lives in
 * [Config.folderSectionOrder] as a comma-joined string; an empty
 * stored order means "never customized — use the default".
 *
 * Pure functions, so the order math is unit-tested without Android.
 */
object FolderSectionOrder {

    /** The four system mailboxes that live in the Folders section. */
    val SYSTEM_ENTRIES = listOf(Mailbox.CONTACTS, Mailbox.QUARANTINE, Mailbox.AUTH, Mailbox.LOCKER)

    /** Default order: the three inboxes, then top-level user folders, then Locker. */
    fun defaultOrder(topLevelCustomIds: List<String>): List<String> =
        listOf(Mailbox.CONTACTS, Mailbox.QUARANTINE, Mailbox.AUTH) + topLevelCustomIds + Mailbox.LOCKER

    /**
     * The order to render: the stored order where it covers entries
     * that still exist, with anything new (a folder created since the
     * last reorder, a system entry missing from storage) slotted into
     * its default position. Stored entries for folders that no longer
     * exist are dropped.
     */
    fun resolveOrder(stored: List<String>, topLevelCustomIds: List<String>): List<String> {
        val known = defaultOrder(topLevelCustomIds)
        val storedIndex = stored.withIndex().associate { it.value to it.index }
        // Stable sort: entries the user placed keep their stored
        // positions; new entries keep their default relative order.
        return known.sortedBy { storedIndex[it] ?: known.indexOf(it) }
    }

    /**
     * Move [entry] one step in [order]; returns the new order, or the
     * same order when the entry is already at that end (or unknown).
     */
    fun move(order: List<String>, entry: String, up: Boolean): List<String> {
        val index = order.indexOf(entry)
        if (index == -1) return order
        val target = if (up) index - 1 else index + 1
        if (target !in order.indices) return order
        val reordered = order.toMutableList()
        reordered.removeAt(index)
        reordered.add(target, entry)
        return reordered
    }
}
