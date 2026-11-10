package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One locked message: the encrypted backup of a single SMS or MMS that
 * used to live in Android's shared message database. The plaintext is
 * a serialized LockedRecord (see models/LockedRecord.kt): the
 * SmsBackup/MmsBackup as the metadata face, plus a plain display
 * envelope. Rows locked before 1.10.17 hold a bare backup instead;
 * the reader accepts both. Unlocking is decrypt, deserialize, and
 * write back through MessagesWriter, so the locked copy round-trips
 * exactly, attachments included.
 *
 * The row is keyed by the message's original provider id; [threadId]
 * and [date] (millis, from the backup) let the Locker view list and
 * order a thread's messages without decrypting anything.
 *
 * The ciphertext itself is NOT in this row: it lives in
 * [LockedMessageChunk] pieces (see that class for why — a photo MMS
 * blows past SQLite's 2MB CursorWindow and a single big row makes
 * every read of the thread fail). [iv] is small and stays here.
 */
@Entity(
    tableName = "locked_messages",
    primaryKeys = ["id", "is_mms"]
)
data class LockedMessage(
    /** Original provider id. SMS and MMS ids live in separate tables and can collide, hence the composite key with [isMMS]. */
    val id: Long,
    @ColumnInfo(name = "thread_id")
    val threadId: Long,
    @ColumnInfo(name = "is_mms")
    val isMMS: Boolean,
    /** Message date in millis, copied from the backup for ordering. */
    val date: Long,
    /** Base64 of the random 12-byte GCM IV used for this message. */
    val iv: String,
    /**
     * The display envelope as its own small ciphertext (1.10.18):
     * Base64 of the IV and the ciphertext of the LockedDisplay JSON,
     * encrypted under the same Keystore key as the backup. Null for
     * rows locked before 1.10.18 — those upgrade in place on first
     * read. The thread view decrypts only these few hundred bytes
     * to show the thread; the multi-MB backup decrypts lazily,
     * behind it, for the media.
     */
    @ColumnInfo(name = "envelope_iv")
    val envelopeIv: String? = null,
    @ColumnInfo(name = "envelope_ciphertext")
    val envelopeCiphertext: String? = null,
)
