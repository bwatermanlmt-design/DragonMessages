package org.fossify.messages.helpers

import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Base64
import com.google.android.mms.pdu_alt.PduHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.parseToJsonElement
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.R
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.filtering.LockerCrypto
import org.fossify.messages.extensions.deleteMessage
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.lockedMessageChunksDB
import org.fossify.messages.extensions.lockedMessagesDB
import org.fossify.messages.extensions.lockedThreadsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.messaging.MessagingUtils.Companion.ADDRESS_SEPARATOR
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.LockedDisplay
import org.fossify.messages.models.LockedDisplayAttachment
import org.fossify.messages.models.LockedMessage
import org.fossify.messages.models.LockedMessageChunk
import org.fossify.messages.models.LockedRecord
import org.fossify.messages.models.LockedThread
import org.fossify.messages.models.Mailbox
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessagesBackup
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.MessageAttachment
import org.fossify.messages.models.MmsBackup
import org.fossify.messages.models.MmsPart
import org.fossify.messages.models.SmsBackup
import javax.crypto.SecretKey

/**
 * The Locker: real isolation, not a label. Locking a thread copies
 * every message into the app's own encrypted store (one AES/GCM
 * ciphertext per message, key in the Android Keystore) and then deletes
 * those messages from Android's shared message database, so no other
 * SMS app — and no backup that reads the provider — can see them.
 * Unlocking reverses it through MessagesWriter, so the messages land
 * back in the provider exactly as they were.
 *
 * Failure direction is always "keep a copy": encrypt-and-store happens
 * before any delete, and restore happens before any store row is
 * dropped. A crash mid-move leaves a duplicate, never a loss.
 *
 * Each stored message is a LockedRecord (see models/LockedRecord.kt):
 * the MessagesBackup JSON as the metadata face — unlock restores
 * from it exactly as before — plus a plain display envelope derived
 * once at lock time, which the thread view reads directly instead of
 * re-deriving meaning from the provider schema. Rows locked before
 * 1.10.17 hold a bare backup; the reader accepts both shapes and
 * derives the envelope in memory for old rows.
 *
 * The thread view reads locked threads from here (see getMessages):
 * the provider has nothing for them, by design.
 */
class LockerManager(private val context: Context) {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun isThreadLocked(threadId: Long): Boolean {
        return context.lockedThreadsDB.get(threadId) != null
    }

    fun getLockedThread(threadId: Long): LockedThread? {
        return context.lockedThreadsDB.get(threadId)
    }

    fun lockedMessageCount(): Int {
        return context.lockedMessagesDB.countAll()
    }

