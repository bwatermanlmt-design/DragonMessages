package org.fossify.messages.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.models.RadioItem
import org.fossify.commons.views.MyTextView
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityFolderViewBinding
import org.fossify.messages.databinding.ItemFolderRowBinding
import org.fossify.messages.dialogs.CreateFolderDialog
import org.fossify.messages.dialogs.RenameFolderDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.foldersDB
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.helpers.MAILBOX_FILTER
import org.fossify.messages.models.Folder
import org.fossify.messages.models.Mailbox

/**
 * The home screen: the five mailboxes with their conversation counts, like
 * an email client's folder list. Tapping a mailbox opens its conversations
 * in MainActivity. The Locker row is gated: a biometric/device-credential
 * prompt must succeed before any locker content is shown.
 */
class FolderViewActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityFolderViewBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.folderNestedScrollview))

        binding.mailboxContactsHolder.setOnClickListener { openMailbox(Mailbox.CONTACTS) }
        binding.mailboxAuthHolder.setOnClickListener { openMailbox(Mailbox.AUTH) }
        binding.mailboxQuarantineHolder.setOnClickListener { openMailbox(Mailbox.QUARANTINE) }
        binding.mailboxSpamHolder.setOnClickListener { openMailbox(Mailbox.SPAM) }
        binding.mailboxLockerHolder.setOnClickListener { unlockLockerThenOpen() }

        // Long-press a folder to silence (or un-silence) the whole category:
        // Ben's hand-set mute, notifications only — filing never changes.
        binding.mailboxContactsHolder.setOnLongClickListener { toggleFolderMute(Mailbox.CONTACTS) }
        binding.mailboxAuthHolder.setOnLongClickListener { toggleFolderMute(Mailbox.AUTH) }
        binding.mailboxQuarantineHolder.setOnLongClickListener { toggleFolderMute(Mailbox.QUARANTINE) }
        binding.mailboxSpamHolder.setOnLongClickListener { toggleFolderMute(Mailbox.SPAM) }
        binding.mailboxLockerHolder.setOnLongClickListener { toggleFolderMute(Mailbox.LOCKER) }

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
     * The same three folder tools as visible rows in the list itself
     * (revision 8): the overflow menu entries above stay, but the
     * entrances no longer depend on opening a menu — a fresh install
     * shows no custom-folder rows, and these rows are what tells Ben
     * the features exist.
     */
    private fun setupActionRows() {
        binding.actionNewFolderHolder.setOnClickListener { showCreateFolderDialog() }
        binding.actionManageFoldersHolder.setOnClickListener { openFolderManager() }
        binding.actionMacrosHolder.setOnClickListener { openMacros() }
    }

    private fun showCreateFolderDialog() {
        CreateFolderDialog(this) {
            refreshCustomFolders()
        }
    }

    private fun openFolderManager() {
        startActivity(Intent(this, ManageFoldersActivity::class.java))
    }

    private fun openMacros() {
        startActivity(Intent(this, MacrosActivity::class.java))
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
        refreshCustomFolders()
    }

    /**
     * Ben's own folders (revision 7), after the system five in creation
     * order, in the same row dress: colour chip from the folder's stored
     * colour, name (with the silenced suffix when muted), count pill, and
     * an unread pill like the other contact-ish folders. A long press
     * offers the silence toggle alongside Rename and Delete.
     */
    private fun refreshCustomFolders() {
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
                showCustomFolders(folders, labelsByMailbox, counts, unreadCounts)
            }
        }
    }

    private fun showCustomFolders(
        folders: List<Folder>,
        labelsByMailbox: Map<String, List<String>>,
        counts: Map<String, Int>,
        unreadCounts: Map<String, Int>
    ) {
        val container = binding.customFoldersContainer
        container.removeAllViews()
        val muted = config.mutedMailboxes

        folders.forEach { folder ->
            val mailbox = Mailbox.customMailboxId(folder.id)
            val row = ItemFolderRowBinding.inflate(layoutInflater, container, false)

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
        }
        updateTextColors(container)
    }

    private fun showFolderOptions(folder: Folder, mailbox: String) {
        val items = arrayListOf(
            RadioItem(
                0,
                getString(if (mailbox in config.mutedMailboxes) R.string.unsilence_folder else R.string.silence_folder)
            ),
            RadioItem(1, getString(R.string.rename_folder)),
            RadioItem(2, getString(R.string.delete_folder))
        )
        RadioGroupDialog(this, items) { selected ->
            when (selected as Int) {
                0 -> {
                    toggleFolderMute(mailbox)
                    refreshCustomFolders()
                }

                1 -> RenameFolderDialog(this, folder) {
                    refreshCustomFolders()
                }

                2 -> ConfirmationDialog(
                    this,
                    getString(R.string.delete_folder_confirmation, folder.name)
                ) {
                    ensureBackgroundThread {
                        FolderManager.deleteFolder(this@FolderViewActivity, folder)
                        runOnUiThread {
                            refreshCustomFolders()
                        }
                    }
                }
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

    private fun unlockLockerThenOpen() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    openMailbox(Mailbox.LOCKER)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // Cancelling just returns to the folder list; real errors are worth a word.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        toast(errString.toString())
                    }
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_locker))
            .setSubtitle(getString(R.string.locker_subtitle))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    // Combining biometric and device credential in one call is
                    // an API 30+ ability; older versions take the legacy flag.
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                    setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                }
            }
            .build()

        prompt.authenticate(promptInfo)
    }

    private companion object {
        /** Alpha of a folder colour in its count pill's wash (the rev6 pills use 0x26). */
        const val WASH_ALPHA = 0.15f
    }
}
