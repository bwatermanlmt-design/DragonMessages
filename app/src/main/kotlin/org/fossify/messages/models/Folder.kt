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
)
