package org.fossify.messages.receivers

import android.content.Context
import android.net.Uri
import com.bumptech.glide.Glide
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMMS
import org.fossify.messages.extensions.getLatestMMS
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showLockerWarningNotification
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.FilterEvaluator
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.FilterAuditEntry
import org.fossify.messages.models.Mailbox
import org.fossify.messages.models.Message

class MmsReceiver : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String): Boolean {
        // Never true: a true verdict makes the library delete the MMS it
        // just downloaded. Blocked senders are filed to Spam by the rule
        // engine in onMessageReceived instead (precedence step 0), so
        // nothing is ever silently destroyed.
        return false
    }

    override fun isContentBlocked(context: Context, content: String): Boolean {
        // Never true: a true verdict makes the library delete the MMS.
        // Content rules are applied by the rule engine after delivery.
        return false
    }

    override fun onMessageReceived(context: Context, messageUri: Uri?) {
        val mms = if (messageUri != null) {
            context.getMMS(messageUri)
        } else {
            context.getLatestMMS()
        } ?: return

        val address = mms.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: mms.senderPhoneNumber
        val size = context.resources.getDimension(R.dimen.notification_large_icon_size).toInt()
        ensureBackgroundThread {
            handleMmsMessage(context, mms, size, address)
        }
    }

    override fun onError(context: Context, error: String) {
        context.showErrorToast(context.getString(R.string.couldnt_download_mms))
    }

    private fun handleMmsMessage(
        context: Context,
        mms: Message,
        size: Int,
        address: String
    ) {
        val glideBitmap = try {
            Glide.with(context)
                .asBitmap()
                .load(mms.attachment!!.attachments.first().getUri())
                .centerCrop()
                .into(size, size)
                .get()
        } catch (e: Exception) {
            null
        }


        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        // The MMS is already stored by the library at this point; the rule
        // engine decides where it files and whether it may notify.
        val verdict = FilterEvaluator.evaluateIncoming(
            context = context,
            sender = address,
            body = mms.body,
            channel = FilterAuditEntry.CHANNEL_MMS,
            threadId = mms.threadId
        )

        when {
            // The Locker knocks instead of showing: a warning naming the
            // kind of arrival, never the content or a thumbnail.
            verdict.mailbox == Mailbox.LOCKER && !verdict.notificationMuted -> {
                context.showLockerWarningNotification(
                    threadId = mms.threadId,
                    senderName = senderName,
                    kindRes = lockerWarningKind(mms)
                )
            }

            verdict.shouldNotify -> {
                context.showReceivedMessageNotification(
                    messageId = mms.id,
                    isMms = true,
                    address = address,
                    senderName = senderName,
                    body = mms.body,
                    threadId = mms.threadId,
                    bitmap = glideBitmap
                )
            }
        }

        val conversation = context.getConversations(mms.threadId).firstOrNull() ?: return
        runCatching { context.insertOrUpdateConversation(conversation) }
        runCatching { context.conversationsDB.updateMailbox(mms.threadId, verdict.mailbox) }
        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(mms.threadId, false)
        }
        refreshMessages()
        refreshConversations()
    }

    /** What kind of thing arrived, for the Locker's warning: photo beats video beats text. */
    private fun lockerWarningKind(mms: Message): Int {
        val attachments = mms.attachment?.attachments ?: return R.string.locker_kind_text
        return when {
            attachments.any { it.mimetype.startsWith("image/") } -> R.string.locker_kind_photo
            attachments.any { it.mimetype.startsWith("video/") } -> R.string.locker_kind_video
            else -> R.string.locker_kind_text
        }
    }
}
