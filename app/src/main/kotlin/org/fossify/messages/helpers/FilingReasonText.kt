package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.R
import org.fossify.messages.filtering.FilingReason
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.FilterAuditEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders a [FilingReason] as the one-line answer to "Why is this here?". */
object FilingReasonText {

    fun describe(context: Context, conversation: Conversation, entry: FilterAuditEntry?): String {
        return when (val reason = FilingReason.reason(conversation.manuallyFiled, conversation.mailbox, entry)) {
            FilingReason.Reason.HandMoved -> context.getString(R.string.filing_reason_hand_moved)
            FilingReason.Reason.NoRecord -> context.getString(R.string.no_filing_record)
            is FilingReason.Reason.Filed -> {
                val destination = FolderManager.displayName(context, reason.mailbox)
                val whenText = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
                    .format(Date(reason.timestamp))
                if (reason.ruleName == FilterAuditEntry.DEFAULT_RULE_NAME) {
                    context.getString(R.string.filing_reason_default_text, destination, whenText)
                } else {
                    context.getString(R.string.filing_reason_text, destination, reason.ruleName, whenText)
                }
            }
        }
    }
}
