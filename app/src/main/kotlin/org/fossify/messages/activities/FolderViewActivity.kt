package org.fossify.messages.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ExportResult
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyTextView
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityFolderViewBinding
import org.fossify.messages.databinding.ItemFolderRowBinding
import org.fossify.messages.dialogs.CreateFolderDialog
import org.fossify.messages.dialogs.ExportMessagesDialog
import org.fossify.messages.dialogs.RenameFolderDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.foldersDB
import org.fossify.messages.helpers.FILTER_RULES_EXPORT_EXTENSION
import org.fossify.messages.helpers.FilterRulesExporter
import org.fossify.messages.helpers.FilterRulesImporter
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.helpers.FolderSectionOrder
import org.fossify.messages.helpers.LockerGate
import org.fossify.messages.helpers.MAILBOX_FILTER
import org.fossify.messages.helpers.RuleSetStore
import org.fossify.messages.models.Folder
import org.fossify.messages.models.Mailbox

/**
 * The home screen (landing page, 2026-11-07): three sections.
 * Folders — Contacts, Not in Contacts, Auth and Businesses, the
 * user's own folders, Locker — in the user's order, each movable up
 * and down from its long-press menu. Then Spam alone in its own
 * section. Then the actions: Rules, Import Rules, Export messages,
 * Export Rules, Suggest rules. Tapping a folder opens its
 * conversations in MainActivity. The Locker row is gated: a
 * biometric/device-credential prompt must succeed before any locker
 * content is shown. Suggest rules is a placeholder until the pattern
 * seeker is built — it says so when tapped.
 */
class FolderViewActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityFolderViewBinding::inflate)

    private val messagesFileType = "application/json"

    private val saveRulesDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(messagesFileType)) { uri ->
            if (uri != null) {
                exportRules(uri)
            }
        }

    private val getRulesContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importRules(uri)
            }
        }

    private var exportMessagesDialog: ExportMessagesDialog? = null

    private val saveMessagesDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(messagesFileType)) { uri ->
            if (uri != null) {
                toast(org.fossify.commons.R.string.exporting)
                exportMessagesDialog?.exportMessages(uri)
            }
        }

    /** The order the Folders section was last rendered in. */
    private var currentSectionOrder: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.folderNestedScrollview))

        binding.mailboxContactsHolder.setOnClickListener { openMailbox(Mailbox.CONTACTS) }
        binding.mailboxAuthHolder.setOnClickListener { openMailbox(Mailbox.AUTH) }
        binding.mailboxQuarantineHolder.setOnClickListener { openMailbox(Mailbox.QUARANTINE) }
        binding.mailboxSpamHolder.setOnClickListener { openMailbox(Mailbox.SPAM) }
        binding.mailboxLockerHolder.setOnClickListener { unlockLockerThenOpen() }

        // Long-press a folder in the Folders section for its options
        // (move up/down, silence); Spam is alone in its section and
        // silent by definition, so its long press just says so.
        binding.mailboxContactsHolder.setOnLongClickListener { showSystemFolderOptions(Mailbox.CONTACTS) }
        binding.mailboxAuthHolder.setOnLongClickListener { showSystemFolderOptions(Mailbox.AUTH) }
        binding.mailboxQuarantineHolder.setOnLongClickListener { showSystemFolderOptions(Mailbox.QUARANTINE) }
        binding.mailboxLockerHolder.setOnLongClickListener { showSystemFolderOptions(Mailbox.LOCKER) }
        binding.mailboxSpamHolder.setOnLongClickListener { toggleFolderMute(Mailbox.SPAM) }

        setupOptionsMenu()
        setupActionRows()
    }

    private fun setupOptionsMenu() {
        binding.folderToolbar.inflateMenu(R.menu.folder_view_menu)
        binding.folderToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.new_folder -> {
                    showCreateFolderDialog()
                    true
                }

                R.id.manage_folders -> {
                    openFolderManager()
                    true
                }

                R.id.macros -> {
                    openMacros()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * The actions section: Rules (look at and add rules), Import
     * Rules, Export messages, Export Rules, Suggest rules. New folder
     * and Manage folders live in the toolbar overflow menu instead.
     */
    private fun setupActionRows() {
        binding.actionRulesHolder.setOnClickListener { openMacros() }
        binding.actionImportRulesHolder.setOnClickListener {
            getRulesContent.launch(arrayOf(messagesFileType))
        }
        binding.actionExportMessagesHolder.setOnClickListener {
            exportMessagesDialog = ExportMessagesDialog(this) { fileName ->
                saveMessagesDocument.launch("$fileName.json")
            }
        }
        binding.actionExportRulesHolder.setOnClickListener {
            saveRulesDocument.launch("messages-filter-rules$FILTER_RULES_EXPORT_EXTENSION")
        }
        binding.actionSuggestRulesHolder.setOnClickListener {
            startActivity(Intent(this, SuggestRulesActivity::class.java))
        }
    }

    private fun showCreateFolderDialog() {
        CreateFolderDialog(this) {
            refreshFolderSection()
        }
    }

    private fun openFolderManager() {
        startActivity(Intent(this, ManageFoldersActivity::class.java))
    }

    private fun openMacros() {
        startActivity(Intent(this, MacrosActivity::class.java))
    }

    /**
     * The long-press menu for a system folder in the Folders section:
     * move it up or down (omitted at the ends), or silence it.
     */
    private fun showSystemFolderOptions(mailbox: String): Boolean {
        val order = currentSectionOrder.ifEmpty {
            FolderSectionOrder.defaultOrder(emptyList())
        }
        val index = order.indexOf(mailbox)
        // The move items exist only when the folder can move that way,
        // so each item's id maps to its action explicitly.
        val items = arrayListOf<RadioItem>()
        val actions = HashMap<Int, String>()
        var nextId = 0
        if (index > 0) {
            items += RadioItem(nextId, getString(R.string.folder_move_up))
            actions[nextId] = "up"
            nextId++
        }
        if (index != -1 && index < order.lastIndex) {
            items += RadioItem(nextId, getString(R.string.folder_move_down))
            actions[nextId] = "down"
            nextId++
        }
        items += RadioItem(
            nextId,
            getString(if (mailbox in config.mutedMailboxes) R.string.unsilence_folder else R.string.silence_folder)
        )
        actions[nextId] = "mute"
        RadioGroupDialog(this, items) { selected ->
            when (actions[selected as Int]) {
                "up" -> moveSectionEntry(mailbox, up = true)
                "down" -> moveSectionEntry(mailbox, up = false)
                else -> {
                    toggleFolderMute(mailbox)
                    refreshFolderSection()
                }
            }
        }
        return true
    }

    /**
     * Move one entry in the Folders section and persist the order.
     * Top-level user folders also get their stored sort order
     * re-ranked to match, so Manage folders lists them in the same
     * relative order.
     */
    private fun moveSectionEntry(entry: String, up: Boolean) {
        val order = currentSectionOrder.ifEmpty {
            FolderSectionOrder.defaultOrder(emptyList())
        }
        val newOrder = FolderSectionOrder.move(order, entry, up)
        if (newOrder == order) return
        config.folderSectionOrder = newOrder
        currentSectionOrder = newOrder
        ensureBackgroundThread {
            rerankTopLevelCustomFolders(newOrder)
            runOnUiThread { refreshFolderSection() }
        }
    }

    private fun rerankTopLevelCustomFolders(sectionOrder: List<String>) {
        try {
            val sectionIds = sectionOrder.filter { Mailbox.isCustomMailbox(it) }
            val folders = foldersDB.getAllFolders().filter { it.parentId == null }
            val byId = folders.associateBy { it.id }
            val ordered = sectionIds.mapNotNull { byId[Mailbox.folderIdOf(it)] } +
                folders.filter { Mailbox.customMailboxId(it.id) !in sectionIds }
            ordered.forEachIndexed { i, folder ->
                if (folder.sortOrder != i) {
                    foldersDB.insertOrUpdateFolder(folder.copy(sortOrder = i))
                }
            }
        } catch (e: Exception) {
            // The section order is already saved; the sort-order
            // re-rank is cosmetic and must not block the move.
        }
    }

    private fun toggleFolderMute(mailbox: String): Boolean {
        if (mailbox == Mailbox.SPAM) {
            // Spam is the filter output: silent by definition, mute is a no-op.
            toast(R.string.spam_always_silent)
            return true
        }

        val muted = config.mutedMailboxes
        config.mutedMailboxes = if (mailbox in muted) {
            toast(R.string.folder_unsilenced)
            muted - mailbox
        } else {
            toast(R.string.folder_silenced)
            muted + mailbox
        }
        updateFolderNames()
        return true
    }

    private fun updateFolderNames() {
        val muted = config.mutedMailboxes
        fun label(mailbox: String): String {
            val name = getString(Mailbox.displayNameRes(mailbox))
            return if (mailbox in muted) name + getString(R.string.folder_silenced_suffix) else name
        }

        binding.mailboxContactsName.text = label(Mailbox.CONTACTS)
        binding.mailboxAuthName.text = label(Mailbox.AUTH)
        binding.mailboxQuarantineName.text = label(Mailbox.QUARANTINE)
        binding.mailboxSpamName.text = label(Mailbox.SPAM)
        binding.mailboxLockerName.text = label(Mailbox.LOCKER)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.folderAppbar, NavigationIcon.None)
        updateTextColors(binding.folderCoordinator)
        updateFolderNames()
        loadCounts()
        refreshFolderSection()
    }

    /**
     * The Folders section: system folders and Ben's own folders
     * (revision 7), in the stored order — Contacts, Not in Contacts,
     * Auth, user folders, Locker by default. Nested folders render
     * under their parent, indented one step per level. The visited set
     * keeps corrupt data (cycles, orphans whose parent is gone) from
     * looping or vanishing: orphans land at the top level with their
     * own children beneath them.
     */
    private fun refreshFolderSection() {
        ensureBackgroundThread {
            val folders = FolderManager.allFolders(this)
            val labelsByMailbox = try {
                foldersDB.getAllLabelMappings().groupBy({ it.mailbox }, { it.label })
            } catch (e: Exception) {
                emptyMap()
            }
            val counts = HashMap<String, Int>()
            val unreadCounts = HashMap<String, Int>()
            folders.forEach { folder ->
                val mailbox = Mailbox.customMailboxId(folder.id)
                counts[mailbox] = try {
                    conversationsDB.getCountByMailbox(mailbox)
                } catch (e: Exception) {
                    0
                }
                unreadCounts[mailbox] = try {
                    conversationsDB.getUnreadCountByMailbox(mailbox)
                } catch (e: Exception) {
                    0
                }
            }

            runOnUiThread {
                renderFolderSection(folders, labelsByMailbox, counts, unreadCounts)
            }
        }
    }

    private fun renderFolderSection(
        folders: List<Folder>,
        labelsByMailbox: Map<String, List<String>>,
        counts: Map<String, Int>,
        unreadCounts: Map<String, Int>
    ) {
        val container = binding.foldersSectionContainer
        container.removeAllViews()
        val muted = config.mutedMailboxes

        val topLevelIds = folders.filter { it.parentId == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { Mailbox.customMailboxId(it.id) }
        val order = FolderSectionOrder.resolveOrder(config.folderSectionOrder, topLevelIds)
        currentSectionOrder = order

        val systemHolders = mapOf(
            Mailbox.CONTACTS to binding.mailboxContactsHolder,
            Mailbox.QUARANTINE to binding.mailboxQuarantineHolder,
            Mailbox.AUTH to binding.mailboxAuthHolder,
            Mailbox.LOCKER to binding.mailboxLockerHolder
        )
        val byId = folders.associateBy { it.id }
        val byParent = folders.groupBy { it.parentId }
        // One level of nesting indents by 24dp — no dimen dependency.
        val indentPx = (24 * resources.displayMetrics.density).toInt()
        val visited = HashSet<String>()

        fun addFolderSubtree(folder: Folder, depth: Int) {
            if (!visited.add(folder.id)) return
            val mailbox = Mailbox.customMailboxId(folder.id)
            val row = ItemFolderRowBinding.inflate(layoutInflater, container, false)
            if (depth > 0) {
                row.root.setPaddingRelative(
                    row.root.paddingStart + depth * indentPx,
                    row.root.paddingTop,
                    row.root.paddingEnd,
                    row.root.paddingBottom
                )
            }

            row.folderRowIcon.setImageResource(FolderManager.iconRes(folder.image))
            row.folderRowIconHolder.backgroundTintList = ColorStateList.valueOf(folder.color)
            row.folderRowName.text = folder.name +
                if (mailbox in muted) getString(R.string.folder_silenced_suffix) else ""

            val labels = labelsByMailbox[mailbox].orEmpty()
            row.folderRowSubtitle.text = if (labels.isEmpty()) {
                getString(R.string.custom_folder_subtitle)
            } else {
                getString(R.string.folder_label_subtitle, labels.joinToString(", "))
            }

            row.folderRowCount.backgroundTintList =
                ColorStateList.valueOf(folder.color.adjustAlpha(WASH_ALPHA))
            row.folderRowCount.text = (counts[mailbox] ?: 0).toString()

            row.folderRowUnread.backgroundTintList = ColorStateList.valueOf(folder.color)
            updateUnreadPill(row.folderRowUnread, unreadCounts[mailbox] ?: 0)

            row.root.setOnClickListener { openMailbox(mailbox) }
            row.root.setOnLongClickListener {
                showFolderOptions(folder, mailbox)
                true
            }
            container.addView(row.root)

            byParent[folder.id].orEmpty()
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                .forEach { child -> addFolderSubtree(child, depth + 1) }
        }

        order.forEach { entry ->
            systemHolders[entry]?.let { container.addView(it) }
                ?: Mailbox.folderIdOf(entry)?.let { folderId ->
                    byId[folderId]?.takeIf { it.parentId == null }?.let { folder ->
                        addFolderSubtree(folder, 0)
                    }
                }
        }
        // Orphans (parent gone) and anything the stored order missed
        // still show, at the end of the section, rather than vanish.
        folders.sortedWith(compareBy({ it.sortOrder }, { it.id })).forEach { folder ->
            if (folder.id !in visited && folder.parentId == null) {
                addFolderSubtree(folder, 0)
            }
        }
        updateTextColors(container)
    }

    private fun showFolderOptions(folder: Folder, mailbox: String) {
        val order = currentSectionOrder
        val index = order.indexOf(mailbox)
        val items = arrayListOf(
            RadioItem(
                0,
                getString(if (mailbox in config.mutedMailboxes) R.string.unsilence_folder else R.string.silence_folder)
            ),
            RadioItem(1, getString(R.string.rename_folder)),
            RadioItem(2, getString(R.string.delete_folder))
        )
        if (index > 0) items += RadioItem(3, getString(R.string.folder_move_up))
        if (index != -1 && index < order.lastIndex) items += RadioItem(4, getString(R.string.folder_move_down))
        RadioGroupDialog(this, items) { selected ->
            when (selected as Int) {
                0 -> {
                    toggleFolderMute(mailbox)
                    refreshFolderSection()
                }

                1 -> RenameFolderDialog(this, folder) {
                    refreshFolderSection()
                }

                2 -> ConfirmationDialog(
                    this,
                    getString(R.string.delete_folder_confirmation, folder.name)
                ) {
                    ensureBackgroundThread {
                        FolderManager.deleteFolder(this@FolderViewActivity, folder)
                        runOnUiThread {
                            refreshFolderSection()
                        }
                    }
                }

                3 -> moveSectionEntry(mailbox, up = true)
                4 -> moveSectionEntry(mailbox, up = false)
            }
        }
    }

    private fun loadCounts() {
        ensureBackgroundThread {
            val counts = Mailbox.ALL.associateWith { mailbox ->
                try {
                    conversationsDB.getCountByMailbox(mailbox)
                } catch (e: Exception) {
                    0
                }
            }

            // Unread pills only where unread means something: Spam is
            // stored already-read by design, and the Locker row shows no
            // activity detail — its count is all it ever reveals.
            val unreadCounts = listOf(Mailbox.CONTACTS, Mailbox.AUTH, Mailbox.QUARANTINE)
                .associateWith { mailbox ->
                    try {
                        conversationsDB.getUnreadCountByMailbox(mailbox)
                    } catch (e: Exception) {
                        0
                    }
                }

            runOnUiThread {
                binding.mailboxContactsCount.text = counts[Mailbox.CONTACTS].toString()
                binding.mailboxAuthCount.text = counts[Mailbox.AUTH].toString()
                binding.mailboxQuarantineCount.text = counts[Mailbox.QUARANTINE].toString()
                binding.mailboxSpamCount.text = counts[Mailbox.SPAM].toString()
                binding.mailboxLockerCount.text = counts[Mailbox.LOCKER].toString()

                updateUnreadPill(binding.mailboxContactsUnread, unreadCounts[Mailbox.CONTACTS] ?: 0)
                updateUnreadPill(binding.mailboxAuthUnread, unreadCounts[Mailbox.AUTH] ?: 0)
                updateUnreadPill(binding.mailboxQuarantineUnread, unreadCounts[Mailbox.QUARANTINE] ?: 0)
            }
        }
    }

    private fun updateUnreadPill(view: MyTextView, unreadCount: Int) {
        view.beVisibleIf(unreadCount > 0)
        if (unreadCount > 0) {
            view.text = unreadCount.toString()
            view.contentDescription = getString(R.string.unread_badge, unreadCount)
        }
    }

    private fun openMailbox(mailbox: String) {
        Intent(this, MainActivity::class.java).apply {
            putExtra(MAILBOX_FILTER, mailbox)
            startActivity(this)
        }
    }

    private fun exportRules(uri: Uri) {
        toast(org.fossify.commons.R.string.exporting)
        ensureBackgroundThread {
            val ruleSet = RuleSetStore.buildRuleSet(this)
            val outputStream = try {
                contentResolver.openOutputStream(uri)
            } catch (e: Exception) {
                null
            }

            FilterRulesExporter.exportRuleSet(ruleSet, outputStream) { result ->
                toast(
                    when (result) {
                        ExportResult.EXPORT_OK -> org.fossify.commons.R.string.exporting_successful
                        else -> org.fossify.commons.R.string.unknown_error_occurred
                    }
                )
            }
        }
    }

    private fun importRules(uri: Uri) {
        toast(org.fossify.commons.R.string.importing)
        ensureBackgroundThread {
            val ruleSet = try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    FilterRulesImporter.parseRuleSet(inputStream)
                }
            } catch (e: Exception) {
                null
            }

            if (ruleSet == null) {
                toast(org.fossify.commons.R.string.invalid_file_format)
                return@ensureBackgroundThread
            }

            runOnUiThread {
                ConfirmationDialog(
                    activity = this,
                    message = "",
                    messageId = R.string.import_filter_rules_confirmation,
                    positive = org.fossify.commons.R.string.yes,
                    negative = org.fossify.commons.R.string.no
                ) {
                    ensureBackgroundThread {
                        RuleSetStore.replaceAll(this, ruleSet)
                        toast(org.fossify.commons.R.string.importing_successful)
                    }
                }
            }
        }
    }

    private fun unlockLockerThenOpen() {
        // The gate is the user's choice: device unlock, or the app-only
        // code. Either way, opening the Locker runs it first.
        LockerGate.authenticate(this) {
            openMailbox(Mailbox.LOCKER)
        }
    }

    private companion object {
        /** Alpha of a folder colour in its count pill's wash (the rev6 pills use 0x26). */
        const val WASH_ALPHA = 0.15f
    }
}
