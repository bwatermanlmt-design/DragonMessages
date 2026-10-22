package org.fossify.messages.adapters

import android.view.Menu
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.deleteConversation
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Mailbox

class QuarantinedConversationsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    private val mailbox: String,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit
) : BaseConversationsAdapter(activity, recyclerView, onRefresh, itemClick) {

    override fun getActionMenuId() = R.menu.cab_quarantined_conversations

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_release -> releaseConversations()
            R.id.cab_block -> askConfirmBlockSenders()
            R.id.cab_select_all -> selectAll()
        }
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val items = resources.getQuantityString(R.plurals.delete_conversations, itemsCnt, itemsCnt)

        val baseString = org.fossify.commons.R.string.deletion_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                deleteConversations()
            }
        }
    }

    private fun deleteConversations() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsToRemove = getSelectedItems()
        conversationsToRemove.forEach {
            activity.deleteConversation(it.threadId)
            activity.notificationManager.cancel(it.threadId.hashCode())
        }

        removeConversationsFromList(conversationsToRemove)
    }

    /** Release: the conversation moves to My Contacts and leaves this list. */
    private fun releaseConversations() {
        if (selectedKeys.isEmpty()) {
            return
        }

        ensureBackgroundThread {
            val conversationsToRelease = getSelectedItems()
            conversationsToRelease.forEach {
                activity.conversationsDB.updateMailboxManuallyFiled(it.threadId, Mailbox.CONTACTS)
            }

            removeConversationsFromList(conversationsToRelease)
        }
    }

    private fun askConfirmBlockSenders() {
        ConfirmationDialog(
            activity = activity,
            message = activity.getString(R.string.block_senders_confirmation)
        ) {
            ensureBackgroundThread {
                blockSenders()
            }
        }
    }

    /**
     * Block: the number goes into the system BlockedNumberProvider (the hard
     * pre-check at receive time) and the conversation moves to Spam.
     */
    private fun blockSenders() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val selected = getSelectedItems()
        selected.forEach {
            activity.addBlockedNumber(it.phoneNumber)
            activity.conversationsDB.updateMailboxManuallyFiled(it.threadId, Mailbox.SPAM)
        }

        if (mailbox == Mailbox.SPAM) {
            // Already listed in Spam: the rows stay, only their state changed.
            activity.runOnUiThread {
                finishActMode()
                refreshConversations()
            }
        } else {
            removeConversationsFromList(selected)
        }
    }

    private fun removeConversationsFromList(removedConversations: List<Conversation>) {
        val newList = try {
            currentList.toMutableList().apply { removeAll(removedConversations) }
        } catch (ignored: Exception) {
            currentList.toMutableList()
        }

        activity.runOnUiThread {
            if (newList.none { selectedKeys.contains(it.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (newList.isEmpty()) {
                    refreshConversations()
                }
            }
        }
    }
}
