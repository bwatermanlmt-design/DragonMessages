package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityMacroEditorBinding
import org.fossify.messages.dialogs.CreateFolderDialog
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.SenderClassifier
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.helpers.MACRO_ID
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * The macro editor (revision 7): a friendly face over one rule row. A
 * macro is a name, a handful of optional criteria (every set criterion
 * must match), a target folder — never the Locker, which is hand-filled
 * only — and an automatic toggle. Macros file at priority
 * [MACRO_PRIORITY]: after Ben's converted blocked keywords, before the
 * starter pack's content rules — his deliberate searches outrank the
 * fingerprints — and ties among macros break by creation order, like
 * rules everywhere else in the engine.
 */
class MacroEditorActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityMacroEditorBinding::inflate)

    private var macroId: Long = 0
    private var targetMailbox: String = Mailbox.CONTACTS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        macroId = intent.getLongExtra(MACRO_ID, 0)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.macroEditorScrollview))

        binding.macroTargetValue.setOnClickListener {
            pickTargetFolder()
        }
        binding.macroSaveButton.setOnClickListener {
            saveMacro()
        }
        binding.macroDeleteButton.setOnClickListener {
            askDeleteMacro()
        }

        if (macroId != 0L) {
            binding.macroEditorToolbar.title = getString(R.string.edit_macro)
            binding.macroDeleteButton.beVisibleIf(true)
            loadMacro()
        } else {
            binding.macroClassAny.isChecked = true
            binding.macroContactsEither.isChecked = true
            binding.macroLinkEither.isChecked = true
            updateTargetLabel()
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.macroEditorAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.macroEditorCoordinator)
    }

    private fun loadMacro() {
        ensureBackgroundThread {
            val macro = try {
                filterRulesDB.getById(macroId)
            } catch (e: Exception) {
                null
            } ?: return@ensureBackgroundThread

            runOnUiThread {
                binding.macroNameEdittext.setText(macro.name)
                binding.macroSenderContainsEdittext.setText(macro.senderContains ?: "")
                binding.macroKeywordEdittext.setText(macro.keyword ?: "")
                when (macro.senderClass) {
                    SenderClassifier.LONG_NUMBER -> binding.macroClassLong.isChecked = true
                    SenderClassifier.SHORT_CODE -> binding.macroClassShort.isChecked = true
                    SenderClassifier.ALPHANUMERIC -> binding.macroClassAlpha.isChecked = true
                    else -> binding.macroClassAny.isChecked = true
                }
                when (macro.senderInContacts) {
                    true -> binding.macroContactsYes.isChecked = true
                    false -> binding.macroContactsNo.isChecked = true
                    null -> binding.macroContactsEither.isChecked = true
                }
                when (macro.containsLink) {
                    true -> binding.macroLinkYes.isChecked = true
                    else -> binding.macroLinkEither.isChecked = true
                }
                targetMailbox = macro.mailbox
                binding.macroAutomaticCheckbox.isChecked = macro.macroAutomatic
                updateTargetLabel()
            }
        }
    }

    private fun updateTargetLabel() {
        binding.macroTargetValue.text = FolderManager.displayName(this, targetMailbox)
    }

    /** System folders (never the Locker) + Ben's folders + "New folder…". */
    private fun pickTargetFolder() {
        ensureBackgroundThread {
            val folders = FolderManager.allFolders(this@MacroEditorActivity)
            val mailboxes = ArrayList<String>()
            mailboxes += Mailbox.CONTACTS
            mailboxes += Mailbox.AUTH
            mailboxes += Mailbox.QUARANTINE
            mailboxes += Mailbox.SPAM
            folders.forEach { mailboxes += Mailbox.customMailboxId(it.id) }

            val items = ArrayList<RadioItem>()
            mailboxes.forEachIndexed { index, mailbox ->
                items += RadioItem(index, FolderManager.displayName(this@MacroEditorActivity, mailbox))
            }
            items += RadioItem(NEW_FOLDER_ID, getString(R.string.new_folder) + "…")
            val checkedId = mailboxes.indexOf(targetMailbox)

            runOnUiThread {
                RadioGroupDialog(this@MacroEditorActivity, items, checkedId, R.string.macro_target_folder) { selected ->
                    val id = selected as Int
                    if (id == NEW_FOLDER_ID) {
                        CreateFolderDialog(this@MacroEditorActivity) { folder ->
                            targetMailbox = Mailbox.customMailboxId(folder.id)
                            updateTargetLabel()
                        }
                    } else {
                        targetMailbox = mailboxes[id]
                        updateTargetLabel()
                    }
                }
            }
        }
    }

    private fun saveMacro() {
        val name = binding.macroNameEdittext.value.trim()
        if (name.isEmpty()) {
            toast(R.string.macro_needs_a_name)
            return
        }

        val senderContains = binding.macroSenderContainsEdittext.value.trim().ifEmpty { null }
        val keyword = binding.macroKeywordEdittext.value.trim().ifEmpty { null }
        val senderClass = when (binding.macroSenderClassGroup.checkedRadioButtonId) {
            R.id.macro_class_long -> SenderClassifier.LONG_NUMBER
            R.id.macro_class_short -> SenderClassifier.SHORT_CODE
            R.id.macro_class_alpha -> SenderClassifier.ALPHANUMERIC
            else -> null
        }
        val senderInContacts = when (binding.macroInContactsGroup.checkedRadioButtonId) {
            R.id.macro_contacts_yes -> true
            R.id.macro_contacts_no -> false
            else -> null
        }
        val containsLink = when (binding.macroLinkGroup.checkedRadioButtonId) {
            R.id.macro_link_yes -> true
            else -> null
        }

        if (senderContains == null && keyword == null && senderClass == null &&
            senderInContacts == null && containsLink == null
        ) {
            toast(R.string.macro_needs_a_criterion)
            return
        }

        val automatic = binding.macroAutomaticCheckbox.isChecked
        ensureBackgroundThread {
            val existing = if (macroId != 0L) {
                try {
                    filterRulesDB.getById(macroId)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            val macro = (existing ?: FilterRule(name = name, mailbox = targetMailbox)).copy(
                name = name,
                enabled = true,
                priority = MACRO_PRIORITY,
                senderExact = null,
                senderPattern = null,
                senderContains = senderContains,
                senderInContacts = senderInContacts,
                senderTag = null,
                keyword = keyword,
                containsLink = containsLink,
                senderClass = senderClass,
                mailbox = targetMailbox,
                assignTag = null,
                isMacro = true,
                macroAutomatic = automatic
            )
            filterRulesDB.insertOrUpdate(macro)
            runOnUiThread {
                finish()
            }
        }
    }

    private fun askDeleteMacro() {
        val name = binding.macroNameEdittext.value.trim()
        ConfirmationDialog(this, getString(R.string.delete_macro_confirmation, name)) {
            ensureBackgroundThread {
                try {
                    filterRulesDB.getById(macroId)?.let { filterRulesDB.delete(it) }
                } catch (e: Exception) {
                    // Already gone; leaving is the whole point.
                }
                runOnUiThread {
                    finish()
                }
            }
        }
    }

    companion object {
        /** Where macros sit in the rules order (see the class doc). */
        const val MACRO_PRIORITY = 15

        private const val NEW_FOLDER_ID = 1000
    }
}
