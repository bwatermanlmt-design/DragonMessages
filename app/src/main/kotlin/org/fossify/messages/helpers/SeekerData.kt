package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.filtering.SeekerMessage
import org.fossify.messages.filtering.isImageContentType
import org.fossify.messages.helpers.FilterEvaluator
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * The seeker's data: seed is every received message in Spam threads,
 * ham is every received message in all other mailboxes except Locker
 * (Locker content is never mined — its threads are hidden by design).
 *
 * Call [load] on a background thread: it walks every thread, so it
 * touches the message database heavily. Sender facts are cached per
 * sender, since the contacts lookup is a cursor query each time.
 */
data class SeekerDataSet(
    val seed: List<SeekerMessage>,
    val ham: List<SeekerMessage>,
    val rules: List<FilterRule>,
)

object SeekerData {

    fun load(context: Context): SeekerDataSet {
        val conversations = try {
            context.conversationsDB.getAllNonArchivedConversations()
        } catch (e: Exception) {
            emptyList()
        }
        val contactsCache = HashMap<String, Boolean>()
        val repliedCache = HashMap<Long, Boolean>()
        val seed = ArrayList<SeekerMessage>()
        val ham = ArrayList<SeekerMessage>()

        for (conversation in conversations) {
            val messages = try {
                context.messagesDB.getNonRecycledThreadMessages(conversation.threadId)
            } catch (e: Exception) {
                emptyList()
            }
            val repliedTo = repliedCache.getOrPut(conversation.threadId) {
                FilterEvaluator.hasOutgoingMessage(context, conversation.threadId)
            }
            for (message in messages) {
                if (!message.isReceivedMessage()) continue
                val sender = message.senderPhoneNumber
                val inContacts = contactsCache.getOrPut(sender) {
                    FilterEvaluator.isInContacts(context, sender)
                }
                val seekerMessage = SeekerMessage(
                    sender = sender,
                    body = message.body,
                    senderInContacts = inContacts,
                    repliedTo = repliedTo,
                    // The stored attachments say whether this message
                    // carries an image; SMS never does.
                    hasImage = if (message.isMMS) {
                        message.attachment?.attachments?.any { isImageContentType(it.mimetype) } ?: false
                    } else {
                        false
                    },
                )
                when (conversation.mailbox) {
                    Mailbox.SPAM -> seed += seekerMessage
                    Mailbox.LOCKER -> Unit // never mined
                    else -> ham += seekerMessage
                }
            }
        }

        val rules = try {
            context.filterRulesDB.getAll()
        } catch (e: Exception) {
            emptyList()
        }
        return SeekerDataSet(seed, ham, rules)
    }

    /** The latest inbound message of a thread, as a seeker message, or null when the thread has none. */
    fun latestInbound(context: Context, threadId: Long): SeekerMessage? {
        val messages = try {
            context.messagesDB.getNonRecycledThreadMessages(threadId)
        } catch (e: Exception) {
            return null
        }
        val latest = messages.filter { it.isReceivedMessage() }.maxByOrNull { it.date } ?: return null
        return SeekerMessage(
            sender = latest.senderPhoneNumber,
            body = latest.body,
            senderInContacts = FilterEvaluator.isInContacts(context, latest.senderPhoneNumber),
            repliedTo = FilterEvaluator.hasOutgoingMessage(context, threadId),
            hasImage = if (latest.isMMS) {
                latest.attachment?.attachments?.any { isImageContentType(it.mimetype) } ?: false
            } else {
                false
            },
        )
    }
}