    /**
     * Lock a whole thread. Returns false when the thread does not exist
     * or anything fails before the provider delete — in that case
     * nothing was deleted and the thread is unchanged.
     */
    fun lockThread(threadId: Long): Boolean {
        if (isThreadLocked(threadId)) {
            return true
        }

        // Writing to the shared provider (the delete half of locking)
        // requires the default-SMS role. Refuse before storing anything:
        // a lock that stores but cannot delete would leave a half-locked
        // thread — encrypted rows in the store, originals still in the
        // provider, and the thread reading from the store.
        if (!context.isDefaultSmsApp()) {
            return false
        }

        val conversation = context.conversationsDB.getConversationWithThreadId(threadId)
            ?: return false

        return try {
            val reader = MessagesReader(context)
            val key = LockerKey.getOrCreateKey()
            val rows = mutableListOf<LockedMessage>()
            val allChunks = mutableListOf<LockedMessageChunk>()

            reader.getSmsBackupsForThread(threadId).forEach { (id, backup) ->
                val (row, chunks) = encryptBackup(backup, id, isMMS = false, threadId, backup.date, key, conversation.phoneNumber)
                rows.add(row)
                allChunks.addAll(chunks)
            }
            reader.getMmsBackupsForThread(threadId).forEach { (id, backup) ->
                // MMS dates are seconds; the store orders on millis.
                val (row, chunks) = encryptBackup(backup, id, isMMS = true, threadId, backup.date * 1000L, key, conversation.phoneNumber)
                rows.add(row)
                allChunks.addAll(chunks)
            }

            // A thread with no messages has nothing to lock: refuse
            // rather than recording an empty locked thread, which
            // would show an empty view and could never unlock to
            // anything.
            if (rows.isEmpty()) {
                return false
            }

            // Store first, delete second: a failure here leaves the
            // thread untouched in the provider.
            context.lockedMessagesDB.insertAll(rows)
            context.lockedMessageChunksDB.insertAll(allChunks)
            context.lockedThreadsDB.insert(
                LockedThread(
                    threadId = threadId,
                    previousMailbox = conversation.mailbox,
                    lockedAt = System.currentTimeMillis()
                )
            )

            deleteThreadFromProvider(threadId)
            context.messagesDB.deleteThreadMessages(threadId)
            context.conversationsDB.updateMailboxManuallyFiled(threadId, Mailbox.LOCKER)
            context.conversationsDB.clearSnippet(threadId)
            context.notificationManager.cancel(threadId.hashCode())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Unlock a thread: restore every message to the provider, then drop
     * the store rows and put the conversation back where it came from.
     * A restore failure aborts the unlock — the thread stays locked
     * with its store intact, and the unlock can be retried.
     *
     * The provider files the restored messages under its own thread for
     * the address — the locked thread's provider rows are gone, so its
     * id is not reused. The restored thread is therefore re-homed to
     * where the locked thread came from, and the stale locked row is
     * dropped, so the user sees the same number, the same messages, in
     * the right folder.
     */
    fun unlockThread(threadId: Long): Boolean {
        val locked = context.lockedThreadsDB.get(threadId) ?: return false

        // Restoring writes the messages back to the shared provider,
        // which only the default SMS app may do. Refuse before
        // restoring anything: the UI flow asks for the role first and
        // only calls unlock once it is held (or the user declines).
        if (!context.isDefaultSmsApp()) {
            return false
        }

        return try {
            val writer = MessagesWriter(context)
            val key = LockerKey.getOrCreateKey()
            val rows = context.lockedMessagesDB.getByThread(threadId)

            var firstBackup: MessagesBackup? = null
            var lastSnippet = ""
            var lastDateSeconds = 0
            rows.forEach { row ->
                val backup = decryptBackup(row, key)
                if (firstBackup == null) {
                    firstBackup = backup
                }
                lastSnippet = when (backup) {
                    is SmsBackup -> backup.body ?: ""
                    is MmsBackup -> mmsDisplayText(backup.parts)
                }
                // SMS dates are millis, MMS dates are seconds.
                lastDateSeconds = when (backup) {
                    is SmsBackup -> (backup.date / 1000L).toInt()
                    is MmsBackup -> backup.date.toInt()
                }
                when (backup) {
                    is SmsBackup -> writer.writeSmsMessage(backup)
                    is MmsBackup -> writer.writeMmsMessage(backup)
                }
            }

            val restoredThreadId = firstBackup?.let { findRestoredThreadId(it) }
            // Capture the locked row BEFORE deleting it: when the
            // provider reuses the thread id on restore, the restored
            // row IS this row, and deleting after seeding would wipe
            // the just-seeded row — the 1.10.13 re-homing bug, which
            // landed restored threads in Contacts instead of their
            // previous mailbox.
            val lockedConv = if (restoredThreadId != null && restoredThreadId != 0L) {
                context.conversationsDB.getConversationWithThreadId(threadId)
            } else {
                null
            }
            context.conversationsDB.deleteThreadId(threadId)
            if (restoredThreadId != null && restoredThreadId != 0L) {
                // The provider files restored messages under a NEW thread
                // id, so no Room row exists for it yet: setting the
                // mailbox on a missing row updates nothing, and the later
                // sync files the fresh thread as contacts. Seed the row
                // from the locked thread — same conversation, new id —
                // with its previous mailbox, so the restore lands where
                // the thread came from.
                val restoredConv = lockedConv?.copy(
                    threadId = restoredThreadId,
                    mailbox = locked.previousMailbox,
                    manuallyFiled = true,
                    snippet = lastSnippet,
                    date = lastDateSeconds
                ) ?: Conversation(
                    threadId = restoredThreadId,
                    snippet = lastSnippet,
                    date = lastDateSeconds,
                    read = true,
                    title = backupAddress(firstBackup),
                    photoUri = "",
                    isGroupConversation = false,
                    phoneNumber = backupAddress(firstBackup),
                    mailbox = locked.previousMailbox,
                    manuallyFiled = true
                )
                context.conversationsDB.insertOrUpdate(restoredConv)
                movePinIfNeeded(threadId, restoredThreadId)
            }

            // Chunks go first: their delete matches through the
            // locked_messages rows, which the next line drops.
            context.lockedMessageChunksDB.deleteByThread(threadId)
            context.lockedMessagesDB.deleteByThread(threadId)
            context.lockedThreadsDB.delete(threadId)
            // The thread's decrypted media files are no longer
            // needed — the provider has the originals again — and
            // must not linger past the unlock. A failure here must
            // not fail the unlock itself.
            runCatching { clearDecryptedMedia() }
            writer.fixConversationDates()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * The provider thread a restored message actually landed in, found
     * by the same key the writer dedupes on. Precise for group threads
     * too, where an address lookup would find the wrong thread.
     */
    private fun findRestoredThreadId(backup: MessagesBackup): Long? {
        return try {
            var threadId: Long? = null
            when (backup) {
                is SmsBackup -> {
                    context.queryCursor(
                        Sms.CONTENT_URI,
                        arrayOf(Sms.THREAD_ID),
                        "${Sms.DATE} = ? AND ${Sms.ADDRESS} = ? AND ${Sms.TYPE} = ?",
                        arrayOf(
                            backup.date.toString(),
                            backup.address,
                            backup.type.toString()
                        )
                    ) { cursor ->
                        threadId = cursor.getLongValue(Sms.THREAD_ID)
                    }
                }

                is MmsBackup -> {
                    context.queryCursor(
                        Mms.CONTENT_URI,
                        arrayOf(Mms.THREAD_ID),
                        "${Mms.DATE} = ? AND ${Mms.DATE_SENT} = ? AND ${Mms.MESSAGE_BOX} = ?",
                        arrayOf(
                            backup.date.toString(),
                            backup.dateSent.toString(),
                            backup.messageBox.toString()
                        )
                    ) { cursor ->
                        threadId = cursor.getLongValue(Mms.THREAD_ID)
                    }
                }
            }
            threadId
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** The address a backup belongs to, for the fallback restored row. */
    private fun backupAddress(backup: MessagesBackup?): String {
        if (backup == null) {
            return ""
        }
        return when (backup) {
            is SmsBackup -> backup.address
            is MmsBackup -> backup.addresses.firstOrNull { it.type == PduHeaders.FROM }?.address
                ?: backup.addresses.firstOrNull()?.address ?: ""
        }
    }

    /** A pinned locked thread keeps its pin on the thread it restores to. */
    private fun movePinIfNeeded(fromThreadId: Long, toThreadId: Long) {
        try {
            val pinned = context.config.pinnedConversations
            if (pinned.contains(fromThreadId.toString())) {
                context.config.pinnedConversations =
                    pinned.map { if (it == fromThreadId.toString()) toThreadId.toString() else it }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Isolate one message (incoming in a locked thread, or just sent in
     * one). No-op when the thread is not locked, or the message is
     * already in the store.
     */
    fun lockMessage(messageId: Long, isMMS: Boolean, threadId: Long): Boolean {
        if (!isThreadLocked(threadId)) {
            return false
        }
        if (context.lockedMessagesDB.getById(messageId, isMMS) != null) {
            return true
        }

        // Same guard as lockThread: the delete half needs the
        // default-SMS role, and storing first would half-lock the message.
        if (!context.isDefaultSmsApp()) {
            return false
        }

        return try {
            val reader = MessagesReader(context)
            val key = LockerKey.getOrCreateKey()
            // The display envelope falls back to the thread's
            // conversation address when a message has none of its
            // own (an address-less MMS): the conversation row is
            // what survives the lock, so it is the fallback that
            // is still true at display time.
            val fallbackAddress = try {
                context.conversationsDB.getConversationWithThreadId(threadId)?.phoneNumber ?: ""
            } catch (e: Exception) {
                ""
            }
            val (row, chunks) = if (isMMS) {
                val (id, backup) = reader.getMmsBackupById(messageId) ?: return false
                encryptBackup(backup, id, isMMS = true, threadId, backup.date * 1000L, key, fallbackAddress)
            } else {
                val (id, backup) = reader.getSmsBackupById(messageId) ?: return false
                encryptBackup(backup, id, isMMS = false, threadId, backup.date, key, fallbackAddress)
            }

            context.lockedMessagesDB.insert(row)
            context.lockedMessageChunksDB.insertAll(chunks)
            context.deleteMessage(messageId, isMMS)
            context.conversationsDB.clearSnippet(threadId)
            // Keep the locked thread sorted by its latest arrival: the
            // receive path no longer bumps the date through the provider
            // conversation (there is none for a locked thread).
            runCatching {
                val dateSeconds = (row.date / 1000L).toInt()
                context.conversationsDB.updateDate(threadId, dateSeconds)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Isolate one just-sent message, finding its thread from the
     * provider row. The sent-status receivers call this: it is the
     * choke point for every in-app send (thread view, scheduled send,
     * quick reply), and it runs after the send status is final, so the
     * stored copy carries the real SENT/FAILED state.
     */
    fun lockSentMessage(messageId: Long, isMMS: Boolean): Boolean {
        val threadId = threadIdForProviderMessage(messageId, isMMS) ?: return false
        return lockMessage(messageId, isMMS, threadId)
    }

    /** Sweep a locked thread's provider rows into the store (safety net). */
    fun sweepThread(threadId: Long): Int {
        if (!isThreadLocked(threadId)) {
            return 0
        }
        val reader = MessagesReader(context)
        var locked = 0
        reader.getSmsBackupsForThread(threadId).forEach { (id, _) ->
            if (context.lockedMessagesDB.getById(id, false) == null &&
                lockMessage(id, isMMS = false, threadId)
            ) {
                locked++
            }
        }
        reader.getMmsBackupsForThread(threadId).forEach { (id, _) ->
            if (context.lockedMessagesDB.getById(id, true) == null &&
                lockMessage(id, isMMS = true, threadId)
            ) {
                locked++
            }
        }
        return locked
    }

    private fun threadIdForProviderMessage(messageId: Long, isMMS: Boolean): Long? {
        val uri = if (isMMS) Mms.CONTENT_URI else Sms.CONTENT_URI
        var threadId: Long? = null
        try {
            context.queryCursor(
                uri,
                arrayOf(Sms.THREAD_ID),
                "${Sms._ID} = ?",
                arrayOf(messageId.toString())
            ) { cursor ->
                threadId = cursor.getLongValue(Sms.THREAD_ID)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return threadId
    }

    /** A locked thread's messages, decrypted, for the thread view. */
    fun getLockedMessages(threadId: Long): List<Message> {
        // A dead process leaves decrypted media files behind; the
        // next locked view load sweeps every session but this one.
        LockedMediaCache.clearStaleSessions(context)
        // The envelope fallback for rows locked before 1.10.17 (or
        // any message whose backup has no address): the thread's
        // conversation address, fetched once for the whole thread.
        val fallbackAddress = try {
            context.conversationsDB.getConversationWithThreadId(threadId)?.phoneNumber ?: ""
        } catch (e: Exception) {
            ""
        }
        val placeholderText = context.getString(R.string.locker_undecryptable_message)
        return try {
            val key = LockerKey.getOrCreateKey()
            context.lockedMessagesDB.getByThread(threadId).map { row ->
                try {
                    toDisplayMessage(decryptRecord(row, key, fallbackAddress), row)
                } catch (e: Exception) {
                    // A row that will not decrypt is shown, not
                    // dropped: silently vanishing messages are how
                    // the empty locked view happened, and a
                    // placeholder tells the user the copy is damaged
                    // instead of pretending the thread is empty.
                    e.printStackTrace()
                    placeholderMessage(row, fallbackAddress, placeholderText)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Delete every decrypted locked-media file, whatever session it was from. */
    fun clearDecryptedMedia() {
        LockedMediaCache.clearAll(context)
    }

    /** Every locked message as its backup object — the export path. */
    fun getAllLockedBackups(): List<MessagesBackup> {
        return try {
            val key = LockerKey.getOrCreateKey()
            context.lockedMessagesDB.getAll().mapNotNull { row ->
                try {
                    decryptBackup(row, key)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Delete a thread's rows from the shared provider database only — the conversation row survives. */
    private fun deleteThreadFromProvider(threadId: Long) {
        val selection = "${Sms.THREAD_ID} = ?"
        val selectionArgs = arrayOf(threadId.toString())
        context.contentResolver.delete(Sms.CONTENT_URI, selection, selectionArgs)
        context.contentResolver.delete(Mms.CONTENT_URI, selection, selectionArgs)
    }

    private fun encryptBackup(
        backup: MessagesBackup,
        providerId: Long,
        isMMS: Boolean,
        threadId: Long,
        dateMillis: Long,
        key: SecretKey,
        fallbackAddress: String = ""
    ): Pair<LockedMessage, List<LockedMessageChunk>> {
        // One Locker-native record: the backup as the metadata face
        // (unlock restores from it exactly as before) plus the
        // display envelope derived now, while the thread's address
        // is still at hand.
        val record = LockedRecord(backup, deriveDisplay(backup, fallbackAddress))
        val plaintext = json.encodeToString<LockedRecord>(record).toByteArray(Charsets.UTF_8)
        val blob = LockerCrypto.encrypt(plaintext, key)
        val ciphertext = Base64.encodeToString(blob.ciphertext, Base64.NO_WRAP)
        val row = LockedMessage(
            id = providerId,
            threadId = threadId,
            isMMS = isMMS,
            date = dateMillis,
            iv = Base64.encodeToString(blob.iv, Base64.NO_WRAP)
        )
        return row to chunkCiphertext(providerId, isMMS, ciphertext)
    }

    private fun decryptBackup(row: LockedMessage, key: SecretKey): MessagesBackup {
        return decryptRecord(row, key).backup
    }

    /**
     * Decrypt one row to its full record. Old rows (locked before
     * 1.10.17) hold a bare backup; [decodeRecord] derives their
     * envelope in memory, so both shapes read through this one seam.
     */
    private fun decryptRecord(row: LockedMessage, key: SecretKey, fallbackAddress: String = ""): LockedRecord {
        // The pieces are loaded for this one message only, in order,
        // and joined back into the exact Base64 the row used to carry.
        val ciphertext = joinCiphertext(
            context.lockedMessageChunksDB.getByMessage(row.id, row.isMMS).map { it.chunk }
        )
        val blob = LockerCrypto.EncryptedBlob(
            iv = Base64.decode(row.iv, Base64.NO_WRAP),
            ciphertext = Base64.decode(ciphertext, Base64.NO_WRAP)
        )
        val plaintext = LockerCrypto.decrypt(blob, key).toString(Charsets.UTF_8)
        return decodeRecord(plaintext, fallbackAddress)
    }

    companion object {
        /**
         * One ciphertext piece is at most this many Base64 chars.
         * 256KB a piece: a single row can never approach the 2MB
         * CursorWindow, and the pieces of even a multi-MB photo page
         * through it safely.
         */
        const val CIPHER_CHUNK_SIZE = 262144

        private val recordJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /**
         * Decode one decrypted row's plaintext to its record. A
         * 1.10.17 row carries a LockedRecord (it has a top-level
         * "display" key); an older row carries a bare MessagesBackup
         * and its envelope is derived here, in memory, with the
         * same derivation the writer uses — so old rows display
         * exactly like new ones without any re-encryption. Pure —
         * unit-tests run it on the JVM.
         */
        fun decodeRecord(plaintext: String, fallbackAddress: String = ""): LockedRecord {
            val element = recordJson.parseToJsonElement(plaintext).jsonObject
            return if (element.containsKey("display")) {
                recordJson.decodeFromString<LockedRecord>(plaintext)
            } else {
                val backup = recordJson.decodeFromString<MessagesBackup>(plaintext)
                LockedRecord(backup, deriveDisplay(backup, fallbackAddress))
            }
        }

        /**
         * The display envelope for a backup, derived once — at lock
         * time by the writer, in memory for older rows by the reader.
         * [fallbackAddress] is the thread's conversation address: it
         * stands in wherever the backup has no address of its own,
         * so a received message's sender and a thread's participants
         * are never empty. A sent MMS keeps an empty sender (the
         * message is the user's own), but still takes the fallback
         * as its participants. Pure — unit-tests run it on the JVM.
         */
        fun deriveDisplay(backup: MessagesBackup, fallbackAddress: String = ""): LockedDisplay {
            val fallbackParticipants = fallbackAddress
                .split(ADDRESS_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return when (backup) {
                is SmsBackup -> {
                    val sender = backup.address.ifEmpty { fallbackAddress }
                    LockedDisplay(
                        sender = sender,
                        body = backup.body ?: "",
                        participants = if (backup.address.isNotEmpty()) {
                            listOf(backup.address)
                        } else {
                            fallbackParticipants
                        }
                    )
                }

                is MmsBackup -> {
                    val isSent = backup.messageBox == Mms.MESSAGE_BOX_SENT ||
                        backup.messageBox == Mms.MESSAGE_BOX_FAILED
                    val sender = if (isSent) {
                        ""
                    } else {
                        backup.addresses.firstOrNull { it.type == PduHeaders.FROM }?.address
                            ?: backup.addresses.firstOrNull()?.address
                            ?: fallbackAddress
                    }
                    val participants = backup.addresses.map { it.address }
                        .filter { it.isNotEmpty() }
                        .ifEmpty { fallbackParticipants }
                    val attachments = backup.parts.mapIndexedNotNull { index, part ->
                        if (part.isNonText()) {
                            LockedDisplayAttachment(
                                partIndex = index,
                                mimeType = part.contentType,
                                fileName = part.filename ?: part.name ?: ""
                            )
                        } else {
                            null
                        }
                    }
                    LockedDisplay(
                        sender = sender,
                        body = mmsDisplayText(backup.parts),
                        participants = participants,
                        attachments = attachments
                    )
                }
            }
        }

        /**
         * The stand-in for a row that will not decrypt: the thread
         * shows that this message's locked copy is damaged instead
         * of pretending the thread is empty. It carries the row's
         * own id, date, thread, and kind, and the fallback address
         * as its participant, so it sorts and renders like any
         * other message. Pure — unit-tests run it on the JVM. (No
         * contact lookup here: a damaged row gets the number as
         * its name, which is all the thread header needs.)
         */
        fun placeholderMessage(row: LockedMessage, fallbackAddress: String, placeholderText: String): Message {
            val participants = if (fallbackAddress.isNotEmpty()) {
                fallbackAddress.split(ADDRESS_SEPARATOR).map { number ->
                    SimpleContact(
                        rawId = 0,
                        contactId = 0,
                        name = number.trim(),
                        photoUri = "",
                        phoneNumbers = arrayListOf(PhoneNumber(number.trim(), 0, "", number.trim())),
                        birthdays = ArrayList(),
                        anniversaries = ArrayList()
                    )
                }.let { ArrayList(it) }
            } else {
                arrayListOf(
                    SimpleContact(
                        rawId = 0,
                        contactId = 0,
                        name = "",
                        photoUri = "",
                        phoneNumbers = arrayListOf(PhoneNumber("", 0, "", "")),
                        birthdays = ArrayList(),
                        anniversaries = ArrayList()
                    )
                )
            }
            return Message(
                id = row.id,
                body = placeholderText,
                type = 1,
                status = -1,
                participants = participants,
                date = (row.date / 1000L).toInt(),
                read = true,
                threadId = row.threadId,
                isMMS = row.isMMS,
                attachment = null,
                senderPhoneNumber = fallbackAddress,
                senderName = fallbackAddress,
                senderPhotoUri = "",
                subscriptionId = 0
            )
        }

        /**
         * A locked MMS's display text, from its text parts. MMS text
         * lives in the TEXT column ([MmsPart.text]); the part stream
         * ([MmsPart.data]) is empty for text parts — reading `data`
         * is why locked MMS showed the attachment label but no text
         * (1.10.16). `data` stays as the fallback for parts whose
         * text came through the stream instead. A part is a text
         * part when it is not a media part (see [MmsPart.isNonText],
         * which counts vCards as media) and not the SMIL
         * presentation. Pure — unit-tests run it on the JVM.
         */
        fun mmsDisplayText(parts: List<MmsPart>): String {
            return parts.filter { !it.isNonText() && it.contentType.lowercase() != "application/smil" }
                .joinToString("\n") { it.text ?: it.data ?: "" }
        }

        /** Split a Base64 ciphertext into [CIPHER_CHUNK_SIZE] pieces. */
        fun chunkCiphertext(id: Long, isMMS: Boolean, ciphertext: String): List<LockedMessageChunk> {
            if (ciphertext.isEmpty()) {
                return listOf(LockedMessageChunk(id, isMMS, 0, ""))
            }
            return ciphertext.chunked(CIPHER_CHUNK_SIZE).mapIndexed { index, piece ->
                LockedMessageChunk(id, isMMS, index, piece)
            }
        }

        /** Join pieces (in order) back into the original ciphertext. */
        fun joinCiphertext(chunks: List<String>): String {
            return chunks.joinToString("")
        }
    }

    /**
     * Build the display Message for a decrypted record, mirroring
     * what getMessages/getMMS build from the provider — except the
     * sender, body, participants, and attachment list come from the
     * record's display envelope, never re-derived from the backup's
     * provider-shaped fields. The backup is still the source for the
     * media bytes themselves (MmsPart.mediaBytes is the one seam)
     * and for the type/status/subscription the adapter expects.
     * A part that cannot be materialized keeps the naming label as
     * fallback, as before.
     */
    private fun toDisplayMessage(record: LockedRecord, row: LockedMessage): Message {
        val backup = record.backup
        val display = record.display
        return when (backup) {
            is SmsBackup -> {
                val namePhoto = if (display.sender.isNotEmpty()) {
                    context.getNameAndPhotoFromPhoneNumber(display.sender)
                } else {
                    null
                }
                Message(
                    id = row.id,
                    body = display.body,
                    type = backup.type,
                    status = backup.status,
                    participants = display.participants.flatMap { participantsFromAddress(it) }
                        .let { ArrayList(it) },
                    date = (backup.date / 1000L).toInt(),
                    read = backup.read == 1,
                    threadId = row.threadId,
                    isMMS = false,
                    attachment = null,
                    senderPhoneNumber = display.sender,
                    senderName = namePhoto?.name ?: "",
                    senderPhotoUri = namePhoto?.photoUri ?: "",
                    subscriptionId = backup.subscriptionId.toInt()
                )
            }

            is MmsBackup -> {
                val namePhoto = if (display.sender.isNotEmpty()) {
                    context.getNameAndPhotoFromPhoneNumber(display.sender)
                } else {
                    null
                }
                val attachments = ArrayList<Attachment>()
                val body = buildString {
                    append(display.body)
                    display.attachments.forEach { descriptor ->
                        // Inline (1.10.16): a part that materializes
                        // renders like a normal attachment; one that
                        // cannot keeps the naming label as fallback.
                        // The descriptor's index is into the backup's
                        // parts list; a corrupt index falls back to
                        // the label rather than crashing the view.
                        val part = backup.parts.getOrNull(descriptor.partIndex)
                        val attachment = part?.let {
                            materializeMediaPart(it, row.id, descriptor.partIndex)
                        }
                        if (attachment != null) {
                            attachments.add(attachment)
                        } else {
                            val label = descriptor.fileName.ifEmpty { descriptor.mimeType }
                            append("\n[Locked attachment: $label (${descriptor.mimeType})]")
                        }
                    }
                }
                Message(
                    id = row.id,
                    body = body,
                    type = backup.messageBox,
                    status = 0,
                    participants = display.participants.flatMap { participantsFromAddress(it) }
                        .let { ArrayList(it) },
                    date = backup.date.toInt(),
                    read = backup.read == 1,
                    threadId = row.threadId,
                    isMMS = true,
                    attachment = MessageAttachment(row.id, display.body, attachments),
                    senderPhoneNumber = display.sender,
                    senderName = namePhoto?.name ?: "",
                    senderPhotoUri = namePhoto?.photoUri ?: "",
                    subscriptionId = backup.subscriptionId.toInt()
                )
            }
        }
    }

    /**
     * Decrypt a locked media part into the session media cache and
     * build the Attachment the thread adapter renders inline. Null
     * when the part has no bytes or the write fails — the caller
     * falls back to the naming label. Runs only behind the gate:
     * getLockedMessages is reached solely from the gated thread view.
     */
    private fun materializeMediaPart(part: MmsPart, messageId: Long, index: Int): Attachment? {
        // One seam for part bytes — see MmsPart.mediaBytes. It
        // knows a vCard's data may be raw text (rows locked before
        // this fix) or Base64 (everything since).
        val bytes = part.mediaBytes() ?: return null
        if (bytes.isEmpty()) {
            return null
        }
        val uri = LockedMediaCache.writePart(context, messageId, index, bytes, part.contentType)
            ?: return null
        return Attachment(
            id = index.toLong(),
            messageId = messageId,
            uriString = uri.toString(),
            mimetype = part.contentType,
            width = 0,
            height = 0,
            filename = part.filename ?: part.name ?: ""
        ).apply { isLockedMedia = true }
    }

    private fun participantsFromAddress(address: String): ArrayList<SimpleContact> {
        return address.split(ADDRESS_SEPARATOR).map { number ->
            val photo = context.getNameAndPhotoFromPhoneNumber(number)
            SimpleContact(
                rawId = 0,
                contactId = 0,
                name = photo.name,
                photoUri = photo.photoUri ?: "",
                phoneNumbers = arrayListOf(PhoneNumber(number, 0, "", number)),
                birthdays = ArrayList(),
                anniversaries = ArrayList()
            )
        }.let { ArrayList(it) }
    }
}
