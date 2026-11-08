package org.fossify.messages.helpers

import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Base64
import com.google.android.mms.pdu_alt.PduHeaders
import kotlinx.serialization.json.Json
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.deleteMessage
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.lockedMessagesDB
import org.fossify.messages.extensions.lockedThreadsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.messaging.MessagingUtils.Companion.ADDRESS_SEPARATOR
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
     */
    fun unlockThread(threadId: Long): Boolean {
        val locked = context.lockedThreadsDB.get(threadId) ?: return false

        return try {
            val writer = MessagesWriter(context)
            val key = LockerKey.getOrCreateKey()
            val rows = context.lockedMessagesDB.getByThread(threadId)

            rows.forEach { row ->
                when (val backup = decryptBackup(row, key)) {
                    is SmsBackup -> writer.writeSmsMessage(backup)
                    is MmsBackup -> writer.writeMmsMessage(backup)
                }
            }

            context.lockedMessagesDB.deleteByThread(threadId)
            context.lockedThreadsDB.delete(threadId)
            context.conversationsDB.updateMailboxManuallyFiled(threadId, locked.previousMailbox)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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
