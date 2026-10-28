package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per filter verdict, so "why did this message land here?" is always
 * answerable: which rule fired, for which sender, and where it delivered to.
 */
@Entity(tableName = "filter_audit_log")
data class FilterAuditEntry(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    @ColumnInfo(name = "timestamp") var timestamp: Long,
    @ColumnInfo(name = "sender") var sender: String,
    @ColumnInfo(name = "rule_name") var ruleName: String,
    @ColumnInfo(name = "mailbox") var mailbox: String,
    @ColumnInfo(name = "channel") var channel: String,
) {
    companion object {
        const val CHANNEL_SMS = "sms"
        const val CHANNEL_MMS = "mms"

        /** Verdicts from the manual "File existing conversations" pass. */
        const val CHANNEL_RETRO = "retro"

        /** Filings from an on-demand macro run (revision 7). */
        const val CHANNEL_MACRO = "macro"

        /** Rule name recorded when no rule matched and the built-in default applied. */
        const val DEFAULT_RULE_NAME = "(default)"

        /** Rule names recorded for conversations the retro pass deliberately left alone. */
        const val RETRO_SKIP_LOCKER = "(retro: locker untouched)"
        const val RETRO_SKIP_NO_INBOUND = "(retro: no inbound message)"

        /** Suffix on the audit rule name when a retro filing is a group conversation. */
        const val RETRO_GROUP_SUFFIX = " (retro: group)"

        /** Suffix on the audit rule name when precedence changed the raw verdict's placement. */
        const val PRECEDENCE_KEPT_SUFFIX = " (precedence: kept)"
    }
}
