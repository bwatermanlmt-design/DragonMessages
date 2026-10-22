package org.fossify.messages.helpers

import android.content.Context
import android.provider.Telephony
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.filterAuditDB
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.extensions.senderTagsDB
import org.fossify.messages.filtering.MessageFacts
import org.fossify.messages.filtering.RetroConversationFacts
import org.fossify.messages.filtering.RetroFiler
import org.fossify.messages.filtering.RuleEngine
import org.fossify.messages.filtering.RuleVerdict
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
     * The receive-path entry point: the raw rule verdict, tempered by
     * thread-state precedence (see [ThreadPrecedence]). An engaged or
     * hand-filed conversation keeps its mailbox instead of being re-filed
     * by whatever the newest message happens to contain; the audit row
     * records the effective placement, with the matched rule's name marked
     * when precedence overrode it. Blocked senders never reach this point —
     * the system BlockedNumberProvider drops SMS in the receiver and the
     * MMS library deletes blocked MMS — so [ThreadPrecedence]'s blocked
     * case is not in play here.
     */
    fun evaluateIncoming(
        context: Context,
        sender: String,
        body: String,
        channel: String,
        threadId: Long
    ): RuleVerdict {
        val raw = evaluateRules(context, sender, body)

        val conversation = runCatching {
            context.conversationsDB.getConversationWithThreadId(threadId)
        }.getOrNull()

        val effectiveMailbox = ThreadPrecedence.resolve(
            currentMailbox = conversation?.mailbox,
            verdictMailbox = raw.mailbox,
            engaged = hasOutgoingMessage(context, threadId),
            manuallyFiled = conversation?.manuallyFiled == true,
            senderBlocked = false
        )

        val held = effectiveMailbox != raw.mailbox
        val auditRuleName = if (held) {
            raw.ruleName + FilterAuditEntry.PRECEDENCE_KEPT_SUFFIX
        } else {
            raw.ruleName
        }
        recordAudit(context, sender, auditRuleName, effectiveMailbox, channel)

        return if (held) raw.copy(mailbox = effectiveMailbox) else raw
    }

    /** The raw engine verdict for one message, with its tag assignment applied. */
    private fun evaluateRules(context: Context, sender: String, body: String): RuleVerdict {
        FilterRuleSeeder.seedIfNeeded(context)

        val facts = MessageFacts(
            sender = sender,
            body = body,
            senderInContacts = isSenderInContacts(context, sender),
            senderTags = context.senderTagsDB.getForNumber(sender)?.tagList?.toSet() ?: emptySet()
        )

        val verdict = RuleEngine.evaluate(context.filterRulesDB.getAllEnabled(), facts)

        verdict.assignTag?.let { tag ->
            runCatching { assignTag(context, sender, tag) }
        }

        return verdict
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
     * same rules over a stored conversation, using its sender facts and the
     * body of its most recent inbound message. Applies the decision (mailbox
     * move, tag assignment) and records it in the audit log on the retro
     * channel. Protected conversations (groups, hand-filed, the Locker)
     * come back [RetroOutcome.Skipped] with the skip itself audited.
     */
    fun evaluateRetro(context: Context, conversation: Conversation): RetroOutcome {
        FilterRuleSeeder.seedIfNeeded(context)

        RetroFiler.skipReason(
            isGroupConversation = conversation.isGroupConversation,
            manuallyFiled = conversation.manuallyFiled,
            currentMailbox = conversation.mailbox
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

        val retroFacts = RetroConversationFacts(
            isGroupConversation = conversation.isGroupConversation,
            manuallyFiled = conversation.manuallyFiled,
            currentMailbox = conversation.mailbox,
            facts = MessageFacts(
                sender = conversation.phoneNumber,
                body = latestInboundBody(context, conversation.threadId),
                senderInContacts = isSenderInContacts(context, conversation.phoneNumber),
                senderTags = context.senderTagsDB.getForNumber(conversation.phoneNumber)?.tagList?.toSet()
                    ?: emptySet()
            )
        )

        return when (val decision = RetroFiler.decide(retroFacts, context.filterRulesDB.getAllEnabled())) {
            is RetroFiler.Decision.File -> {
                val verdict = decision.verdict
                recordAudit(
                    context, conversation.phoneNumber, verdict.ruleName, verdict.mailbox,
                    FilterAuditEntry.CHANNEL_RETRO
                )
                verdict.assignTag?.let { tag ->
                    runCatching { assignTag(context, conversation.phoneNumber, tag) }
                }
                context.conversationsDB.updateMailbox(conversation.threadId, verdict.mailbox)
                RetroOutcome.Filed(verdict)
            }

            is RetroFiler.Decision.Skip -> {
                // Only reachable for a rule that tries to deliver into the Locker.
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
        RetroFiler.SkipReason.GROUP -> FilterAuditEntry.RETRO_SKIP_GROUP
        RetroFiler.SkipReason.MANUALLY_FILED -> FilterAuditEntry.RETRO_SKIP_MANUALLY_FILED
        RetroFiler.SkipReason.LOCKER -> FilterAuditEntry.RETRO_SKIP_LOCKER
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

    /** Body of the thread's most recent inbound SMS, or "" if there is none. */
    private fun latestInboundBody(context: Context, threadId: Long): String {
        val projection = arrayOf(Telephony.Sms.BODY)
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
                if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun isSenderInContacts(context: Context, sender: String): Boolean {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        val result = SimpleContactsHelper(context).existsSync(sender, privateCursor)
        return result != ContactLookupResult.NotFound
    }

    private fun assignTag(context: Context, sender: String, tag: String) {
        val existing = context.senderTagsDB.getForNumber(sender)
        val merged = (existing?.tagList ?: emptyList()) + tag
        context.senderTagsDB.insertOrUpdate(SenderTags.of(sender, merged))
    }
}
