package org.fossify.messages.helpers

import android.content.Context
import android.graphics.Color
import org.fossify.messages.R
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

    /**
     * Built-in icon keys for image dressing (revision 9). Keys are stored
     * in Folder.image; null means the default folder glyph. Only
     * drawables that exist in this module (plus the folder glyph, which
     * the folder row layout already resolves) are listed, so a missing
     * icon can never break the build.
     */
    val IMAGE_ICONS: Map<String, Int> = mapOf(
        "image" to R.drawable.ic_image_vector,
        "music" to R.drawable.ic_music_vector,
        "document" to R.drawable.ic_document_vector,
        "video" to R.drawable.ic_video_camera_vector,
        "archive" to R.drawable.ic_archive_vector,
        "pin" to R.drawable.ic_pin_vector,
        "calendar" to R.drawable.ic_calendar_month_vector,
        "pdf" to R.drawable.ic_vector_pdf,
        "audio" to R.drawable.ic_vector_audio_file,
        "zip" to R.drawable.ic_vector_folder_zip,
    )

    /** The glyph for a folder: its dressed icon, or the default folder glyph. */
    fun iconRes(imageKey: String?): Int = IMAGE_ICONS[imageKey] ?: R.drawable.ic_folder_vector

    /**
     * Move a folder one step among its siblings (same parent, in
     * sortOrder). Siblings are renumbered sequentially, so equal or
     * gapped sortOrders can never make a move a silent no-op. Returns
     * false when the folder is already at that end.
     */
    fun moveFolder(context: Context, folder: Folder, up: Boolean): Boolean {
        val dao = context.foldersDB
        val siblings = dao.getAllFolders()
            .filter { it.parentId == folder.parentId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val index = siblings.indexOfFirst { it.id == folder.id }
        if (index == -1) return false
        val target = if (up) index - 1 else index + 1
        if (target !in siblings.indices) return false
        val reordered = siblings.toMutableList()
        reordered.removeAt(index)
        reordered.add(target, folder)
        reordered.forEachIndexed { i, sibling ->
            if (sibling.sortOrder != i) {
                dao.insertOrUpdateFolder(sibling.copy(sortOrder = i))
            }
        }
        return true
    }

    /**
     * Nest a folder under a parent, or null to lift it back to the top
     * level. Refuses cycles (a folder under itself or any of its
     * descendants) and unknown parents; those return false and change
     * nothing. No depth cap: Ben saw no need to artificially limit depth.
     */
    fun setParent(context: Context, folder: Folder, parentId: String?): Boolean {
        if (parentId == folder.id) return false
        if (parentId != null) {
            val byId = context.foldersDB.getAllFolders().associateBy { it.id }
            var current = byId[parentId] ?: return false
            val visited = HashSet<String>()
            while (true) {
                if (current.id == folder.id || !visited.add(current.id)) return false
                current = current.parentId?.let { byId[it] } ?: break
            }
        }
        context.foldersDB.insertOrUpdateFolder(folder.copy(parentId = parentId))
        return true
    }

    fun setColor(context: Context, folder: Folder, color: Int) {
        context.foldersDB.insertOrUpdateFolder(folder.copy(color = color))
    }

    fun setImage(context: Context, folder: Folder, imageKey: String?) {
        context.foldersDB.insertOrUpdateFolder(folder.copy(image = imageKey))
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
