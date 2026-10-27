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
import org.fossify.messages.filtering.RuleEngine
import org.fossify.messages.filtering.SenderClassifier
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.helpers.MACRO_ID
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * The rule editor (revision 9): a friendly face over one rule row. One
 * rules concept now — the old filters and macros are the same list, so
 * this editor edits any rule: a name, a handful of optional criteria,
 * how they combine (all / any), keywords (one per line, optionally
 * patterns), a destination — never the Locker, which is hand-filled
 * only — and, for rules carried over from the macro era, the automatic
 * toggle. New rules sit at priority [DEFAULT_RULE_PRIORITY]: after
 * Ben's converted blocked keywords, before the starter pack's content
 * rules — his deliberate rules outrank the fingerprints — and ties break
 * by creation order, like rules everywhere else in the engine.
 *
 * Fields this editor does not show (exact number, number pattern, sender
 * tag, tag assignment, priority, enabled) are preserved untouched when an
 * existing rule is saved — editing here never zeroes them.
 *
 * Class and layout names still say "Macro" — that is code history, not
 * screen text: no user-visible string on this screen says "macro".
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
            binding.ruleMatchAll.isChecked = true
            binding.macroAutomaticCheckbox.beVisibleIf(false)
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
                binding.macroKeywordEdittext.setText(RuleEngine.effectiveKeywords(macro).joinToString("\n"))
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
                if (macro.conditionMode == RuleEngine.CONDITION_MODE_ANY) {
                    binding.ruleMatchAny.isChecked = true
                } else {
                    binding.ruleMatchAll.isChecked = true
                }
                binding.ruleRegexCheckbox.isChecked = macro.keywordRegex
                // The automatic toggle only means something for rules
                // carried over from the macro era; plain rules always fire
                // on arrival, so the checkbox hides for them.
                binding.macroAutomaticCheckbox.beVisibleIf(macro.isMacro)
                binding.macroAutomaticCheckbox.isChecked = macro.macroAutomatic
                targetMailbox = macro.mailbox
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
        val keywords = binding.macroKeywordEdittext.value
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
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
        val conditionMode = when (binding.ruleMatchGroup.checkedRadioButtonId) {
            R.id.rule_match_any -> RuleEngine.CONDITION_MODE_ANY
            else -> RuleEngine.CONDITION_MODE_ALL
        }
        val keywordRegex = binding.ruleRegexCheckbox.isChecked

        if (senderContains == null && keywords.isEmpty() && senderClass == null &&
            senderInContacts == null && containsLink == null
        ) {
            toast(R.string.macro_needs_a_criterion)
            return
        }

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

            val automatic = if (existing?.isMacro == true) {
                binding.macroAutomaticCheckbox.isChecked
            } else {
                existing?.macroAutomatic ?: false
            }
            val rule = (existing ?: FilterRule(name = name, mailbox = targetMailbox)).copy(
                name = name,
                enabled = existing?.enabled ?: true,
                priority = existing?.priority ?: DEFAULT_RULE_PRIORITY,
                senderContains = senderContains,
                senderInContacts = senderInContacts,
                keyword = keywords.firstOrNull(),
                keywordsJson = if (keywords.isNotEmpty()) RuleEngine.keywordsToJson(keywords) else null,
                conditionMode = conditionMode,
                keywordRegex = keywordRegex,
                containsLink = containsLink,
                senderClass = senderClass,
                mailbox = targetMailbox,
                isMacro = existing?.isMacro ?: false,
                macroAutomatic = automatic
            )
            filterRulesDB.insertOrUpdate(rule)
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
        /** Where user-created rules sit in the rules order (see the class doc). */
        const val DEFAULT_RULE_PRIORITY = 15

        private const val NEW_FOLDER_ID = 1000
    }
}
