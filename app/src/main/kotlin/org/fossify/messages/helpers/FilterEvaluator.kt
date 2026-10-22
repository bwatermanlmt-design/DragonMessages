package org.fossify.messages.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.filterAuditDB
import org.fossify.messages.extensions.getMMSSender
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.extensions.senderTagsDB
import org.fossify.messages.filtering.MessageFacts
import org.fossify.messages.filtering.RetroConversationFacts
import org.fossify.messages.filtering.RetroFiler
import org.fossify.messages.filtering.RuleEngine
import org.fossify.messages.filtering.RuleVerdict
import org.fossify.messages.filtering.StarterRules
import org.fossify.messages.filtering.ThreadPrecedence
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.FilterAuditEntry
import org.fossify.messages.models.SenderTags

/**
 * Android-side wrapper around the pure [RuleEngine]: gathers the message
 * facts (contacts lookup, stored sender tags), evaluates the enabled rules,
 * records the verdict in the audit log and applies any tag assignment.
 * Callers must already be on a background thread.
 */
object FilterEvaluator {

    fun evaluate(context: Context, sender: String, body: String, channel: String): RuleVerdict {
        val verdict = evaluateRules(context, sender, body)
        recordAudit(context, sender, verdict.ruleName, verdict.mailbox, channel)
        return verdict
    }

    /**
     * The receive-path entry point: the raw rule verdict, resolved through
     * thread-state precedence (see [ThreadPrecedence]) into the effective
     * placement — contacts settle to their address-book home, an engaged
     * thread's Spam verdict falls through, hand-filed and Locker threads
     * stay put. The audit row records the effective placement, with the
     * rule name marked when precedence changed it. Hand-set mutes
     * (conversation, number, folder) are folded in as notification
     * suppression only; they never change the placement. Blocked senders
     * never reach this point — the system BlockedNumberProvider drops SMS
     * in the receiver and the MMS library deletes blocked MMS — so
     * [ThreadPrecedence]'s blocked case is not in play here.
     */
    fun evaluateIncoming(
        context: Context,
        sender: String,
        body: String,
        channel: String,
        threadId: Long
    ): RuleVerdict {
        FilterRuleSeeder.seedIfNeeded(context)

        val rules = context.filterRulesDB.getAllEnabled()
        val facts = buildFacts(context, sender, body)
        val raw = RuleEngine.evaluate(rules, facts)

        val conversation = runCatching {
            context.conversationsDB.getConversationWithThreadId(threadId)
        }.getOrNull()

        val effective = ThreadPrecedence.resolve(
            currentMailbox = conversation?.mailbox,
            verdict = raw,
            facts = facts,
            rules = rules,
            engaged = hasOutgoingMessage(context, threadId),
            manuallyFiled = conversation?.manuallyFiled == true,
            senderBlocked = false
        )

        effective.assignTag?.let { tag ->
            runCatching { assignTag(context, sender, tag) }
        }

        val muted = conversation?.muted == true ||
            StarterRules.TAG_MUTED in facts.senderTags ||
            effective.mailbox in context.config.mutedMailboxes
        val verdict = effective.copy(notificationMuted = muted)

        val auditRuleName = if (effective.mailbox != raw.mailbox) {
            effective.ruleName + FilterAuditEntry.PRECEDENCE_KEPT_SUFFIX
        } else {
            effective.ruleName
        }
        recordAudit(context, sender, auditRuleName, effective.mailbox, channel)

        return verdict
    }

    /** The raw engine verdict for one message, with its tag assignment applied. */
    private fun evaluateRules(context: Context, sender: String, body: String): RuleVerdict {
        FilterRuleSeeder.seedIfNeeded(context)

        val facts = buildFacts(context, sender, body)
        val verdict = RuleEngine.evaluate(context.filterRulesDB.getAllEnabled(), facts)

        verdict.assignTag?.let { tag ->
            runCatching { assignTag(context, sender, tag) }
        }

        return verdict
    }

    /** Everything the engine and precedence need to know about one message. */
    private fun buildFacts(context: Context, sender: String, body: String): MessageFacts {
        val inContacts = isSenderInContacts(context, sender)
        return MessageFacts(
            sender = sender,
            body = body,
            senderInContacts = inContacts,
            senderTags = context.senderTagsDB.getForNumber(sender)?.tagList?.toSet() ?: emptySet(),
            businessLabeled = inContacts && isBusinessLabeledContact(context, sender)
        )
    }

