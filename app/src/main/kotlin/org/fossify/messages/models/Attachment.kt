package org.fossify.messages.models

import androidx.core.net.toUri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "attachments", indices = [(Index(value = ["message_id"], unique = true))])
data class Attachment(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "message_id") var messageId: Long,
    @ColumnInfo(name = "uri_string") var uriString: String,
    @ColumnInfo(name = "mimetype") var mimetype: String,
    @ColumnInfo(name = "width") var width: Int,
    @ColumnInfo(name = "height") var height: Int,
    @ColumnInfo(name = "filename") var filename: String
) {
    /**
     * True when this attachment is decrypted locked media, served
     * from LockedMediaCache for one gate session only. A body
     * property, not a constructor parameter — Room rejects @Ignore
     * constructor parameters. Never persisted (locked messages are
     * never cached in Room): Glide must load it with
     * DiskCacheStrategy.NONE and no memory cache, or the decoded
     * bytes would outlive the re-lock on disk. No code copies
     * Attachment instances, so the flag is never lost to copy().
     */
    @Ignore
    var isLockedMedia: Boolean = false

    fun getUri() = uriString.toUri()
}
