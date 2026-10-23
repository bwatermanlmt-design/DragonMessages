package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityManageBlockedSendersBinding
import org.fossify.messages.dialogs.AddBlockedNumberDialog
import org.fossify.messages.dialogs.ManageBlockedSendersAdapter
import org.fossify.messages.helpers.FilterEvaluator

/**
 * The permanent blocked-numbers record, as one list: numbers blocked from
 * a conversation, from the Not in Contacts / Spam screen, or added here —
 * together with anything on the system block list, so no block is ever
 * invisible. Unblocking a row lifts both records; the number's future
 * messages are then judged by the filters normally again.
 */
class ManageBlockedSendersActivity : SimpleActivity(), RefreshRecyclerViewListener {

    private val binding by viewBinding(ActivityManageBlockedSendersBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        updateBlockedNumbers()
        setupOptionsMenu()

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.manageBlockedSendersList))
        setupMaterialScrollListener(
            scrollingView = binding.manageBlockedSendersList,
            topAppBar = binding.blockedSendersAppbar
        )
        updateTextColors(binding.manageBlockedSendersWrapper)

        binding.manageBlockedSendersPlaceholder2.apply {
            underlineText()
            setTextColor(getProperPrimaryColor())
            setOnClickListener {
                addBlockedNumber()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.blockedSendersAppbar, NavigationIcon.Arrow)
    }

    private fun setupOptionsMenu() {
        binding.blockedSendersToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_blocked_number -> {
                    addBlockedNumber()
                    true
                }

                else -> false
            }
        }
    }

    override fun refreshItems() {
        updateBlockedNumbers()
    }

    private fun updateBlockedNumbers() {
        ensureBackgroundThread {
            val blockedNumbers = ArrayList(FilterEvaluator.blockedNumbers(this))
            runOnUiThread {
                ManageBlockedSendersAdapter(
                    activity = this,
                    blockedNumbers = blockedNumbers,
                    listener = this,
                    recyclerView = binding.manageBlockedSendersList
                ) {
                    askUnblock(it as String)
                }.apply {
                    binding.manageBlockedSendersList.adapter = this
                }

                binding.manageBlockedSendersPlaceholder.beVisibleIf(blockedNumbers.isEmpty())
                binding.manageBlockedSendersPlaceholder2.beVisibleIf(blockedNumbers.isEmpty())
            }
        }
    }

    private fun addBlockedNumber() {
        AddBlockedNumberDialog(this) {
            updateBlockedNumbers()
        }
    }

    private fun askUnblock(number: String) {
        val question = String.format(getString(R.string.unblock_confirmation), number)
        ConfirmationDialog(this, question) {
            ensureBackgroundThread {
                FilterEvaluator.unblockNumber(this, number)
                updateBlockedNumbers()
            }
        }
    }
}
