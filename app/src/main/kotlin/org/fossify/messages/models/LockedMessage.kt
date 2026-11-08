package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One locked message: the encrypted backup of a single SMS or MMS that
 * used to live in Android's shared message database. The plaintext is a
 * serialized SmsBackup or MmsBackup (see LockerCrypto) — unlocking is
 * decrypt, deserialize, and write back through MessagesWriter, so the
 * locked copy round-trips exactly, attachments included.
 *
 * The row is keyed by the message's original provider id; [threadId]
 * and [date] (millis, from the backup) let the Locker view list and
 * order a thread's messages without decrypting anything.
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
    /** Base64 of the AES/GCM ciphertext (tag included). */
    val ciphertext: String,
)
