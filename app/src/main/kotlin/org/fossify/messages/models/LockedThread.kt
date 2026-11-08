package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A thread that lives in the Locker. The row is what makes the thread
 * "locked": the thread's messages are in [LockedMessage] rows and
 * nowhere else, and the conversation's mailbox is LOCKER.
 *
 * [previousMailbox] is where the thread came from, so moving it back
 * out restores exactly where it was (Contacts, Not in Contacts, a user
 * folder, ...). The Locker folder itself holds the row, never the
 * mailbox — the mailbox is just the label on the conversation.
 */
@Entity(tableName = "locked_threads")
data class LockedThread(
    @PrimaryKey
    @ColumnInfo(name = "thread_id")
    val threadId: Long,
    @ColumnInfo(name = "previous_mailbox")
    val previousMailbox: String,
    @ColumnInfo(name = "locked_at")
    val lockedAt: Long,
)
