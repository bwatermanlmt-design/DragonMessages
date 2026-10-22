package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.R
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

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        ensureBackgroundThread {
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@ensureBackgroundThread

                // this is how it has always worked, but need to revisit this.
                val address = parts.last().originatingAddress.orEmpty()
                if (address.isBlank()) return@ensureBackgroundThread
                val subject = parts.last().pseudoSubject.orEmpty()
                val status = parts.last().status
                val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }

                // Explicit blocks in the system BlockedNumberProvider are law:
                // they still drop the message outright, before any rule runs.
                if (appContext.isNumberBlocked(address)) return@ensureBackgroundThread

                val threadId = appContext.getThreadId(address)
                val verdict = FilterEvaluator.evaluateIncoming(
                    context = appContext,
                    sender = address,
                    body = body,
                    channel = FilterAuditEntry.CHANNEL_SMS,
                    threadId = threadId
                )

                val date = System.currentTimeMillis()
                val subscriptionId = intent.getIntExtra("subscription", -1)

                handleMessageSync(
                    context = appContext,
                    address = address,
                    subject = subject,
                    body = body,
                    date = date,
                    threadId = threadId,
                    subscriptionId = subscriptionId,
                    status = status,
                    mailbox = verdict.mailbox,
                    storeAsRead = verdict.mailbox == Mailbox.SPAM,
                    suppressNotification = verdict.notificationMuted
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleMessageSync(
        context: Context,
        address: String,
        subject: String,
        body: String,
        date: Long,
        read: Int = 0,
        threadId: Long,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        subscriptionId: Int,
        status: Int,
        mailbox: String,
        storeAsRead: Boolean,
        suppressNotification: Boolean
    ) {
        val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
        val bitmap = context.getNotificationBitmap(photoUri)

        // Only filter catches (Spam) are stored already read, so they cannot
        // light up unread badges anywhere either. Everything else — muted
        // deliveries included — arrives unread like a normal message.
        val newMessageId = context.insertNewSMS(
            address = address,
            subject = subject,
            body = body,
            date = date,
            read = if (storeAsRead) 1 else read,
            threadId = threadId,
            type = type,
            subscriptionId = subscriptionId
        )

        context.getConversations(threadId).firstOrNull()?.let { conv ->
            runCatching { context.insertOrUpdateConversation(conv) }
        }
        runCatching { context.conversationsDB.updateMailbox(threadId, mailbox) }

        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        val participant = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address)),
            birthdays = ArrayList(),
            anniversaries = ArrayList()
        )

        val message = Message(
            id = newMessageId,
            body = body,
            type = type,
            status = status,
            participants = arrayListOf(participant),
            date = (date / 1000).toInt(),
            read = storeAsRead,
            threadId = threadId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = address,
            senderName = senderName,
            senderPhotoUri = photoUri,
            subscriptionId = subscriptionId
        )

        context.messagesDB.insertOrUpdate(message)

        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(threadId, false)
        }

        refreshMessages()
        refreshConversations()
        when {
            // The Locker knocks instead of showing: a warning with the
            // sender and the kind of arrival, never the content.
            mailbox == Mailbox.LOCKER -> if (!suppressNotification) {
                context.showLockerWarningNotification(
                    threadId = threadId,
                    senderName = senderName,
                    kindRes = R.string.locker_kind_text
                )
            }

            !storeAsRead && !suppressNotification -> {
                context.showReceivedMessageNotification(
                    messageId = newMessageId,
                    isMms = false,
                    address = address,
                    senderName = senderName,
                    body = body,
                    threadId = threadId,
                    bitmap = bitmap
                )
            }
        }
    }
}
