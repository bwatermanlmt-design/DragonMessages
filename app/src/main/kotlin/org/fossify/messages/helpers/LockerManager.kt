package org.fossify.messages.helpers

import android.content.Context
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Base64
import com.google.android.mms.pdu_alt.PduHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    /**
     * One message's worth of lock work: the backup plus the row
     * fields it becomes. 1.10.19: the encrypts fan out over the
     * crypto pool (see parallelMap), so the job carries everything
     * the transform needs and nothing it must share.
     */
    private data class EncryptJob(
        val backup: MessagesBackup,
        val providerId: Long,
        val isMMS: Boolean,
        val dateMillis: Long
    )

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
            // 1.10.19: the per-message encrypts are independent (own
            // IV, own Cipher per call), so they fan out over the
            // crypto pool and come back in input order. The store
            // writes and the provider delete below still run
            // sequentially, in that same order, on this thread — and
            // a failure anywhere aborts before anything is stored,
            // so the thread is unchanged, exactly as before.
            val jobs = mutableListOf<EncryptJob>()
            reader.getSmsBackupsForThread(threadId).forEach { (id, backup) ->
                jobs.add(EncryptJob(backup, id, isMMS = false, backup.date))
            }
            reader.getMmsBackupsForThread(threadId).forEach { (id, backup) ->
                // MMS dates are seconds; the store orders on millis.
                jobs.add(EncryptJob(backup, id, isMMS = true, backup.date * 1000L))
            }
            val encrypted = parallelMap(jobs) { job ->
                encryptBackup(job.backup, job.providerId, job.isMMS, threadId, job.dateMillis, key, conversation.phoneNumber)
            }
            val rows = encrypted.map { it.first }
            val allChunks = encrypted.flatMap { it.second }

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

            // 1.10.19: the per-message decrypts are independent, so
            // they fan out over the crypto pool and come back in row
            // order. The provider writes below still run one at a
            // time, in that order, on this thread — and a decrypt
            // failure now aborts before anything is written, instead
            // of leaving a partial restore behind.
            val backups = parallelMap(rows) { row -> decryptBackup(row, key) }

            var firstBackup: MessagesBackup? = null
            var lastSnippet = ""
            var lastDateSeconds = 0
            rows.zip(backups).forEach { (_, backup) ->
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
    /**
     * The thread view's fast load (1.10.18): each row shows from its
     * separate envelope ciphertext — a few hundred bytes — so the
     * thread renders in milliseconds, and the multi-MB backup
     * decrypts lazily behind it, for the media (see
     * [materializeLockedMedia]). A row whose media is already
     * materialized for this session comes back whole from the cache.
     * Rows locked before 1.10.18 have no envelope: they take the
     * old full-decrypt path once, then upgrade in place (the envelope
     * is written alongside the untouched backup ciphertext), so the
     * next open is fast.
     */
    fun getLockedMessages(threadId: Long): List<Message> {
        // A dead process leaves decrypted media files behind; the
        // next locked view load sweeps every session but this one.
        // The materialized cache points at this session's files, so
        // it goes with them.
        LockedMediaCache.clearStaleSessions(context)
        clearMaterialized()
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
                    materializedMessage(row) ?: run {
                        // The envelope first: a few hundred bytes.
                        // A missing or undecryptable envelope falls
                        // through to the full record below.
                        val display = if (row.envelopeCiphertext != null) {
                            try {
                                decryptEnvelope(row, key)
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                        if (display != null) {
                            messageShell(display, row)
                        } else {
                            // Old shape: full decrypt now (slow once),
                            // then upgrade the row in place.
                            val record = decryptRecord(row, key, fallbackAddress)
                            runCatching {
                                val (envelopeIv, envelopeCiphertext) = encryptEnvelope(record.display, key)
                                context.lockedMessagesDB.updateEnvelope(row.id, row.isMMS, envelopeIv, envelopeCiphertext)
                            }
                            toDisplayMessage(record, row)
                        }
                    }
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

    /**
     * The lazy half of the fast load: for each row with attachments,
     * decrypt the full record, materialize its media, and hand the
     * whole message to [onMessageReady] — the thread view swaps it
     * into the adapter as it lands, so the photo pops in behind the
     * text instead of holding the whole thread up. Runs on the
     * caller's background thread; the decrypts fan out over the
     * crypto pool (1.10.19) and the callback fires per message, in
     * row order. Rows already materialized for this session, and rows
     * with no attachments, are skipped. A row that will not decrypt
     * is skipped silently — the shell already shows its text, and
     * the placeholder path in [getLockedMessages] covers the damage.
     */
    fun materializeLockedMedia(threadId: Long, onMessageReady: (Message) -> Unit) {
        val fallbackAddress = try {
            context.conversationsDB.getConversationWithThreadId(threadId)?.phoneNumber ?: ""
        } catch (e: Exception) {
            ""
        }
        try {
            val key = LockerKey.getOrCreateKey()
            val pending = context.lockedMessagesDB.getByThread(threadId).filter { row ->
                if (materializedMessage(row) != null) {
                    return@filter false
                }
                val display = try {
                    decryptEnvelope(row, key)
                } catch (e: Exception) {
                    null
                }
                // An SMS has no media, and an old-shape row was
                // already materialized by the fast load itself.
                display != null && display.attachments.isNotEmpty()
            }
            // 1.10.19: the full-record decrypts fan out over the
            // crypto pool; the messages still land in the adapter
            // in row order, on this thread. A row that will not
            // decrypt is skipped silently — the shell already shows
            // its text, and the placeholder path in
            // [getLockedMessages] covers the damage.
            val messages = parallelMap(pending) { row ->
                try {
                    val record = decryptRecord(row, key, fallbackAddress)
                    row to toDisplayMessage(record, row)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            messages.forEach { pair ->
                if (pair != null) {
                    putMaterialized(pair.first, pair.second)
                    onMessageReady(pair.second)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * One-time envelope upgrade, run in the background at app start:
     * every row locked before 1.10.18 gets its envelope written
     * alongside its untouched backup ciphertext, so its first thread
     * open after the update is already fast. A failure on one row
     * leaves that row exactly as it was — it upgrades on first read
     * instead. No-op when nothing is left to upgrade.
     */
    fun upgradeEnvelopes() {
        try {
            if (context.lockedMessagesDB.countWithoutEnvelope() == 0) {
                return
            }
            val key = LockerKey.getOrCreateKey()
            val rows = context.lockedMessagesDB.getWithoutEnvelope()
            // 1.10.19: the decrypts fan out over the crypto pool;
            // the envelope writes still run one at a time, in row
            // order, on this thread. A failure on one row leaves
            // that row exactly as it was.
            val upgrades = parallelMap(rows) { row ->
                try {
                    val record = decryptRecord(row, key)
                    val (envelopeIv, envelopeCiphertext) = encryptEnvelope(record.display, key)
                    Triple(row.id, row.isMMS, envelopeIv to envelopeCiphertext)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            upgrades.forEach { upgrade ->
                if (upgrade != null) {
                    runCatching {
                        context.lockedMessagesDB.updateEnvelope(upgrade.first, upgrade.second, upgrade.third.first, upgrade.third.second)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Delete every decrypted locked-media file, whatever session it was from. */
    fun clearDecryptedMedia() {
        LockedMediaCache.clearAll(context)
        clearMaterialized()
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
        // 1.10.18: the envelope is ALSO its own small ciphertext,
        // alongside the record. The thread view decrypts only this
        // (a few hundred bytes) to show the thread; the multi-MB
        // record decrypts lazily, behind it, for the media. Same
        // key, fresh IV; the backup ciphertext is untouched, so
        // unlock restores exactly as before.
        val envelopePlaintext = json.encodeToString<LockedDisplay>(record.display).toByteArray(Charsets.UTF_8)
        val envelopeBlob = LockerCrypto.encrypt(envelopePlaintext, key)
        val row = LockedMessage(
            id = providerId,
            threadId = threadId,
            isMMS = isMMS,
            date = dateMillis,
            iv = Base64.encodeToString(blob.iv, Base64.NO_WRAP),
            envelopeIv = Base64.encodeToString(envelopeBlob.iv, Base64.NO_WRAP),
            envelopeCiphertext = Base64.encodeToString(envelopeBlob.ciphertext, Base64.NO_WRAP)
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

    /**
     * Decrypt one row's separate envelope (1.10.18): a few hundred
     * bytes of LockedDisplay JSON, not the multi-MB record. Null
     * when the row has no envelope yet (locked before 1.10.18) —
     * the caller falls back to the full decrypt and upgrades the
     * row. Throws on a wrong key or tampered data, like decrypt.
     */
    private fun decryptEnvelope(row: LockedMessage, key: SecretKey): LockedDisplay? {
        val envelopeIv = row.envelopeIv ?: return null
        val envelopeCiphertext = row.envelopeCiphertext ?: return null
        val blob = LockerCrypto.EncryptedBlob(
            iv = Base64.decode(envelopeIv, Base64.NO_WRAP),
            ciphertext = Base64.decode(envelopeCiphertext, Base64.NO_WRAP)
        )
        val plaintext = LockerCrypto.decrypt(blob, key).toString(Charsets.UTF_8)
        return json.decodeFromString<LockedDisplay>(plaintext)
    }

    /**
     * Encrypt one display envelope under [key], for the upgrade in
     * place: the row's own backup already decrypted it, so the
     * envelope is written alongside the untouched backup ciphertext.
     * Returns the (iv, ciphertext) Base64 pair.
     */
    private fun encryptEnvelope(display: LockedDisplay, key: SecretKey): Pair<String, String> {
        val plaintext = json.encodeToString<LockedDisplay>(display).toByteArray(Charsets.UTF_8)
        val blob = LockerCrypto.encrypt(plaintext, key)
        return Base64.encodeToString(blob.iv, Base64.NO_WRAP) to
            Base64.encodeToString(blob.ciphertext, Base64.NO_WRAP)
    }

    companion object {
        /**
         * One ciphertext piece is at most this many Base64 chars.
         * 256KB a piece: a single row can never approach the 2MB
         * CursorWindow, and the pieces of even a multi-MB photo page
         * through it safely.
         */
        const val CIPHER_CHUNK_SIZE = 262144

        /**
         * The materialized messages for this gate session: whole
         * Messages (shell + media) keyed by the row's composite key.
         * The files they point at live in this session's media dir,
         * so the cache is cleared whenever the session is swept
         * (getLockedMessages) or the media is wiped
         * (clearDecryptedMedia). A refresh that rebuilds the list
         * from envelopes finds the whole message here instead of a
         * bare shell. LockerManager instances are created fresh at
         * every call site, so the cache has to live here, not on an
         * instance.
         */
        private val materializedMessages = java.util.concurrent.ConcurrentHashMap<String, Message>()

        fun materializedMessage(row: LockedMessage): Message? =
            materializedMessages["${row.id}:${row.isMMS}"]

        fun putMaterialized(row: LockedMessage, message: Message) {
            materializedMessages["${row.id}:${row.isMMS}"] = message
        }

        fun clearMaterialized() {
            materializedMessages.clear()
        }

        /**
         * 1.10.19: the per-message crypto is independent — one
         * AES/GCM ciphertext per message, a fresh Cipher per call —
         * so lock/unlock fan the messages out over a small pool
         * instead of doing them one at a time. Results always come
         * back in input order; the Room and provider writes stay
         * sequential, on the calling thread. The pool lives for the
         * process lifetime, like the commons background executor.
         * Capped at 4: the Keystore may serialize hardware-backed
         * operations internally, so more workers would not help.
         */
        private val cryptoPool: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        )

        /**
         * Run [transform] over [items] in parallel, results in input
         * order. Throws the first failure (wrapped in
         * ExecutionException by the Future) — callers that must not
         * fail the whole batch catch per item inside [transform].
         */
        internal fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R> {
            if (items.size <= 1) {
                return items.map(transform)
            }
            val futures = items.map { item -> cryptoPool.submit(Callable { transform(item) }) }
            return futures.map { it.get() }
        }

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
            val element = recordJson.decodeFromString<JsonObject>(plaintext)
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
                        },
                        type = backup.type,
                        status = backup.status,
                        read = backup.read == 1,
                        subscriptionId = backup.subscriptionId.toInt()
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
                        attachments = attachments,
                        type = backup.messageBox,
                        status = 0,
                        read = backup.read == 1,
                        subscriptionId = backup.subscriptionId.toInt()
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
     * The message shell from the envelope alone (1.10.18): sender,
     * body, participants, and the type/status/read/subscription the
     * adapter expects all come from the display face — the backup
     * is never touched. Media is not here yet: an MMS shell carries
     * an empty attachment list, and the media pass fills it in
     * behind (see [materializeLockedMedia]). The date comes from
     * the row (millis for both kinds — lockThread stores MMS dates
     * as millis), so the shell sorts exactly like the full message.
     */
    private fun messageShell(display: LockedDisplay, row: LockedMessage): Message {
        val namePhoto = if (display.sender.isNotEmpty()) {
            context.getNameAndPhotoFromPhoneNumber(display.sender)
        } else {
            null
        }
        return Message(
            id = row.id,
            body = display.body,
            type = display.type,
            status = display.status,
            participants = display.participants.flatMap { participantsFromAddress(it) }
                .let { ArrayList(it) },
            date = (row.date / 1000L).toInt(),
            read = display.read,
            threadId = row.threadId,
            isMMS = row.isMMS,
            attachment = if (row.isMMS) MessageAttachment(row.id, display.body, ArrayList()) else null,
            senderPhoneNumber = display.sender,
            senderName = namePhoto?.name ?: "",
            senderPhotoUri = namePhoto?.photoUri ?: "",
            subscriptionId = display.subscriptionId
        )
    }

    /**
     * Build the whole display Message for a decrypted record: the
     * shell from its envelope, plus the media materialized from the
     * backup's parts (MmsPart.mediaBytes is the one seam for the
     * bytes). A part that cannot be materialized keeps the naming
     * label as fallback, as before. SMS has no media, so it is
     * just the shell.
     */
    private fun toDisplayMessage(record: LockedRecord, row: LockedMessage): Message {
        val backup = record.backup
        val display = record.display
        if (backup is SmsBackup) {
            return messageShell(display, row)
        }
        // MMS: the shell, with the attachments filled in.
        val mmsBackup = backup as MmsBackup
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
                val part = mmsBackup.parts.getOrNull(descriptor.partIndex)
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
        return messageShell(display, row).copy(
            body = body,
            attachment = MessageAttachment(row.id, display.body, attachments)
        )
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
