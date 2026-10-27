package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A folder Ben created himself (Family, Work, anything): a user-defined
 * mailbox alongside the five system ones. A conversation filed here
 * carries the mailbox id `folder:<id>` (see [Mailbox.customMailboxId]).
 * [color] is an opaque ARGB value from the folder palette; [sortOrder]
 * is creation order, which is the order the folder view lists them in.
 */
@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey @ColumnInfo(name = "id") var id: String,
    @ColumnInfo(name = "name") var name: String,
    @ColumnInfo(name = "color") var color: Int,
    @ColumnInfo(name = "sort_order") var sortOrder: Int,
    // Revision 9: optional nesting. Null means top-level (flat, the
    // default). A folder with a parent renders under it; there is no
    // artificial depth cap. Cycles are refused by FolderManager.setParent.
    @ColumnInfo(name = "parent_id") var parentId: String? = null,
    // Revision 9: optional image dressing — a built-in icon key (see
    // FolderManager.IMAGE_ICONS), or null for the default folder glyph.
    @ColumnInfo(name = "image") var image: String? = null,
)
