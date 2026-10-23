package org.fossify.messages.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.views.MyTextView
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityFolderViewBinding
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.helpers.MAILBOX_FILTER
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
}
