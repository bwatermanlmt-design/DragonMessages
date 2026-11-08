package org.fossify.messages.helpers

import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Base64
import com.google.android.mms.pdu_alt.PduHeaders
import kotlinx.serialization.json.Json
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.filtering.LockerCrypto
import org.fossify.messages.extensions.deleteMessage
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.lockedMessagesDB
import org.fossify.messages.extensions.lockedThreadsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.messaging.MessagingUtils.Companion.ADDRESS_SEPARATOR
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.LockedMessage
import org.fossify.messages.models.LockedThread
import org.fossify.messages.models.Mailbox
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessagesBackup
import org.fossify.messages.models.MmsBackup
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

            reader.getSmsBackupsForThread(threadId).forEach { (id, backup) ->
                rows.add(encryptBackup(backup, id, isMMS = false, threadId, backup.date, key))
            }
            reader.getMmsBackupsForThread(threadId).forEach { (id, backup) ->
                // MMS dates are seconds; the store orders on millis.
                rows.add(encryptBackup(backup, id, isMMS = true, threadId, backup.date * 1000L, key))
            }

            // Store first, delete second: a failure here leaves the
            // thread untouched in the provider.
            if (rows.isNotEmpty()) {
                context.lockedMessagesDB.insertAll(rows)
            }
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
                    is MmsBackup -> backup.parts.filter { it.contentType.startsWith("text/") }
                        .joinToString("\n") { it.data ?: "" }
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
            if (restoredThreadId != null && restoredThreadId != 0L) {
                // The provider files restored messages under a NEW thread
                // id, so no Room row exists for it yet: setting the
                // mailbox on a missing row updates nothing, and the later
                // sync files the fresh thread as contacts. Seed the row
                // from the locked thread — same conversation, new id —
                // with its previous mailbox, so the restore lands where
                // the thread came from.
                val lockedConv = context.conversationsDB.getConversationWithThreadId(threadId)
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
            context.conversationsDB.deleteThreadId(threadId)

            context.lockedMessagesDB.deleteByThread(threadId)
            context.lockedThreadsDB.delete(threadId)
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
            val row = if (isMMS) {
                val (id, backup) = reader.getMmsBackupById(messageId) ?: return false
                encryptBackup(backup, id, isMMS = true, threadId, backup.date * 1000L, key)
            } else {
                val (id, backup) = reader.getSmsBackupById(messageId) ?: return false
                encryptBackup(backup, id, isMMS = false, threadId, backup.date, key)
            }

            context.lockedMessagesDB.insert(row)
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
        return try {
            val key = LockerKey.getOrCreateKey()
            context.lockedMessagesDB.getByThread(threadId).mapNotNull { row ->
                try {
                    toDisplayMessage(decryptBackup(row, key), row)
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
        key: SecretKey
    ): LockedMessage {
        val plaintext = json.encodeToString<MessagesBackup>(backup).toByteArray(Charsets.UTF_8)
        val blob = LockerCrypto.encrypt(plaintext, key)
        return LockedMessage(
            id = providerId,
            threadId = threadId,
            isMMS = isMMS,
            date = dateMillis,
            iv = Base64.encodeToString(blob.iv, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(blob.ciphertext, Base64.NO_WRAP)
        )
    }

    private fun decryptBackup(row: LockedMessage, key: SecretKey): MessagesBackup {
        val blob = LockerCrypto.EncryptedBlob(
            iv = Base64.decode(row.iv, Base64.NO_WRAP),
            ciphertext = Base64.decode(row.ciphertext, Base64.NO_WRAP)
        )
        val plaintext = LockerCrypto.decrypt(blob, key).toString(Charsets.UTF_8)
        return json.decodeFromString<MessagesBackup>(plaintext)
    }

    /**
     * Build the display Message for a decrypted backup, mirroring what
     * getMessages/getMMS build from the provider. MMS attachments stay
     * encrypted: the bubble shows the text plus a line naming each
     * locked attachment (unlock the thread to view it).
     */
    private fun toDisplayMessage(backup: MessagesBackup, row: LockedMessage): Message {
        return when (backup) {
            is SmsBackup -> {
                val address = backup.address
                val namePhoto = context.getNameAndPhotoFromPhoneNumber(address)
                Message(
                    id = row.id,
                    body = backup.body ?: "",
                    type = backup.type,
                    status = backup.status,
                    participants = participantsFromAddress(address),
                    date = (backup.date / 1000L).toInt(),
                    read = backup.read == 1,
                    threadId = row.threadId,
                    isMMS = false,
                    attachment = null,
                    senderPhoneNumber = address,
                    senderName = namePhoto.name,
                    senderPhotoUri = namePhoto.photoUri ?: "",
                    subscriptionId = backup.subscriptionId.toInt()
                )
            }

            is MmsBackup -> {
                val isSent = backup.messageBox == Mms.MESSAGE_BOX_SENT ||
                    backup.messageBox == Mms.MESSAGE_BOX_FAILED
                val senderAddress = if (isSent) {
                    ""
                } else {
                    backup.addresses.firstOrNull { it.type == PduHeaders.FROM }?.address
                        ?: backup.addresses.firstOrNull()?.address ?: ""
                }
                val namePhoto = if (senderAddress.isNotEmpty()) {
                    context.getNameAndPhotoFromPhoneNumber(senderAddress)
                } else {
                    null
                }
                val textParts = backup.parts.filter { it.contentType.startsWith("text/") }
                val mediaParts = backup.parts.filter { it.isNonText() }
                val body = buildString {
                    append(textParts.joinToString("\n") { it.data ?: "" })
                    mediaParts.forEach { part ->
                        val label = part.filename ?: part.name ?: part.contentType
                        append("\n[Locked attachment: $label (${part.contentType})]")
                    }
                }
                Message(
                    id = row.id,
                    body = body,
                    type = backup.messageBox,
                    status = 0,
                    participants = backup.addresses.map { address ->
                        val photo = context.getNameAndPhotoFromPhoneNumber(address.address)
                        SimpleContact(
                            rawId = 0,
                            contactId = 0,
                            name = photo.name,
                            photoUri = photo.photoUri ?: "",
                            phoneNumbers = arrayListOf(
                                PhoneNumber(address.address, 0, "", address.address)
                            ),
                            birthdays = ArrayList(),
                            anniversaries = ArrayList()
                        )
                    }.let { ArrayList(it) },
                    date = backup.date.toInt(),
                    read = backup.read == 1,
                    threadId = row.threadId,
                    isMMS = true,
                    attachment = null,
                    senderPhoneNumber = senderAddress,
                    senderName = namePhoto?.name ?: "",
                    senderPhotoUri = namePhoto?.photoUri ?: "",
                    subscriptionId = backup.subscriptionId.toInt()
                )
            }
        }
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
