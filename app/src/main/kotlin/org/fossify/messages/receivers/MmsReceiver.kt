package org.fossify.messages.receivers

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showLockerWarningNotification
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.filtering.OcrGate
import org.fossify.messages.helpers.FilterEvaluator
import org.fossify.messages.helpers.MmsOcr
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
        // Revision 9 OCR runs first, so keyword rules can catch
        // image-only texts — but only for strangers with images and
        // little text, never for contacts, and never past the budget.
        val ocrText = extractOcrText(context, mms, address)
        val verdict = FilterEvaluator.evaluateIncoming(
            context = context,
            sender = address,
            body = mms.body,
            channel = FilterAuditEntry.CHANNEL_MMS,
            threadId = mms.threadId,
            ocrText = ocrText
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
        // Mark Spam as read (revision 10, when the toggle is on): unlike
        // SMS arrivals, an MMS is already stored by the library, so a
        // Spam filing on its own would leave it unread.
        if (verdict.mailbox == Mailbox.SPAM && context.config.markSpamAsRead) {
            runCatching { context.markThreadMessagesRead(mms.threadId) }
        }
        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(mms.threadId, false)
        }
        refreshMessages()
        refreshConversations()
    }

    /**
     * Recognized text from this MMS's images, for filter matching only.
     * Empty unless every gate holds (stranger, image attached, empty or
     * short body); any failure, timeout, or budget exhaustion answers
     * with what was recognized so far — possibly nothing — and filing
     * proceeds exactly as if there were no image. The text is never
     * stored, shown in the notification, or logged: the notification
     * below still uses the real body.
     */
    private fun extractOcrText(context: Context, mms: Message, address: String): String {
        val attachments = mms.attachment?.attachments ?: return ""
        val hasImage = attachments.any { it.mimetype.startsWith("image/") }
        if (!OcrGate.shouldRunOcr(FilterEvaluator.isInContacts(context, address), mms.body, hasImage)) {
            return ""
        }
        val deadline = SystemClock.elapsedRealtime() + OcrGate.TOTAL_BUDGET_MS
        val texts = ArrayList<String>()
        attachments.filter { it.mimetype.startsWith("image/") }
            .take(OcrGate.MAX_IMAGES)
            .forEach { attachment ->
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) return@forEach
                // Attachment.getUri() returns a Uri (parsed from its
                // stored string); a malformed string still yields an
                // empty Uri, which decodePart then fails open on.
                val partUri = try {
                    attachment.getUri()
                } catch (e: Exception) {
                    null
                } ?: return@forEach
                val bitmap = try {
                    MmsOcr.decodePart(context, partUri)
                } catch (e: Exception) {
                    null
                } ?: return@forEach
                MmsOcr.recognize(bitmap, remaining)?.let { texts += it }
            }
        return texts.joinToString("\n")
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
