package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityMacroRunBinding
import org.fossify.messages.databinding.ItemMacroMatchBinding
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.MacroCandidate
import org.fossify.messages.filtering.MacroRuns
import org.fossify.messages.helpers.FilterEvaluator
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.helpers.MACRO_ID
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.FilterRule

/**
 * One on-demand macro run (revision 7): the macro's criteria are matched
 * against every stored conversation — contacts and non-contacts alike —
 * and the matches are offered as a checklist, all ticked. Locker
 * threads, blocked senders, and Ben's hand filings never appear (see
 * [MacroRuns]). "File selected" writes each ticked thread to the macro's
 * target as a hand filing — thread and sender record both — and leaves
 * an audit entry per thread on the macro channel.
 */
class MacroRunActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityMacroRunBinding::inflate)

    private var macro: FilterRule? = null
    private var matches = ArrayList<Conversation>()
    private val checkedThreadIds = HashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val macroId = intent.getLongExtra(MACRO_ID, 0)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.macroRunScrollview))

        binding.macroFileButton.setOnClickListener {
            fileSelected()
        }

        loadRun(macroId)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.macroRunAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.macroRunCoordinator)
    }

    private fun loadRun(macroId: Long) {
        ensureBackgroundThread {
            val loadedMacro = try {
                filterRulesDB.getById(macroId)
            } catch (e: Exception) {
                null
            } ?: return@ensureBackgroundThread
            macro = loadedMacro

            val conversations = try {
                conversationsDB.getAllNonArchivedConversations()
            } catch (e: Exception) {
                emptyList()
            }

            val candidates = conversations.map { conversation ->
                MacroCandidate(
                    threadId = conversation.threadId,
                    sender = conversation.phoneNumber,
                    senderName = conversation.title,
                    body = conversation.snippet,
                    senderInContacts = !conversation.isGroupConversation &&
                        FilterEvaluator.isInContacts(this, conversation.phoneNumber),
                    currentMailbox = conversation.mailbox,
                    manuallyFiled = conversation.manuallyFiled,
                    senderBlocked = FilterEvaluator.isSenderBlocked(this, conversation.phoneNumber),
                    isGroupConversation = conversation.isGroupConversation
                )
            }

            val selectedIds = MacroRuns.select(loadedMacro, candidates)
                .map { it.threadId }
                .toSet()
            val selected = conversations.filter { it.threadId in selectedIds }
                .sortedByDescending { it.date }

            runOnUiThread {
                binding.macroRunToolbar.title =
                    getString(R.string.macro_run_title, loadedMacro.name)
                showMatches(ArrayList(selected))
            }
        }
    }

    private fun showMatches(conversations: ArrayList<Conversation>) {
        matches = conversations
        checkedThreadIds.clear()
        conversations.forEach { checkedThreadIds += it.threadId }

        binding.macroNoMatchesPlaceholder.beVisibleIf(conversations.isEmpty())
        val container = binding.macroMatchesContainer
        container.removeAllViews()

        conversations.forEach { conversation ->
            val row = ItemMacroMatchBinding.inflate(layoutInflater, container, false)
            row.matchTitle.text = conversation.title
            row.matchSnippet.text = conversation.snippet
            row.matchCurrentFolder.text = getString(
                R.string.macro_current_folder,
                FolderManager.displayName(this, conversation.mailbox)
            )
            row.matchCheckbox.isChecked = true
            row.matchCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    checkedThreadIds += conversation.threadId
                } else {
                    checkedThreadIds -= conversation.threadId
                }
                updateFileButton()
            }
            row.root.setOnClickListener {
                row.matchCheckbox.isChecked = !row.matchCheckbox.isChecked
            }
            container.addView(row.root)
        }
        updateTextColors(container)
        updateFileButton()
    }

    private fun updateFileButton() {
        binding.macroFileButton.beVisibleIf(matches.isNotEmpty())
        binding.macroFileButton.text =
            getString(R.string.macro_file_selected, checkedThreadIds.size)
    }

    private fun fileSelected() {
        val currentMacro = macro ?: return
        val toFile = matches.filter { it.threadId in checkedThreadIds }
        if (toFile.isEmpty()) {
            finish()
            return
        }

        ensureBackgroundThread {
            toFile.forEach { conversation ->
                FilterEvaluator.applyManualFiling(this, conversation, currentMacro.mailbox)
                FilterEvaluator.recordMacroFiling(
                    context = this,
                    macroName = currentMacro.name,
                    sender = conversation.phoneNumber,
                    mailbox = currentMacro.mailbox
                )
            }

            runOnUiThread {
                toast(
                    getString(
                        R.string.macro_filed_count,
                        toFile.size,
                        FolderManager.displayName(this@MacroRunActivity, currentMacro.mailbox)
                    )
                )
                finish()
            }
        }
    }
}
