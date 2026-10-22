package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tags attached to a sender, keyed by the sender address as it appears in
 * messages. Tags are stored comma-joined; [tagList] is the parsed view.
 * Rules can match on a tag and can assign one (see FilterRule.assignTag).
 */
@Entity(tableName = "sender_tags")
data class SenderTags(
    @PrimaryKey @ColumnInfo(name = "number") var number: String,
    @ColumnInfo(name = "tags") var tags: String = "",
) {
    val tagList: List<String>
        get() = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun of(number: String, tags: Collection<String>): SenderTags {
            return SenderTags(number, tags.distinct().joinToString(","))
        }
    }
}
