package org.fossify.messages.dialogs

import android.view.*
import androidx.appcompat.widget.PopupMenu
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.R
import org.fossify.messages.databinding.ItemManageBlockedSenderBinding
import org.fossify.messages.helpers.FilterEvaluator

/**
 * The blocked-numbers list: every number on the permanent record — the
 * app's own record unioned with the system block list (see
 * [FilterEvaluator.blockedNumbers]). Unblocking lifts both records.
 */
class ManageBlockedSendersAdapter(
    activity: BaseSimpleActivity, var blockedNumbers: ArrayList<String>, val listener: RefreshRecyclerViewListener?,
    recyclerView: MyRecyclerView, itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {
    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_blocked_senders

    override fun prepareActionMode(menu: Menu) {
        menu.apply {
            findItem(R.id.cab_copy_number).isVisible = isOneItemSelected()
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_copy_number -> copyNumberToClipboard()
            R.id.cab_unblock -> unblockSelection()
        }
    }

    override fun getSelectableItemCount() = blockedNumbers.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = blockedNumbers.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = blockedNumbers.indexOfFirst { it.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemManageBlockedSenderBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val blockedNumber = blockedNumbers[position]
        holder.bindView(blockedNumber, allowSingleClick = true, allowLongClick = true) { itemView, _ ->
            setupView(itemView, blockedNumber)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = blockedNumbers.size

    private fun getSelectedItems() = blockedNumbers.filter { selectedKeys.contains(it.hashCode()) }

    private fun setupView(view: View, blockedNumber: String) {
        ItemManageBlockedSenderBinding.bind(view).apply {
            root.setupViewBackground(activity)
            manageBlockedSenderHolder.isSelected = selectedKeys.contains(blockedNumber.hashCode())
            manageBlockedSenderTitle.apply {
                text = blockedNumber
                setTextColor(textColor)
            }

            overflowMenuIcon.drawable.apply {
                mutate()
                setTint(activity.getProperTextColor())
            }

            overflowMenuIcon.setOnClickListener {
                showPopupMenu(overflowMenuAnchor, blockedNumber)
            }
        }
    }

    private fun showPopupMenu(view: View, blockedNumber: String) {
        finishActMode()
        val theme = activity.getPopupMenuTheme()
        val contextTheme = ContextThemeWrapper(activity, theme)

        PopupMenu(contextTheme, view, Gravity.END).apply {
            inflate(getActionMenuId())
            setOnMenuItemClickListener { item ->
                val blockedNumberId = blockedNumber.hashCode()
                when (item.itemId) {
                    R.id.cab_copy_number -> {
                        executeItemMenuOperation(blockedNumberId) {
                            copyNumberToClipboard()
                        }
                    }

                    R.id.cab_unblock -> {
                        executeItemMenuOperation(blockedNumberId) {
                            unblockSelection()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun executeItemMenuOperation(blockedNumberId: Int, callback: () -> Unit) {
        selectedKeys.add(blockedNumberId)
        callback()
        selectedKeys.remove(blockedNumberId)
    }

    private fun copyNumberToClipboard() {
        val selectedNumber = getSelectedItems().firstOrNull() ?: return
        activity.copyToClipboard(selectedNumber)
        finishActMode()
    }

    private fun unblockSelection() {
        val unblockNumbers = HashSet<String>(selectedKeys.size)
        val positions = getSelectedItemPositions()

        getSelectedItems().forEach {
            unblockNumbers.add(it)
        }

        ensureBackgroundThread {
            unblockNumbers.forEach {
                FilterEvaluator.unblockNumber(activity, it)
            }

            activity.runOnUiThread {
                blockedNumbers.removeAll(unblockNumbers)
                removeSelectedItems(positions)
                if (blockedNumbers.isEmpty()) {
                    listener?.refreshItems()
                }
            }
        }
    }
}
