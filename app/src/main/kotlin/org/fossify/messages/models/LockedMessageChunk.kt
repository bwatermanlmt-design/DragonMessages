package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One piece of a locked message's ciphertext. A locked message's
 * encrypted backup can be bigger than SQLite's 2MB CursorWindow — a
 * full-size photo MMS is several MB — and a single row that big makes
 * every SELECT * of the message fail (SQLiteBlobTooBigException),
 * which is what stranded a photo-heavy locked thread on 1.10.12: the
 * thread listed but would neither open nor unlock. So the ciphertext
 * lives here in 256KB pieces, and the locked_messages row itself
 * stays small (id, thread, date, IV). The list query only ever reads
 * small rows; a message's pieces are loaded and joined only when
 * that one message is decrypted.
 *
 * Pieces are keyed by the message's original provider id plus
 * [isMMS], matching LockedMessage's composite key, and ordered by
 * [chunkIndex]. The joined pieces are the exact Base64 ciphertext
 * the row used to carry, so decrypting is unchanged.
 */
@Entity(
    tableName = "locked_message_chunks",
    primaryKeys = ["id", "is_mms", "chunk_index"]
)
data class LockedMessageChunk(
    /** Original provider id — same key as the LockedMessage row. */
    val id: Long,
    @ColumnInfo(name = "is_mms")
    val isMMS: Boolean,
    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,
    /** One 256KB-or-less slice of the Base64 ciphertext. */
    val chunk: String,
)
