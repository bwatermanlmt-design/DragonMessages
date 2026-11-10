package org.fossify.messages.dialogs

import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.helpers.FilterEvaluator
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.helpers.LockerGate
import org.fossify.messages.helpers.LockerManager
import org.fossify.messages.helpers.isDefaultSmsApp
import org.fossify.messages.helpers.requestDefaultSmsApp
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Mailbox

/**
 * "Send to folder" (revision 7): pick a destination — a system folder,
 * one of Ben's own folders, or "New folder…" to create one on the spot —
 * and the selected conversations are filed there by hand through
 * [FilterEvaluator.applyManualFiling], so the placement sticks and, for
 * 1:1 threads, the sender belongs there from now on. Any thread can go
 * to any offered folder, contact or not; the Locker is offered wherever
 * filing is offered (revision 11) — including Not in Contacts and the
 * thread view, where the threads most worth locking live.
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
        // A Locker move encrypts/decrypts every message in the
        // background — seconds for a thread with large files — so
        // show a status while it runs instead of silence (1.10.18).
        val involvesLocker = conversations.any {
            (targetMailbox == Mailbox.LOCKER && it.mailbox != Mailbox.LOCKER) ||
                (it.mailbox == Mailbox.LOCKER && targetMailbox != Mailbox.LOCKER)
        }
        val progressDialog = if (involvesLocker) {
            LockerGate.showLockerProgressDialog(
                activity,
                activity.getString(
                    if (targetMailbox == Mailbox.LOCKER) R.string.locker_moving_to_locker else R.string.locker_unlocking
                )
            )
        } else {
            null
        }
        ensureBackgroundThread {
            // The Locker is not a label: filing there encrypts the thread
            // and deletes it from the shared database, and filing out
            // restores it first — then the chosen destination applies, so
            // an explicit pick (Spam, a folder, …) wins over where the
            // thread came from. A failed lock or unlock leaves the thread
            // exactly where it was.
            var failed = false
            var needsRole = false
            conversations.forEach { conversation ->
                when {
                    targetMailbox == Mailbox.LOCKER && conversation.mailbox != Mailbox.LOCKER ->
                        if (!LockerManager(activity).lockThread(conversation.threadId)) {
                            failed = true
                        }

                    conversation.mailbox == Mailbox.LOCKER && targetMailbox != Mailbox.LOCKER -> {
                        // Unlocking writes the messages back to the
                        // phone's shared database, which only the
                        // default texting app may do. If Dragon is not
                        // the default, say so and offer to make it the
                        // default, instead of failing with the generic
                        // toast.
                        if (!activity.isDefaultSmsApp()) {
                            needsRole = true
                        } else if (LockerManager(activity).unlockThread(conversation.threadId)) {
                            FilterEvaluator.applyManualFiling(activity, conversation, targetMailbox)
                        } else {
                            failed = true
                        }
                    }

                    else -> {
                        FilterEvaluator.applyManualFiling(activity, conversation, targetMailbox)
                    }
                }
            }

            activity.runOnUiThread {
                runCatching { progressDialog?.dismiss() }
                if (needsRole) {
                    ConfirmationDialog(
                        activity,
                        message = activity.getString(R.string.locker_unlock_needs_default),
                        positive = R.string.locker_set_as_default,
                        negative = org.fossify.commons.R.string.no
                    ) {
                        activity.requestDefaultSmsApp()
                    }
                } else if (failed) {
                    activity.toast(
                        activity.getString(
                            if (targetMailbox == Mailbox.LOCKER) {
                                R.string.locker_lock_failed
                            } else {
                                R.string.locker_unlock_failed
                            }
                        )
                    )
                } else {
                    activity.toast(
                        activity.getString(
                            R.string.placed_in_folder,
                            FolderManager.displayName(activity, targetMailbox)
                        )
                    )
                }
                callback(targetMailbox)
            }
        }
    }

    private companion object {
        const val NEW_FOLDER_ID = 1000
    }
}
