package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A manual filing that belongs to the sender, not to a thread (Ben's
 * refinement during the revision 7 build: "If I move a thread or message,
 * that sender belongs wherever that is"). When Ben files a 1:1 thread by
 * hand — Send to folder, a macro run, a release — the sender's number is
 * recorded here with the mailbox he chose, and every later thread or
 * message from that sender files there too, even if the original thread
 * is deleted. The latest move overwrites the record; there is no expiry
 * and no separate clear action — moving the sender somewhere else is the
 * control. Group threads have no single sender and never appear here.
 *
 * The record is permanent until Ben moves the sender again. (A temporary,
 * expiring variant was floated during the build and explicitly declined
 * by Ben the same day — placements stay where he put them.)
 */
@Entity(tableName = "sender_filings")
data class SenderFiling(
    @PrimaryKey @ColumnInfo(name = "number") var number: String,
    @ColumnInfo(name = "mailbox") var mailbox: String,
)
