package org.fossify.messages.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.ExportResult
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityMacrosBinding
import org.fossify.messages.databinding.ItemMacroBinding
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.RuleEngine
import org.fossify.messages.filtering.SenderClassifier
import org.fossify.messages.helpers.FILTER_RULES_EXPORT_EXTENSION
import org.fossify.messages.helpers.FilterRulesExporter
import org.fossify.messages.helpers.FilterRulesImporter
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.helpers.MACRO_ID
import org.fossify.messages.helpers.RuleSetStore
import org.fossify.messages.models.FilterRule

/**
 * The rules list (revision 9): ONE rules concept — the old filters and
 * macros are the same list now. Every rule with a plain-language summary
 * and a Run button. Tapping a row edits it; a long press offers edit and
 * delete. Running a rule is the checkbox pass in [MacroRunActivity] —
 * nothing files from here directly. Import/export of the whole rule set
 * lives here too (the Settings rows call the same store, so the two
 * entrances cannot diverge).
 *
 * Class and layout names still say "Macros" — that is code history, not
 * screen text: no user-visible string on this screen says "macro".
 */
class MacrosActivity : SimpleActivity() {

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

    private val binding by viewBinding(ActivityMacrosBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.macrosScrollview))

        binding.newMacroButton.setOnClickListener {
            startActivity(Intent(this, MacroEditorActivity::class.java))
        }
        binding.importRulesButton.setOnClickListener {
            getRulesContent.launch(arrayOf(messagesFileType))
        }
        binding.exportRulesButton.setOnClickListener {
            saveRulesDocument.launch("messages-filter-rules$FILTER_RULES_EXPORT_EXTENSION")
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.macrosAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.macrosCoordinator)
        refreshRules()
    }

    private fun refreshRules() {
        ensureBackgroundThread {
            val rules = try {
                filterRulesDB.getAll()
            } catch (e: Exception) {
                emptyList()
            }

            runOnUiThread {
                showRules(rules)
            }
        }
    }

    private fun showRules(rules: List<FilterRule>) {
        binding.noMacrosPlaceholder.beVisibleIf(rules.isEmpty())
        val container = binding.macrosContainer
        container.removeAllViews()

        rules.forEach { rule ->
            val row = ItemMacroBinding.inflate(layoutInflater, container, false)
            row.macroName.text = rule.name
            row.macroSummary.text = summarize(rule)
            row.macroRun.setOnClickListener {
                Intent(this, MacroRunActivity::class.java).apply {
                    putExtra(MACRO_ID, rule.id)
                    startActivity(this)
                }
            }
            row.root.setOnClickListener {
                Intent(this, MacroEditorActivity::class.java).apply {
                    putExtra(MACRO_ID, rule.id)
                    startActivity(this)
                }
            }
            row.root.setOnLongClickListener {
                showRuleOptions(rule)
                true
            }
            container.addView(row.root)
        }
        updateTextColors(container)
    }

    private fun showRuleOptions(rule: FilterRule) {
        val items = arrayListOf(
            RadioItem(0, getString(R.string.edit_macro)),
            RadioItem(1, getString(R.string.delete_macro))
        )
        RadioGroupDialog(this, items) { selected ->
            when (selected as Int) {
                0 -> Intent(this, MacroEditorActivity::class.java).apply {
                    putExtra(MACRO_ID, rule.id)
                    startActivity(this)
                }

                1 -> ConfirmationDialog(
                    this,
                    getString(R.string.delete_macro_confirmation, rule.name)
                ) {
                    ensureBackgroundThread {
                        filterRulesDB.delete(rule)
                        refreshRules()
                    }
                }
            }
        }
    }

    /** A one-line, plain-language reading of the rule's criteria and destination. */
    private fun summarize(rule: FilterRule): String {
        val parts = ArrayList<String>()
        rule.senderContains?.let { parts += "sender: $it" }
        val keywords = RuleEngine.effectiveKeywords(rule)
        if (keywords.isNotEmpty()) {
            parts += "message: " + keywords.joinToString(", ") +
                if (rule.keywordRegex) " (pattern)" else ""
        }
        rule.senderClass?.let { senderClass ->
            parts += getString(
                when (senderClass) {
                    SenderClassifier.SHORT_CODE -> R.string.macro_short_code
                    SenderClassifier.ALPHANUMERIC -> R.string.macro_service_id
                    else -> R.string.macro_long_number
                }
            )
        }
        rule.senderInContacts?.let { inContacts ->
            parts += getString(if (inContacts) R.string.macro_yes else R.string.macro_no) +
                " — " + getString(R.string.macro_in_contacts).lowercase()
        }
        if (rule.containsLink == true) {
            parts += getString(R.string.macro_contains_link).lowercase()
        }
        if (rule.conditionMode == RuleEngine.CONDITION_MODE_ANY) {
            parts += getString(R.string.rule_match_any).lowercase()
        }

        var summary = parts.joinToString(", ") + " → " + FolderManager.displayName(this, rule.mailbox)
        if (rule.macroAutomatic) {
            summary += " · " + getString(R.string.macro_automatic_short)
        }
        return summary
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
                        runOnUiThread { refreshRules() }
                    }
                }
            }
        }
    }
}
