package org.fossify.messages.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Base64
import org.fossify.commons.extensions.getIntValue
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.getStringValueOrNull
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.messages.extensions.getConversationIds
import org.fossify.messages.models.MessagesBackup
import org.fossify.messages.models.MmsAddress
import org.fossify.messages.models.MmsBackup
import org.fossify.messages.models.MmsPart
import org.fossify.messages.models.SmsBackup
import java.io.IOException
import java.io.InputStream

class MessagesReader(private val context: Context) {

    fun getMessagesToExport(
        getSms: Boolean, getMms: Boolean, callback: (messages: List<MessagesBackup>) -> Unit
    ) {
        val conversationIds = context.getConversationIds()
        var smsMessages = listOf<SmsBackup>()
        var mmsMessages = listOf<MmsBackup>()

        if (getSms) {
            smsMessages = getSmsMessages(conversationIds)
        }
        if (getMms) {
            mmsMessages = getMmsMessages(conversationIds)
        }
        callback(smsMessages + mmsMessages)
    }

    private fun getSmsMessages(threadIds: List<Long>): List<SmsBackup> {
        return threadIds.flatMap { threadId ->
            getSmsBackupsForThread(threadId).map { it.second }
        }
    }

    /**
     * The Locker's lock path: one thread's SMS, each paired with its
     * provider id, so the encrypted row can be keyed to the message it
     * replaces (SmsBackup itself carries no id).
     */
    fun getSmsBackupsForThread(threadId: Long): List<Pair<Long, SmsBackup>> {
        val projection = arrayOf(
            Sms._ID,
            Sms.SUBSCRIPTION_ID,
            Sms.ADDRESS,
            Sms.BODY,
            Sms.DATE,
            Sms.DATE_SENT,
            Sms.LOCKED,
            Sms.PROTOCOL,
            Sms.READ,
            Sms.STATUS,
            Sms.TYPE,
            Sms.SERVICE_CENTER
        )

        val selection = "${Sms.THREAD_ID} = ?"
        val smsList = mutableListOf<Pair<Long, SmsBackup>>()
        context.queryCursor(
            uri = Sms.CONTENT_URI,
            projection = projection,
            selection = selection,
            selectionArgs = arrayOf(threadId.toString())
        ) { cursor ->
            smsList.add(cursor.getLongValue(Sms._ID) to smsBackupFromCursor(cursor))
        }
        return smsList
    }

    /** The Locker's single-message lock path: one SMS by provider id. */
    fun getSmsBackupById(id: Long): Pair<Long, SmsBackup>? {
        val projection = arrayOf(
            Sms._ID,
            Sms.SUBSCRIPTION_ID,
            Sms.ADDRESS,
            Sms.BODY,
            Sms.DATE,
            Sms.DATE_SENT,
            Sms.LOCKED,
            Sms.PROTOCOL,
            Sms.READ,
            Sms.STATUS,
            Sms.TYPE,
            Sms.SERVICE_CENTER
        )

        var result: Pair<Long, SmsBackup>? = null
        context.queryCursor(
            uri = Sms.CONTENT_URI,
            projection = projection,
            selection = "${Sms._ID} = ?",
            selectionArgs = arrayOf(id.toString())
        ) { cursor ->
            result = cursor.getLongValue(Sms._ID) to smsBackupFromCursor(cursor)
        }
        return result
    }

    private fun smsBackupFromCursor(cursor: android.database.Cursor): SmsBackup {
        return SmsBackup(
            subscriptionId = cursor.getLongValue(Sms.SUBSCRIPTION_ID),
            address = cursor.getStringValue(Sms.ADDRESS),
            body = cursor.getStringValueOrNull(Sms.BODY),
            date = cursor.getLongValue(Sms.DATE),
            dateSent = cursor.getLongValue(Sms.DATE_SENT),
            locked = cursor.getIntValue(Sms.LOCKED),
            protocol = cursor.getStringValueOrNull(Sms.PROTOCOL),
            read = cursor.getIntValue(Sms.READ),
            status = cursor.getIntValue(Sms.STATUS),
            type = cursor.getIntValue(Sms.TYPE),
            serviceCenter = cursor.getStringValueOrNull(Sms.SERVICE_CENTER)
        )
    }

    private fun getMmsMessages(
        threadIds: List<Long>,
        includeTextOnlyAttachment: Boolean = false
    ): List<MmsBackup> {
        return threadIds.flatMap { threadId ->
            getMmsBackupsForThread(threadId, includeTextOnlyAttachment).map { it.second }
        }
    }

