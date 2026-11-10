package org.fossify.messages.models

import kotlinx.serialization.Serializable

/**
 * One locked message's stored record (1.10.17): two faces, one
 * ciphertext.
 *
 * - [backup] is the METADATA face: the MessagesBackup JSON the Locker
 * has stored since 1.10.11. Unlock restores from it exactly as before,
 * byte-identical — that path is proven by md5 round trips and is not
 * redesigned here.
 * - [display] is the DISPLAY face: the plain fields the thread view
 * needs, derived ONCE at lock time from the backup (see
 * LockerManager.deriveDisplay). Display reads only these fields and
 * never re-derives meaning from the provider schema — every display
 * bug so far lived at that seam: MMS text read from the empty data
 * field instead of text (1.10.16), an address-less MMS filtered out
 * of the view by the participants filter, a vCard's payload encoded
 * two ways.
 *
 * The envelope carries no media bytes: those stay in the backup's
 * parts (MmsPart.mediaBytes is the one seam for them) and the
 * envelope's attachments only DESCRIBE them (index into the parts,
 * mime, name), so the record costs a few hundred bytes, not a second
 * copy of the photo.
 *
 * Format note: rows locked before 1.10.17 hold a bare MessagesBackup
 * JSON, not a LockedRecord. The reader accepts both shapes and
 * derives the envelope in memory for old rows (see
 * LockerManager.decodeRecord) — the format is versioned in-band,
 * inside the ciphertext, so no Room migration is needed. A Room
 * migration would have to decrypt every row without a Context and
 * with fallbackToDestructiveMigration armed: any failure there would
 * wipe the whole store, locked messages included. The 19->20
 * migration stayed pure SQL for exactly this reason.
 */
@Serializable
data class LockedRecord(
    val backup: MessagesBackup,
    val display: LockedDisplay
)

/**
 * The display face. [sender] is a single plain string: the FROM
 * address for a received MMS, "" for a sent one, and the thread's
 * conversation address when the backup has no address at all — so a
 * sender is never empty for a received message. [participants] are
 * the conversation's addresses, falling back to the thread's
 * conversation address when the backup lists none, so the view's
 * participants filter can never drop a locked message. [body] is
 * the display text, computed at lock time (SMS body, or the MMS
 * text parts joined). [attachments] describe the media parts.
 */
@Serializable
data class LockedDisplay(
    val sender: String,
    val body: String,
    val participants: List<String>,
    val attachments: List<LockedDisplayAttachment> = emptyList(),
    /**
     * The message shell, so the thread view can build a Message from
     * the envelope alone (1.10.18: the envelope is a separate small
     * ciphertext, and the big backup decrypts lazily behind it).
     * SMS: the provider type/status/read/subscription; MMS: the
     * message box, status 0, read, subscription. Defaults are the
     * received-message values, for envelopes derived before these
     * fields existed — deriveDisplay always sets them explicitly.
     */
    val type: Int = 1,
    val status: Int = -1,
    val read: Boolean = true,
    val subscriptionId: Int = 0
)

/** One media part as the display face sees it: its index into the backup's parts list, its mime type, and its file name. */
@Serializable
data class LockedDisplayAttachment(
    val partIndex: Int,
    val mimeType: String,
    val fileName: String
)
