package org.fossify.messages.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.deleteBlockedNumber
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.filterAuditDB
import org.fossify.messages.extensions.foldersDB
import org.fossify.messages.extensions.getMMSSender
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.extensions.senderTagsDB
import org.fossify.messages.filtering.BlockedNumbers
import org.fossify.messages.filtering.LabelHomeMapping
import org.fossify.messages.filtering.LabelHomes
import org.fossify.messages.filtering.MessageFacts
import org.fossify.messages.filtering.RetroConversationFacts
import org.fossify.messages.filtering.RetroFiler
import org.fossify.messages.filtering.RuleEngine
import org.fossify.messages.filtering.RuleVerdict
import org.fossify.messages.filtering.StarterRules
import org.fossify.messages.filtering.ThreadPrecedence
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.FilterAuditEntry
import org.fossify.messages.models.Mailbox
import org.fossify.messages.models.SenderFiling
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
     * thread's Spam verdict falls through, an answered thread already in
     * Not in Contacts is ignored by the filters entirely, hand-filed and
     * Locker threads stay put. The audit row records the effective
     * placement, with the rule name marked when precedence changed it —
     * or when the filters ignored the thread. Hand-set mutes
     * (conversation, number, folder) are folded in as notification
     * suppression only; they never change the placement. Blocked senders
     * are evaluated like everyone else and land in Spam through
     * [ThreadPrecedence] step 0: nothing is dropped or deleted on arrival,
     * so a blocked message is stored already-read and silent in Spam —
     * visible, auditable, and reversible by unblocking the number.
     */
    /**
     * Evaluate one arrival. [ocrText] is recognized image text (revision
     * 9 OCR): keyword rules match against it like the body, but it is
     * never stored, shown, or logged — it lives only for this evaluation.
     */
    fun evaluateIncoming(
        context: Context,
        sender: String,
        body: String,
        channel: String,
        threadId: Long,
        ocrText: String = ""
    ): RuleVerdict {
        FilterRuleSeeder.seedIfNeeded(context)

        val rules = context.filterRulesDB.getAllEnabledForEvaluation()
        val facts = buildFacts(context, sender, body, ocrText)
        val raw = RuleEngine.evaluate(rules, facts)

        val conversation = runCatching {
            context.conversationsDB.getConversationWithThreadId(threadId)
        }.getOrNull()

        val engaged = hasOutgoingMessage(context, threadId)
        val effective = ThreadPrecedence.resolve(
            currentMailbox = conversation?.mailbox,
            verdict = raw,
            facts = facts,
            rules = rules,
            engaged = engaged,
            manuallyFiled = conversation?.manuallyFiled == true,
            senderBlocked = isSenderBlocked(context, sender)
        )

        effective.assignTag?.let { tag ->
            runCatching { assignTag(context, sender, tag) }
        }

        val muted = conversation?.muted == true ||
            StarterRules.TAG_MUTED in facts.senderTags ||
            effective.mailbox in context.config.mutedMailboxes
        val verdict = effective.copy(notificationMuted = muted)

        // The filters ignore an answered thread sitting in Not in
        // Contacts: precedence kept it there without consulting the rules
        // for placement. The audit row says so distinctly, so the filter
        // log can explain why nothing moved. (Contacts are excluded —
        // the address book outranks the ignore — and a hand-filed thread
        // is kept by Ben's hand, not by this rule.)
        val answeredAndIgnored = engaged &&
            conversation?.mailbox == Mailbox.QUARANTINE &&
            conversation?.manuallyFiled != true &&
            !facts.senderInContacts
        val auditRuleName = when {
            answeredAndIgnored -> effective.ruleName + FilterAuditEntry.PRECEDENCE_ANSWERED_SUFFIX
            effective.mailbox != raw.mailbox -> effective.ruleName + FilterAuditEntry.PRECEDENCE_KEPT_SUFFIX
            else -> effective.ruleName
        }
        recordAudit(context, sender, auditRuleName, effective.mailbox, channel)

        return verdict
    }

    /** The raw engine verdict for one message, with its tag assignment applied. */
    private fun evaluateRules(context: Context, sender: String, body: String): RuleVerdict {
        FilterRuleSeeder.seedIfNeeded(context)

        val facts = buildFacts(context, sender, body)
        val verdict = RuleEngine.evaluate(context.filterRulesDB.getAllEnabledForEvaluation(), facts)

        verdict.assignTag?.let { tag ->
            runCatching { assignTag(context, sender, tag) }
        }

        return verdict
    }

    /** Everything the engine and precedence need to know about one message. */
    private fun buildFacts(context: Context, sender: String, body: String, ocrText: String = ""): MessageFacts {
        val inContacts = isSenderInContacts(context, sender)
        val labels = if (inContacts) contactLabels(context, sender) else emptyList()
        val businessLabel = businessLabelOf(labels)
        return MessageFacts(
            sender = sender,
            body = body,
            ocrText = ocrText,
            senderInContacts = inContacts,
            senderTags = context.senderTagsDB.getForNumber(sender)?.tagList?.toSet() ?: emptySet(),
            businessLabeled = businessLabel != null,
            senderName = if (inContacts) {
                runCatching { context.getNameFromAddress(sender, null) }.getOrDefault("")
            } else {
                ""
            },
            senderFiledMailbox = senderFiledMailbox(context, sender),
            labelHome = if (inContacts) {
                LabelHomes.homeFor(labels, businessLabel, labelMappings(context))
            } else {
                null
            }
        )
    }

    /** The label mappings as the pure precedence layer sees them, in order. */
    private fun labelMappings(context: Context): List<LabelHomeMapping> {
        return try {
            context.foldersDB.getAllLabelMappings().map { LabelHomeMapping(it.label, it.mailbox) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Whether [sender] is in the address book (public wrapper for the macro screens). */
    fun isInContacts(context: Context, sender: String): Boolean {
        return isSenderInContacts(context, sender)
    }

    /**
     * The address-book label that marks family (revision 9): the label
     * mapped to the folder named "Family", when such a folder and mapping
     * exist, else the literal "Family" label. Read failure answers
     * "Family": the fallback, never a crash.
     */
    fun familyLabel(context: Context): String {
        return try {
            val familyFolder = context.foldersDB.getAllFolders()
                .firstOrNull { it.name.equals("Family", ignoreCase = true) }
            if (familyFolder != null) {
                context.foldersDB.getAllLabelMappings()
                    .firstOrNull { it.mailbox == Mailbox.customMailboxId(familyFolder.id) }
                    ?.label
            } else {
                null
            } ?: "Family"
        } catch (e: Exception) {
            "Family"
        }
    }

    /**
     * Whether any participant behind [sender] carries the family label.
     * Group threads join participants with commas or semicolons, so the
     * sender string is split and any family participant counts. A
     * lookup failure answers false: unlabeled, never family by accident.
     */
    fun isFamilySender(context: Context, sender: String): Boolean {
        val label = familyLabel(context)
        return sender.split(',', ';').any { part ->
            val number = part.trim()
            number.isNotEmpty() &&
                contactLabels(context, number).any { it.equals(label, ignoreCase = true) }
        }
    }
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
     * threads with no inbound message, answered threads already in Not in
     * Contacts) come back [RetroOutcome.Skipped] with the skip itself
     * audited. Engaged threads anywhere else are judged like everything
     * else, with their spam-immunity applied inside the decision (see
     * [RetroFiler]).
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
            senderBlocked = isSenderBlocked(context, judgedSender),
            facts = buildFacts(context, judgedSender, judgedBody)
        )

        return when (val decision = RetroFiler.decide(retroFacts, context.filterRulesDB.getAllEnabledForEvaluation())) {
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
        RetroFiler.SkipReason.ENGAGED_IN_QUARANTINE -> FilterAuditEntry.RETRO_SKIP_ENGAGED_IN_QUARANTINE
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
     * Every address-book label (Contacts group title) the contact behind
     * [sender] carries. The classification lives in the address book, not
     * in the app. Group data comes from the system contacts provider;
     * contacts from other sources expose none, and any failure answers an
     * empty list — when in doubt the sender is treated as an unlabeled
     * person, never pushed away from Contacts.
     */
    private fun contactLabels(context: Context, sender: String): List<String> {
        return try {
            val contactId = lookupContactId(context, sender) ?: return emptyList()
            val groupIds = groupIdsForContact(context, contactId)
            if (groupIds.isEmpty()) {
                emptyList()
            } else {
                groupTitles(context, groupIds).toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The business label among [labels], if any: a Contacts group titled
     * "Businesses" or "Services" (case-insensitive) marks the contact as a
     * business, whose home is Auth & Services unless that very label is
     * mapped to a folder of its own (see [LabelHomes]).
     */
    private fun businessLabelOf(labels: List<String>): String? {
        return labels.firstOrNull { title ->
            BUSINESS_GROUP_TITLES.any { it.equals(title, ignoreCase = true) }
        }
    }

    /**
     * Every address-book group title present on the device, for the label
     * pickers (folder creation, mapping management). Read failure answers
     * an empty list: no labels to offer, never a crash.
     */
    fun contactGroupTitles(context: Context): List<String> {
        return try {
            val titles = LinkedHashSet<String>()
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups.TITLE, ContactsContract.Groups.DELETED),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(0)
                    val deleted = cursor.getInt(1)
                    if (title != null && title.isNotBlank() && deleted == 0) {
                        titles += title
                    }
                }
            }
            titles.sorted()
        } catch (e: Exception) {
            emptyList()
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

    private fun removeTag(context: Context, sender: String, tag: String) {
        val existing = context.senderTagsDB.getForNumber(sender) ?: return
        val remaining = existing.tagList - tag
        context.senderTagsDB.insertOrUpdate(SenderTags.of(sender, remaining))
    }

    // region sender-level filings (Ben's revision 7 refinement)

    /**
     * The mailbox Ben filed [sender] to by hand, from the sender-level
     * filing record, or null when the sender has none. The record belongs
     * to the number (matched format-tolerantly, like the blocked record),
     * so it survives thread deletion and needs no contact anywhere: a
     * stranger Ben filed into My Contacts is found here exactly like a
     * filed friend.
     */
    fun senderFiledMailbox(context: Context, sender: String): String? {
        return try {
            context.foldersDB.getAllSenderFilings()
                .firstOrNull { BlockedNumbers.numbersMatch(it.number, sender) }
                ?.mailbox
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The one manual placement, used by every Send to folder action and
     * by macro runs: the thread moves (marked hand-filed, as manual moves
     * have always been) and — for a 1:1 thread — the sender-level record
     * is written, so the sender belongs wherever Ben put it from now on.
     * Group threads have no single sender to record and stay thread-level
     * only; the Locker is never recorded (it is a place threads are held,
     * not a sender's home, and precedence never lets a filing outrank a
     * thread sitting in it anyway).
     */
    fun applyManualFiling(context: Context, conversation: Conversation, targetMailbox: String) {
        context.conversationsDB.updateMailboxManuallyFiled(conversation.threadId, targetMailbox)
        recordSenderFiling(
            context = context,
            sender = conversation.phoneNumber,
            isGroupConversation = conversation.isGroupConversation,
            targetMailbox = targetMailbox
        )
    }

    /** Write (or overwrite) the sender-level record for one manual placement. */
    fun recordSenderFiling(context: Context, sender: String, isGroupConversation: Boolean, targetMailbox: String) {
        if (isGroupConversation || targetMailbox == Mailbox.LOCKER || sender.isBlank()) {
            return
        }

        try {
            val dao = context.foldersDB
            dao.getAllSenderFilings()
                .filter { BlockedNumbers.numbersMatch(it.number, sender) }
                .forEach { dao.deleteSenderFiling(it.number) }
            dao.insertOrUpdateSenderFiling(SenderFiling(number = sender, mailbox = targetMailbox))
        } catch (e: Exception) {
            // The thread-level move already landed; the record is the
            // sender's memory of it, and a later move rewrites it.
        }
    }

    /**
     * Where a conversation belongs with every hand placement taken away:
     * the natural home the filing machinery computes for it — blocked
     * senders to Spam, contacts to their label/business home, everyone
     * else where the rules put them. Used when a folder is deleted and
     * its threads fall back. Group threads have no single sender to judge
     * and fall back to Not in Contacts.
     */
    fun naturalHome(context: Context, conversation: Conversation): String {
        if (conversation.isGroupConversation) {
            return Mailbox.QUARANTINE
        }

        val sender = conversation.phoneNumber
        val facts = buildFacts(context, sender, conversation.snippet)
            .copy(senderFiledMailbox = null)
        val rules = context.filterRulesDB.getAllEnabledForEvaluation()
        val resolved = ThreadPrecedence.resolve(
            currentMailbox = null,
            verdict = RuleEngine.evaluate(rules, facts),
            facts = facts,
            rules = rules,
            engaged = hasOutgoingMessage(context, conversation.threadId),
            manuallyFiled = false,
            senderBlocked = isSenderBlocked(context, sender)
        )
        // The Locker is filled by hand only; no fallback lands there.
        return if (resolved.mailbox == Mailbox.LOCKER) Mailbox.QUARANTINE else resolved.mailbox
    }

    /** The audit row a macro run leaves for one filed thread. */
    fun recordMacroFiling(context: Context, macroName: String, sender: String, mailbox: String) {
        recordAudit(context, sender, macroName, mailbox, FilterAuditEntry.CHANNEL_MACRO)
    }

    // endregion

    // region permanent, number-level blocking (Ben's requirement, 2026-10-22)

    /**
     * Whether [sender] is blocked: it is on the app's own blocked-numbers
     * record (the reserved `blocked` sender tag, matched format-tolerantly)
     * or in the system BlockedNumberProvider. The app record belongs to
     * the number and survives thread deletion, so a blocked number's next
     * thread — whenever it appears — files straight to Spam.
     */
    fun isSenderBlocked(context: Context, sender: String): Boolean {
        if (context.isNumberBlocked(sender)) {
            return true
        }

        val recorded = try {
            context.senderTagsDB.getAll()
                .filter { StarterRules.TAG_BLOCKED in it.tagList }
                .map { it.number }
        } catch (e: Exception) {
            emptyList()
        }
        return BlockedNumbers.isBlocked(sender, recorded)
    }

    /**
     * Every blocked number as one list: the app's record unioned with the
     * system block list, format variants of the same number collapsed.
     * This is what the blocked-numbers screen shows, so no block — from
     * whichever side it was placed — is ever invisible there.
     */
    fun blockedNumbers(context: Context): List<String> {
        val numbers = ArrayList<String>()
        fun add(number: String) {
            if (number.isNotBlank() && !BlockedNumbers.isBlocked(number, numbers)) {
                numbers += number
            }
        }

        try {
            context.senderTagsDB.getAll()
                .filter { StarterRules.TAG_BLOCKED in it.tagList }
                .forEach { add(it.number) }
        } catch (e: Exception) {
            // The system list below still answers.
        }
        try {
            context.getBlockedNumbers().forEach { add(it.number) }
        } catch (e: Exception) {
            // The app record above still answers.
        }
        return numbers.sorted()
    }

    /**
     * The one permanent block, used by every Block action in the app:
     * record the number in the app's blocked record and in the system
     * BlockedNumberProvider. Filing the current thread to Spam is the
     * caller's job (it owns the thread id).
     */
    fun blockNumber(context: Context, number: String) {
        runCatching { assignTag(context, number, StarterRules.TAG_BLOCKED) }
        runCatching { context.addBlockedNumber(number) }
    }

    /**
     * Lift a permanent block from both records. Future messages from the
     * number are judged normally again; threads already in Spam stay
     * there until Ben moves them or a filing pass re-files them.
     */
    fun unblockNumber(context: Context, number: String) {
        try {
            context.senderTagsDB.getAll()
                .filter { BlockedNumbers.numbersMatch(it.number, number) }
                .forEach { removeTag(context, it.number, StarterRules.TAG_BLOCKED) }
        } catch (e: Exception) {
            // Fall through: the system entry is still lifted below.
        }
        runCatching { context.deleteBlockedNumber(number) }
    }

    // endregion
}