    /**
     * The Locker's lock path: one thread's MMS, each paired with its
     * provider id, so the encrypted row can be keyed to the message it
     * replaces (MmsBackup itself carries no id).
     */
    fun getMmsBackupsForThread(
        threadId: Long,
        includeTextOnlyAttachment: Boolean = false
    ): List<Pair<Long, MmsBackup>> {
        val projection = arrayOf(
            Mms._ID,
            Mms.CREATOR,
            Mms.CONTENT_TYPE,
            Mms.DELIVERY_REPORT,
            Mms.DATE,
            Mms.DATE_SENT,
            Mms.LOCKED,
            Mms.MESSAGE_TYPE,
            Mms.MESSAGE_BOX,
            Mms.READ,
            Mms.READ_REPORT,
            Mms.SEEN,
            Mms.TEXT_ONLY,
            Mms.STATUS,
            Mms.SUBJECT,
            Mms.SUBJECT_CHARSET,
            Mms.SUBSCRIPTION_ID,
            Mms.TRANSACTION_ID
        )
        val selection = if (includeTextOnlyAttachment) {
            "${Mms.THREAD_ID} = ? AND ${Mms.TEXT_ONLY} = ?"
        } else {
            "${Mms.THREAD_ID} = ?"
        }
        val selectionArgs = if (includeTextOnlyAttachment) {
            arrayOf(threadId.toString(), "1")
        } else {
            arrayOf(threadId.toString())
        }
        val mmsList = mutableListOf<Pair<Long, MmsBackup>>()
        context.queryCursor(Mms.CONTENT_URI, projection, selection, selectionArgs) { cursor ->
            val mmsId = cursor.getLongValue(Mms._ID)
            mmsList.add(mmsId to mmsBackupFromCursor(cursor, mmsId))
        }
        return mmsList
    }

    /** The Locker's single-message lock path: one MMS by provider id. */
    fun getMmsBackupById(id: Long): Pair<Long, MmsBackup>? {
        val projection = arrayOf(
            Mms._ID,
            Mms.CREATOR,
            Mms.CONTENT_TYPE,
            Mms.DELIVERY_REPORT,
            Mms.DATE,
            Mms.DATE_SENT,
            Mms.LOCKED,
            Mms.MESSAGE_TYPE,
            Mms.MESSAGE_BOX,
            Mms.READ,
            Mms.READ_REPORT,
            Mms.SEEN,
            Mms.TEXT_ONLY,
            Mms.STATUS,
            Mms.SUBJECT,
            Mms.SUBJECT_CHARSET,
            Mms.SUBSCRIPTION_ID,
            Mms.TRANSACTION_ID
        )

        var result: Pair<Long, MmsBackup>? = null
        context.queryCursor(
            Mms.CONTENT_URI,
            projection,
            "${Mms._ID} = ?",
            arrayOf(id.toString())
        ) { cursor ->
            val mmsId = cursor.getLongValue(Mms._ID)
            result = mmsId to mmsBackupFromCursor(cursor, mmsId)
        }
        return result
    }

    private fun mmsBackupFromCursor(cursor: android.database.Cursor, mmsId: Long): MmsBackup {
        return MmsBackup(
            creator = cursor.getStringValueOrNull(Mms.CREATOR),
            contentType = cursor.getStringValueOrNull(Mms.CONTENT_TYPE),
            deliveryReport = cursor.getIntValue(Mms.DELIVERY_REPORT),
            date = cursor.getLongValue(Mms.DATE),
            dateSent = cursor.getLongValue(Mms.DATE_SENT),
            locked = cursor.getIntValue(Mms.LOCKED),
            messageType = cursor.getIntValue(Mms.MESSAGE_TYPE),
            messageBox = cursor.getIntValue(Mms.MESSAGE_BOX),
            read = cursor.getIntValue(Mms.READ),
            readReport = cursor.getIntValue(Mms.READ_REPORT),
            seen = cursor.getIntValue(Mms.SEEN),
            textOnly = cursor.getIntValue(Mms.TEXT_ONLY),
            status = cursor.getStringValueOrNull(Mms.STATUS),
            subject = cursor.getStringValueOrNull(Mms.SUBJECT),
            subjectCharSet = cursor.getStringValueOrNull(Mms.SUBJECT_CHARSET),
            subscriptionId = cursor.getLongValue(Mms.SUBSCRIPTION_ID),
            transactionId = cursor.getStringValueOrNull(Mms.TRANSACTION_ID),
            addresses = getMmsAddresses(mmsId),
            parts = getParts(mmsId)
        )
    }

