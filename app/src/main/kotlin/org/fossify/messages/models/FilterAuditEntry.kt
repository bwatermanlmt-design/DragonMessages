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

        /** Rule name recorded when no rule matched and the built-in default applied. */
        const val DEFAULT_RULE_NAME = "(default)"
    }
}
