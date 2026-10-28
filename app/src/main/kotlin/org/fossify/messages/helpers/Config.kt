package org.fossify.messages.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import org.fossify.messages.extensions.getDefaultKeyboardHeight
import org.fossify.messages.models.Conversation

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    fun saveUseSIMIdAtNumber(number: String, SIMId: Int) {
        prefs.edit().putInt(USE_SIM_ID_PREFIX + number, SIMId).apply()
    }

    fun getUseSIMIdAtNumber(number: String) = prefs.getInt(USE_SIM_ID_PREFIX + number, 0)

    var showCharacterCounter: Boolean
        get() = prefs.getBoolean(SHOW_CHARACTER_COUNTER, false)
        set(showCharacterCounter) = prefs.edit()
            .putBoolean(SHOW_CHARACTER_COUNTER, showCharacterCounter).apply()

    var useSimpleCharacters: Boolean
        get() = prefs.getBoolean(USE_SIMPLE_CHARACTERS, false)
        set(useSimpleCharacters) = prefs.edit()
            .putBoolean(USE_SIMPLE_CHARACTERS, useSimpleCharacters).apply()

    var sendOnEnter: Boolean
        get() = prefs.getBoolean(SEND_ON_ENTER, false)
        set(sendOnEnter) = prefs.edit().putBoolean(SEND_ON_ENTER, sendOnEnter).apply()

    var enableDeliveryReports: Boolean
        get() = prefs.getBoolean(ENABLE_DELIVERY_REPORTS, false)
        set(enableDeliveryReports) = prefs.edit()
            .putBoolean(ENABLE_DELIVERY_REPORTS, enableDeliveryReports).apply()

    var sendLongMessageMMS: Boolean
        get() = prefs.getBoolean(SEND_LONG_MESSAGE_MMS, false)
        set(sendLongMessageMMS) = prefs.edit().putBoolean(SEND_LONG_MESSAGE_MMS, sendLongMessageMMS)
            .apply()

    var sendGroupMessageMMS: Boolean
        get() = prefs.getBoolean(SEND_GROUP_MESSAGE_MMS, false)
        set(sendGroupMessageMMS) = prefs.edit()
            .putBoolean(SEND_GROUP_MESSAGE_MMS, sendGroupMessageMMS).apply()

    val isGroupMessageMmsPreferenceResolved: Boolean
        get() = prefs.contains(SEND_GROUP_MESSAGE_MMS)

    var lockScreenVisibilitySetting: Int
        get() = prefs.getInt(LOCK_SCREEN_VISIBILITY, LOCK_SCREEN_SENDER_MESSAGE)
        set(lockScreenVisibilitySetting) = prefs.edit()
            .putInt(LOCK_SCREEN_VISIBILITY, lockScreenVisibilitySetting).apply()

    var mmsFileSizeLimit: Long
        get() = prefs.getLong(MMS_FILE_SIZE_LIMIT, FILE_SIZE_200_KB)
        set(mmsFileSizeLimit) = prefs.edit().putLong(MMS_FILE_SIZE_LIMIT, mmsFileSizeLimit).apply()

    /**
     * Pinned conversations in the user's order (revision 10): the pinned
     * section renders in this order, and drag-and-drop reordering writes
     * it back. Stored as one comma-joined string — a StringSet cannot
     * keep order. Migrates once from the old StringSet key, then the old
     * key is dropped.
     */
    var pinnedConversations: List<String>
        get() {
            if (!prefs.contains(PINNED_CONVERSATIONS_ORDER)) {
                val legacy = prefs.getStringSet(PINNED_CONVERSATIONS, null)
                if (legacy != null) {
                    val migrated = legacy.toList()
                    prefs.edit()
                        .putString(PINNED_CONVERSATIONS_ORDER, migrated.joinToString(","))
                        .remove(PINNED_CONVERSATIONS)
                        .apply()
                    return migrated
                }
            }
            return prefs.getString(PINNED_CONVERSATIONS_ORDER, "").orEmpty()
                .split(",").filter { it.isNotEmpty() }
        }
        set(pinnedConversations) = prefs.edit()
            .putString(PINNED_CONVERSATIONS_ORDER, pinnedConversations.distinct().joinToString(",")).apply()

    /**
     * Mark Spam as read (revision 10): when on — the default — any filing
     * to Spam also marks the thread read in the provider, so nothing
     * filed to Spam ever surfaces as unread. When off, filing leaves
     * read state exactly as it was.
     */
    var markSpamAsRead: Boolean
        get() = prefs.getBoolean(MARK_SPAM_AS_READ, true)
        set(markSpamAsRead) = prefs.edit().putBoolean(MARK_SPAM_AS_READ, markSpamAsRead).apply()

    fun addPinnedConversationByThreadId(threadId: Long) {
        pinnedConversations = (pinnedConversations + threadId.toString()).distinct()
    }

    fun addPinnedConversations(conversations: List<Conversation>) {
        pinnedConversations = (pinnedConversations + conversations.map { it.threadId.toString() }).distinct()
    }

    fun removePinnedConversationByThreadId(threadId: Long) {
        pinnedConversations = pinnedConversations.minus(threadId.toString())
    }

    fun removePinnedConversations(conversations: List<Conversation>) {
        pinnedConversations =
            pinnedConversations.minus(conversations.map { it.threadId.toString() })
    }

    /** Move one pinned id within the user's order (drag-and-drop). Out-of-range moves are ignored. */
    fun movePinnedConversation(fromIndex: Int, toIndex: Int) {
        val ids = pinnedConversations.toMutableList()
        if (fromIndex !in ids.indices || toIndex !in ids.indices) return
        ids.add(toIndex, ids.removeAt(fromIndex))
        pinnedConversations = ids
    }

    var blockedKeywords: Set<String>
        get() = prefs.getStringSet(BLOCKED_KEYWORDS, HashSet<String>())!!
        set(blockedKeywords) = prefs.edit().putStringSet(BLOCKED_KEYWORDS, blockedKeywords).apply()

    fun addBlockedKeyword(keyword: String) {
        blockedKeywords = blockedKeywords.plus(keyword)
    }

    fun removeBlockedKeyword(keyword: String) {
        blockedKeywords = blockedKeywords.minus(keyword)
    }

    var exportSms: Boolean
        get() = prefs.getBoolean(EXPORT_SMS, true)
        set(exportSms) = prefs.edit().putBoolean(EXPORT_SMS, exportSms).apply()

    var exportMms: Boolean
        get() = prefs.getBoolean(EXPORT_MMS, true)
        set(exportMms) = prefs.edit().putBoolean(EXPORT_MMS, exportMms).apply()

    var importSms: Boolean
        get() = prefs.getBoolean(IMPORT_SMS, true)
        set(importSms) = prefs.edit().putBoolean(IMPORT_SMS, importSms).apply()

    var importMms: Boolean
        get() = prefs.getBoolean(IMPORT_MMS, true)
        set(importMms) = prefs.edit().putBoolean(IMPORT_MMS, importMms).apply()

    var wasDbCleared: Boolean
        get() = prefs.getBoolean(WAS_DB_CLEARED, false)
        set(wasDbCleared) = prefs.edit().putBoolean(WAS_DB_CLEARED, wasDbCleared).apply()

    var keyboardHeight: Int
        get() = prefs.getInt(SOFT_KEYBOARD_HEIGHT, context.getDefaultKeyboardHeight())
        set(keyboardHeight) = prefs.edit().putInt(SOFT_KEYBOARD_HEIGHT, keyboardHeight).apply()

    var useRecycleBin: Boolean
        get() = prefs.getBoolean(USE_RECYCLE_BIN, false)
        set(useRecycleBin) = prefs.edit().putBoolean(USE_RECYCLE_BIN, useRecycleBin).apply()

    var lastRecycleBinCheck: Long
        get() = prefs.getLong(LAST_RECYCLE_BIN_CHECK, 0L)
        set(lastRecycleBinCheck) = prefs.edit().putLong(LAST_RECYCLE_BIN_CHECK, lastRecycleBinCheck)
            .apply()

    var isArchiveAvailable: Boolean
        get() = prefs.getBoolean(IS_ARCHIVE_AVAILABLE, true)
        set(isArchiveAvailable) = prefs.edit().putBoolean(IS_ARCHIVE_AVAILABLE, isArchiveAvailable)
            .apply()

    var customNotifications: Set<String>
        get() = prefs.getStringSet(CUSTOM_NOTIFICATIONS, HashSet<String>())!!
        set(customNotifications) = prefs.edit()
            .putStringSet(CUSTOM_NOTIFICATIONS, customNotifications).apply()

    fun addCustomNotificationsByThreadId(threadId: Long) {
        customNotifications = customNotifications.plus(threadId.toString())
    }

    fun removeCustomNotificationsByThreadId(threadId: Long) {
        customNotifications = customNotifications.minus(threadId.toString())
    }

    var lastBlockedKeywordExportPath: String
        get() = prefs.getString(LAST_BLOCKED_KEYWORD_EXPORT_PATH, "")!!
        set(lastBlockedNumbersExportPath) = prefs.edit()
            .putString(LAST_BLOCKED_KEYWORD_EXPORT_PATH, lastBlockedNumbersExportPath).apply()

    var keepConversationsArchived: Boolean
        get() = prefs.getBoolean(KEEP_CONVERSATIONS_ARCHIVED, false)
        set(keepConversationsArchived) = prefs.edit()
            .putBoolean(KEEP_CONVERSATIONS_ARCHIVED, keepConversationsArchived).apply()

    var filterRulesSeeded: Boolean
        get() = prefs.getBoolean(FILTER_RULES_SEEDED, false)
        set(filterRulesSeeded) = prefs.edit()
            .putBoolean(FILTER_RULES_SEEDED, filterRulesSeeded).apply()

    /** Version of the starter rule pack last seeded/repaired; 0 = never. */
    var filterRulesSeedVersion: Int
        get() = prefs.getInt(FILTER_RULES_SEED_VERSION, 0)
        set(filterRulesSeedVersion) = prefs.edit()
            .putInt(FILTER_RULES_SEED_VERSION, filterRulesSeedVersion).apply()

    /** Mailboxes Ben silenced by hand from the folder view. Default: none. */
    var mutedMailboxes: Set<String>
        get() = prefs.getStringSet(MUTED_MAILBOXES, emptySet())!!.toSet()
        set(mutedMailboxes) = prefs.edit()
            .putStringSet(MUTED_MAILBOXES, mutedMailboxes).apply()
}
