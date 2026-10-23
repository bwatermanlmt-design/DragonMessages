package org.fossify.messages.helpers

import android.content.Context
import android.graphics.Color
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.foldersDB
import org.fossify.messages.models.Folder
import org.fossify.messages.models.LabelMapping
import org.fossify.messages.models.Mailbox
import java.util.UUID

/**
 * Ben's own folders (revision 7): creation, renaming, the label mappings
 * that feed them, and deletion — which never deletes a thread, it sends
 * every thread in the folder back to its natural home (see
 * [FilterEvaluator.naturalHome]) and clears the records that pointed at
 * the folder. Callers must already be on a background thread.
 */
object FolderManager {

    /**
     * The folder palette: the five landing-page hues from revision 6
     * first (so early folders match their system neighbours), then the
     * revision 7 additions — teal, plum, forest, indigo, rust, olive —
     * all mid-dark tones in the app icon's deep-navy (#1D314E) family.
     * New folders take the next colour by creation count.
     */
    val PALETTE: List<Int> = listOf(
        Color.rgb(0x2F, 0x5D, 0x8A),
        Color.rgb(0x5E, 0x4B, 0x9B),
        Color.rgb(0x9C, 0x6B, 0x1E),
        Color.rgb(0xB0, 0x3A, 0x2E),
        Color.rgb(0x37, 0x47, 0x4F),
        Color.rgb(0x1E, 0x7A, 0x74),
        Color.rgb(0x7A, 0x3B, 0x69),
        Color.rgb(0x43, 0x70, 0x3C),
        Color.rgb(0x3B, 0x4A, 0x8C),
        Color.rgb(0x9C, 0x4A, 0x1F),
        Color.rgb(0x6B, 0x7C, 0x2E),
    )

    fun allFolders(context: Context): List<Folder> {
        return try {
            context.foldersDB.getAllFolders()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun folderForMailbox(context: Context, mailbox: String): Folder? {
        val folderId = Mailbox.folderIdOf(mailbox) ?: return null
        return try {
            context.foldersDB.getFolder(folderId)
        } catch (e: Exception) {
            null
        }
    }

    /** The display name of any mailbox: a folder's own name, else its string resource. */
    fun displayName(context: Context, mailbox: String): String {
        return folderForMailbox(context, mailbox)?.name
            ?: context.getString(Mailbox.displayNameRes(mailbox))
    }

    /** Create a folder, optionally with a label mapping made in the same breath. */
    fun createFolder(context: Context, name: String, label: String? = null): Folder {
        val dao = context.foldersDB
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            name = name,
            color = PALETTE[dao.getFolderCount() % PALETTE.size],
            sortOrder = dao.getMaxFolderSortOrder() + 1
        )
        dao.insertOrUpdateFolder(folder)

        if (!label.isNullOrBlank()) {
            addLabelMapping(context, label, Mailbox.customMailboxId(folder.id))
        }
        return folder
    }

    fun renameFolder(context: Context, folder: Folder, newName: String) {
        context.foldersDB.insertOrUpdateFolder(folder.copy(name = newName))
    }

    fun addLabelMapping(context: Context, label: String, mailbox: String): LabelMapping {
        val dao = context.foldersDB
        val mapping = LabelMapping(
            label = label,
            mailbox = mailbox,
            sortOrder = dao.getMaxLabelMappingSortOrder() + 1
        )
        dao.insertLabelMapping(mapping)
        return mapping
    }

    /**
     * Delete a folder: its threads fall back to their natural homes as
     * natural filings (hand-filed marks cleared — the placement is the
     * machinery's now, not Ben's), the label mappings and sender filings
     * that pointed at it are cleared (those threads re-home naturally on
     * the same rule), its folder-mute is forgotten, and the folder row
     * goes. Threads themselves are never deleted.
     */
    fun deleteFolder(context: Context, folder: Folder) {
        val mailbox = Mailbox.customMailboxId(folder.id)
        val conversationsDB = context.conversationsDB

        conversationsDB.getByMailbox(mailbox).forEach { conversation ->
            val home = FilterEvaluator.naturalHome(context, conversation)
            conversationsDB.updateMailboxNatural(conversation.threadId, home)
        }

        val dao = context.foldersDB
        dao.deleteLabelMappingsForMailbox(mailbox)
        dao.deleteSenderFilingsForMailbox(mailbox)
        dao.deleteFolder(folder)

        if (mailbox in context.config.mutedMailboxes) {
            context.config.mutedMailboxes = context.config.mutedMailboxes - mailbox
        }
    }
}
