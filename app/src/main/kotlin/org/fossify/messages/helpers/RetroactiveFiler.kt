package org.fossify.messages.helpers

import android.content.Context
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.filtering.RetroFiler
import org.fossify.messages.models.Mailbox

/**
 * The manual "File existing conversations" pass. Phase 1 filed messages on
 * arrival only, which left the existing store unsorted; this pass runs the
 * same rule engine over the stored conversations. Filing never deletes
 * anything, protected conversations are skipped (see [RetroFiler]), and a
 * re-run with unchanged rules changes nothing.
 */
object RetroactiveFiler {

    data class Summary(
        val filedToContacts: Int = 0,
        val filedToAuth: Int = 0,
        val filedToQuarantine: Int = 0,
        val filedToSpam: Int = 0,
        val noInboundSkipped: Int = 0,
        val manuallyFiledSkipped: Int = 0,
        val lockerSkipped: Int = 0,
        val unchanged: Int = 0,
    ) {
        val totalFiled: Int
            get() = filedToContacts + filedToAuth + filedToQuarantine + filedToSpam
    }

    /** Must be called from a background thread. */
    fun fileExistingConversations(context: Context): Summary {
        var summary = Summary()
        val conversations = context.conversationsDB.getAllNonArchivedConversations()
        conversations.forEach { conversation ->
            summary = when (val outcome = FilterEvaluator.evaluateRetro(context, conversation)) {
                is FilterEvaluator.RetroOutcome.Filed -> when (outcome.verdict.mailbox) {
                    Mailbox.CONTACTS -> summary.copy(filedToContacts = summary.filedToContacts + 1)
                    Mailbox.AUTH -> summary.copy(filedToAuth = summary.filedToAuth + 1)
                    Mailbox.QUARANTINE -> summary.copy(filedToQuarantine = summary.filedToQuarantine + 1)
                    Mailbox.SPAM -> summary.copy(filedToSpam = summary.filedToSpam + 1)
                    else -> summary
                }

                is FilterEvaluator.RetroOutcome.Skipped -> when (outcome.reason) {
                    RetroFiler.SkipReason.NO_INBOUND ->
                        summary.copy(noInboundSkipped = summary.noInboundSkipped + 1)

                    RetroFiler.SkipReason.MANUALLY_FILED ->
                        summary.copy(manuallyFiledSkipped = summary.manuallyFiledSkipped + 1)

                    RetroFiler.SkipReason.LOCKER -> summary.copy(lockerSkipped = summary.lockerSkipped + 1)
                }

                FilterEvaluator.RetroOutcome.Unchanged -> summary.copy(unchanged = summary.unchanged + 1)
            }
        }
        return summary
    }
}

/** Runs the retroactive filing pass off the UI thread and reports the summary. */
fun SimpleActivity.runRetroactiveFiling() {
    toast(R.string.filing_existing_conversations)
    ensureBackgroundThread {
        val summary = RetroactiveFiler.fileExistingConversations(this)
        runOnUiThread {
            ConfirmationDialog(this, retroSummaryMessage(summary)) {}
            refreshConversations()
        }
    }
}

private fun SimpleActivity.retroSummaryMessage(summary: RetroactiveFiler.Summary): String {
    val lines = ArrayList<String>()
    lines += "${getString(R.string.filed_conversations)}: ${summary.totalFiled}"
    if (summary.filedToContacts > 0) {
        lines += "${getString(R.string.my_contacts)}: ${summary.filedToContacts}"
    }
    if (summary.filedToAuth > 0) {
        lines += "${getString(R.string.auth_and_services)}: ${summary.filedToAuth}"
    }
    if (summary.filedToQuarantine > 0) {
        lines += "${getString(R.string.quarantine)}: ${summary.filedToQuarantine}"
    }
    if (summary.filedToSpam > 0) {
        lines += "${getString(R.string.spam)}: ${summary.filedToSpam}"
    }
    if (summary.noInboundSkipped > 0) {
        lines += "${getString(R.string.skipped_no_inbound)}: ${summary.noInboundSkipped}"
    }
    if (summary.manuallyFiledSkipped > 0) {
        lines += "${getString(R.string.skipped_manually_filed)}: ${summary.manuallyFiledSkipped}"
    }
    if (summary.lockerSkipped > 0) {
        lines += "${getString(R.string.skipped_locker)}: ${summary.lockerSkipped}"
    }
    if (summary.unchanged > 0) {
        lines += "${getString(R.string.already_filed)}: ${summary.unchanged}"
    }
    return lines.joinToString("\n")
}