    /**
     * Whether the thread contains a message Ben sent. The message store is
     * the source of truth (it also counts replies sent before this app
     * became the default, or from another app), so no flag has to be
     * maintained on send.
     */
    fun hasOutgoingMessage(context: Context, threadId: Long): Boolean {
        val smsSent = providerHasRow(
            context,
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?",
            arrayOf(threadId.toString(), Telephony.Sms.MESSAGE_TYPE_SENT.toString())
        )
        if (smsSent) return true

        return providerHasRow(
            context,
            Telephony.Mms.CONTENT_URI,
            "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ?",
            arrayOf(threadId.toString(), Telephony.Mms.MESSAGE_BOX_SENT.toString())
        )
    }

    private fun providerHasRow(
        context: Context,
        uri: android.net.Uri,
        selection: String,
        selectionArgs: Array<String>
    ): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(Telephony.Sms._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** The outcome of retroactively evaluating one stored conversation. */
    sealed interface RetroOutcome {
        data class Filed(val verdict: RuleVerdict) : RetroOutcome
        data class Skipped(val reason: RetroFiler.SkipReason) : RetroOutcome
        data object Unchanged : RetroOutcome
    }

    /**
     * The retro mode used by the "File existing conversations" pass: the
     * same rules over a stored conversation, judged on its most recent
     * inbound message. 1:1 threads are judged on the conversation's own
     * number, as before; group threads are judged on the sender and body
     * of that most recent inbound message, by exactly the same rules.
     * Applies the decision (mailbox move, tag assignment) and records it
     * in the audit log on the retro channel, with group filings marked as
     * such. Protected conversations (hand-filed, the Locker, group
     * threads with no inbound message) come back [RetroOutcome.Skipped]
     * with the skip itself audited. Engaged threads are judged like
     * everything else, with their spam-immunity applied inside the
     * decision (see [RetroFiler]).
     */
    fun evaluateRetro(context: Context, conversation: Conversation): RetroOutcome {
        FilterRuleSeeder.seedIfNeeded(context)

        // The two protections decidable from the stored row alone, checked
        // before any provider work. RetroFiler.decide() re-checks them (and
        // everything else) as the single authority.
        RetroFiler.skipReason(
            isGroupConversation = conversation.isGroupConversation,
            manuallyFiled = conversation.manuallyFiled,
            currentMailbox = conversation.mailbox,
            hasInboundMessage = true
        )?.let { reason ->
            recordAudit(
                context = context,
                sender = conversation.phoneNumber,
                ruleName = skipRuleName(reason),
                mailbox = conversation.mailbox,
                channel = FilterAuditEntry.CHANNEL_RETRO
            )
            return RetroOutcome.Skipped(reason)
        }

        val latestSms = latestInboundSms(context, conversation.threadId)
        val latestMms = latestInboundMms(context, conversation.threadId)
        val latestInbound = listOfNotNull(latestSms, latestMms).maxByOrNull { it.dateMillis }

        val judgedSender = if (conversation.isGroupConversation) {
            latestInbound?.sender?.takeIf { it.isNotBlank() } ?: conversation.phoneNumber
        } else {
            conversation.phoneNumber
        }
        val judgedBody = if (conversation.isGroupConversation) {
            latestInbound?.body ?: ""
        } else {
            latestSms?.body ?: ""
        }

        val retroFacts = RetroConversationFacts(
            isGroupConversation = conversation.isGroupConversation,
            manuallyFiled = conversation.manuallyFiled,
            currentMailbox = conversation.mailbox,
            engaged = hasOutgoingMessage(context, conversation.threadId),
            hasInboundMessage = latestInbound != null,
            facts = buildFacts(context, judgedSender, judgedBody)
        )

        return when (val decision = RetroFiler.decide(retroFacts, context.filterRulesDB.getAllEnabled())) {
            is RetroFiler.Decision.File -> {
                val verdict = decision.verdict
                val auditRuleName = if (conversation.isGroupConversation) {
                    verdict.ruleName + FilterAuditEntry.RETRO_GROUP_SUFFIX
                } else {
                    verdict.ruleName
                }
                recordAudit(
                    context, conversation.phoneNumber, auditRuleName, verdict.mailbox,
                    FilterAuditEntry.CHANNEL_RETRO
                )
                verdict.assignTag?.let { tag ->
                    runCatching { assignTag(context, judgedSender, tag) }
                }
                context.conversationsDB.updateMailbox(conversation.threadId, verdict.mailbox)
                RetroOutcome.Filed(verdict)
            }

            is RetroFiler.Decision.Skip -> {
                recordAudit(
                    context, conversation.phoneNumber, skipRuleName(decision.reason),
                    conversation.mailbox, FilterAuditEntry.CHANNEL_RETRO
                )
                RetroOutcome.Skipped(decision.reason)
            }

            RetroFiler.Decision.AlreadyFiled -> RetroOutcome.Unchanged
        }
    }

    private fun skipRuleName(reason: RetroFiler.SkipReason): String = when (reason) {
        RetroFiler.SkipReason.MANUALLY_FILED -> FilterAuditEntry.RETRO_SKIP_MANUALLY_FILED
        RetroFiler.SkipReason.LOCKER -> FilterAuditEntry.RETRO_SKIP_LOCKER
        RetroFiler.SkipReason.NO_INBOUND -> FilterAuditEntry.RETRO_SKIP_NO_INBOUND
    }

    private fun recordAudit(context: Context, sender: String, ruleName: String, mailbox: String, channel: String) {
        runCatching {
            context.filterAuditDB.insert(
                FilterAuditEntry(
                    timestamp = System.currentTimeMillis(),
                    sender = sender,
                    ruleName = ruleName,
                    mailbox = mailbox,
                    channel = channel
                )
            )
        }
    }

    /** One inbound message of a thread, as the retro pass sees it. */
    private data class InboundMessage(val sender: String, val body: String, val dateMillis: Long)

    /** The thread's most recent inbound SMS, or null if it has none. */
    private fun latestInboundSms(context: Context, threadId: Long): InboundMessage? {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val selection = "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?"
        val selectionArgs = arrayOf(
            threadId.toString(),
            Telephony.Sms.MESSAGE_TYPE_INBOX.toString()
        )
        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    InboundMessage(
                        sender = cursor.getString(0) ?: "",
                        body = cursor.getString(1) ?: "",
                        dateMillis = cursor.getLong(2)
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The thread's most recent inbound MMS, or null if it has none. Only the
     * sender is read (via the address table); MMS text parts are not
     * extracted, so the body is empty — the same SMS-only body caveat the
     * retro pass has always had.
     */
    private fun latestInboundMms(context: Context, threadId: Long): InboundMessage? {
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE)
        val selection = "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ?"
        val selectionArgs = arrayOf(
            threadId.toString(),
            Telephony.Mms.MESSAGE_BOX_INBOX.toString()
        )
        return try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${Telephony.Mms.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    InboundMessage(
                        sender = context.getMMSSender(cursor.getLong(0)),
                        body = "",
                        // The MMS provider stores dates in seconds.
                        dateMillis = cursor.getLong(1) * 1000
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isSenderInContacts(context: Context, sender: String): Boolean {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        val result = SimpleContactsHelper(context).existsSync(sender, privateCursor)
        return result != ContactLookupResult.NotFound
    }

    /** Address-book group titles that mark a contact as a business. */
    private val BUSINESS_GROUP_TITLES = setOf("Businesses", "Services")

    /**
     * Whether the contact behind [sender] carries a business label: a
     * Contacts group titled "Businesses" or "Services" (case-insensitive).
     * The classification lives in the address book, not in the app. Group
     * data comes from the system contacts provider; contacts from other
     * sources expose none, and any failure answers false — when in doubt
     * the sender is treated as a person, never pushed away from Contacts.
     */
    private fun isBusinessLabeledContact(context: Context, sender: String): Boolean {
        return try {
            val contactId = lookupContactId(context, sender) ?: return false
            val groupIds = groupIdsForContact(context, contactId)
            if (groupIds.isEmpty()) {
                false
            } else {
                groupTitles(context, groupIds).any { title ->
                    BUSINESS_GROUP_TITLES.any { it.equals(title, ignoreCase = true) }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun lookupContactId(context: Context, sender: String): Long? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(sender)
        )
        return context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun groupIdsForContact(context: Context, contactId: Long): Set<Long> {
        val groupIds = HashSet<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
            ),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) {
                    groupIds += cursor.getLong(0)
                }
            }
        }
        return groupIds
    }

    private fun groupTitles(context: Context, groupIds: Set<Long>): Set<String> {
        val placeholders = groupIds.joinToString(", ") { "?" }
        val titles = HashSet<String>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups.TITLE, ContactsContract.Groups.DELETED),
            "${ContactsContract.Groups._ID} IN ($placeholders)",
            groupIds.map { it.toString() }.toTypedArray(),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(0)
                val deleted = cursor.getInt(1)
                if (title != null && deleted == 0) {
                    titles += title
                }
            }
        }
        return titles
    }

    private fun assignTag(context: Context, sender: String, tag: String) {
        val existing = context.senderTagsDB.getForNumber(sender)
        val merged = (existing?.tagList ?: emptyList()) + tag
        context.senderTagsDB.insertOrUpdate(SenderTags.of(sender, merged))
    }
}
