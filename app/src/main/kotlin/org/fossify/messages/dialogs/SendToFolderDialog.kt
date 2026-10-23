package org.fossify.messages.dialogs

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.helpers.FilterEvaluator
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Mailbox

/**
 * "Send to folder" (revision 7): pick a destination — a system folder,
 * one of Ben's own folders, or "New folder…" to create one on the spot —
 * and the selected conversations are filed there by hand through
 * [FilterEvaluator.applyManualFiling], so the placement sticks and, for
 * 1:1 threads, the sender belongs there from now on. Any thread can go
 * to any offered folder, contact or not; the Locker is only offered
 * where a move-to-Locker action already existed (the conversation list).
 */
class SendToFolderDialog(
    private val activity: BaseSimpleActivity,
    private val conversations: List<Conversation>,
    private val includeLocker: Boolean,
    private val callback: (targetMailbox: String) -> Unit
) {

    init {
        ensureBackgroundThread {
            val folders = FolderManager.allFolders(activity)

            val mailboxes = ArrayList<String>()
            mailboxes += Mailbox.CONTACTS
            mailboxes += Mailbox.AUTH
            mailboxes += Mailbox.QUARANTINE
            mailboxes += Mailbox.SPAM
            if (includeLocker) {
                mailboxes += Mailbox.LOCKER
            }
            folders.forEach { mailboxes += Mailbox.customMailboxId(it.id) }

            val names = mailboxes.associateWith { FolderManager.displayName(activity, it) }
            val items = ArrayList<RadioItem>()
            mailboxes.forEachIndexed { index, mailbox ->
                items += RadioItem(index, names[mailbox] ?: mailbox)
            }
            items += RadioItem(NEW_FOLDER_ID, activity.getString(R.string.new_folder) + "…")

            val currentMailbox = conversations.map { it.mailbox }.distinct().singleOrNull()
            val checkedId = mailboxes.indexOf(currentMailbox)

            activity.runOnUiThread {
                RadioGroupDialog(activity, items, checkedId, R.string.send_to_folder) { selected ->
                    val id = selected as Int
                    if (id == NEW_FOLDER_ID) {
                        CreateFolderDialog(activity) { folder ->
                            fileTo(Mailbox.customMailboxId(folder.id))
                        }
                    } else {
                        fileTo(mailboxes[id])
                    }
                }
            }
        }
    }

    private fun fileTo(targetMailbox: String) {
        ensureBackgroundThread {
            conversations.forEach { conversation ->
                FilterEvaluator.applyManualFiling(activity, conversation, targetMailbox)
            }

            activity.runOnUiThread {
                activity.toast(
                    activity.getString(
                        R.string.placed_in_folder,
                        FolderManager.displayName(activity, targetMailbox)
                    )
                )
                callback(targetMailbox)
            }
        }
    }

    private companion object {
        const val NEW_FOLDER_ID = 1000
    }
}
