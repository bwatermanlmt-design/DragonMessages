package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One address-book label → folder mapping (Ben's revision 7 ruling): a
 * contact carrying [label] (a Contacts group title) has [mailbox] as its
 * home. Mappings are consulted in [sortOrder] — creation order — and the
 * first one the contact carries wins. The business labels ("Businesses",
 * "Services") are ordinary labels here: mapping one is the deliberate way
 * to give business contacts a folder of their own (see LabelHomes).
 */
@Entity(tableName = "label_mappings")
data class LabelMapping(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    @ColumnInfo(name = "label") var label: String,
    @ColumnInfo(name = "mailbox") var mailbox: String,
    @ColumnInfo(name = "sort_order") var sortOrder: Int,
)