    @SuppressLint("NewApi")
    private fun getParts(mmsId: Long): List<MmsPart> {
        val parts = mutableListOf<MmsPart>()
        val uri = if (isQPlus()) Mms.Part.CONTENT_URI else Uri.parse("content://mms/part")
        val projection = arrayOf(
            Mms.Part._ID,
            Mms.Part.CONTENT_DISPOSITION,
            Mms.Part.CHARSET,
            Mms.Part.CONTENT_ID,
            Mms.Part.CONTENT_LOCATION,
            Mms.Part.CONTENT_TYPE,
            Mms.Part.CT_START,
            Mms.Part.CT_TYPE,
            Mms.Part.FILENAME,
            Mms.Part.NAME,
            Mms.Part.SEQ,
            Mms.Part.TEXT
        )

        val selection = "${Mms.Part.MSG_ID} = ?"
        val selectionArgs = arrayOf(mmsId.toString())
        context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
            val partId = cursor.getLongValue(Mms.Part._ID)
            val contentDisposition = cursor.getStringValueOrNull(Mms.Part.CONTENT_DISPOSITION)
            val charset = cursor.getStringValueOrNull(Mms.Part.CHARSET)
            val contentId = cursor.getStringValueOrNull(Mms.Part.CONTENT_ID)
            val contentLocation = cursor.getStringValueOrNull(Mms.Part.CONTENT_LOCATION)
            val contentType = cursor.getStringValue(Mms.Part.CONTENT_TYPE)
            val ctStart = cursor.getStringValueOrNull(Mms.Part.CT_START)
            val ctType = cursor.getStringValueOrNull(Mms.Part.CT_TYPE)
            val filename = cursor.getStringValueOrNull(Mms.Part.FILENAME)
            val name = cursor.getStringValueOrNull(Mms.Part.NAME)
            val sequenceOrder = cursor.getIntValue(Mms.Part.SEQ)
            val text = cursor.getStringValueOrNull(Mms.Part.TEXT)
            val data = when {
                contentType.startsWith("text/") -> {
                    usePart(partId) { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                }

                else -> {
                    usePart(partId) { stream ->
                        Base64.encodeToString(stream.readBytes(), Base64.DEFAULT)
                    }
                }
            }
            parts.add(
                MmsPart(
                    contentDisposition = contentDisposition,
                    charset = charset,
                    contentId = contentId,
                    contentLocation = contentLocation,
                    contentType = contentType,
                    ctStart = ctStart,
                    ctType = ctType,
                    filename = filename,
                    name = name,
                    sequenceOrder = sequenceOrder,
                    text = text,
                    data = data
                )
            )
        }
        return parts
    }

    @SuppressLint("NewApi")
    private fun usePart(partId: Long, block: (InputStream) -> String): String {
        val partUri = if (isQPlus()) {
            Mms.Part.CONTENT_URI.buildUpon().appendPath(partId.toString()).build()
        } else {
            Uri.parse("content://mms/part/$partId")
        }

        try {
            val stream = context.contentResolver.openInputStream(partUri) ?: return ""
            stream.use {
                return block(stream)
            }
        } catch (e: IOException) {
            return ""
        }
    }

    @SuppressLint("NewApi")
    private fun getMmsAddresses(messageId: Long): List<MmsAddress> {
        val addresses = mutableListOf<MmsAddress>()
        val uri = if (isRPlus()) {
            Mms.Addr.getAddrUriForMessage(messageId.toString())
        } else {
            Uri.parse("content://mms/$messageId/addr")
        }

        val projection = arrayOf(Mms.Addr.ADDRESS, Mms.Addr.TYPE, Mms.Addr.CHARSET)
        val selection = "${Mms.Addr.MSG_ID}= ?"
        val selectionArgs = arrayOf(messageId.toString())
        context.queryCursor(uri, projection, selection, selectionArgs) { cursor ->
            val address = cursor.getStringValue(Mms.Addr.ADDRESS)
            val type = cursor.getIntValue(Mms.Addr.TYPE)
            val charset = cursor.getIntValue(Mms.Addr.CHARSET)
            addresses.add(MmsAddress(address, type, charset))
        }
        return addresses
    }
}
