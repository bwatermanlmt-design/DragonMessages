package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityMacrosBinding
import org.fossify.messages.databinding.ItemMacroBinding
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.SenderClassifier
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.helpers.MACRO_ID
import org.fossify.messages.models.FilterRule

/**
 * The macros list (revision 7): every macro with a plain-language
 * summary and a Run button. Tapping a row edits it; a long press offers
 * edit and delete. Running a macro is the checkbox pass in
 * [MacroRunActivity] — nothing files from here directly.
 */
class MacrosActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityMacrosBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.macrosScrollview))

        binding.newMacroButton.setOnClickListener {
            startActivity(Intent(this, MacroEditorActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.macrosAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.macrosCoordinator)
        refreshMacros()
    }

    private fun refreshMacros() {
        ensureBackgroundThread {
            val macros = try {
                filterRulesDB.getAllMacros()
            } catch (e: Exception) {
                emptyList()
            }

            runOnUiThread {
                showMacros(macros)
            }
        }
    }

    private fun showMacros(macros: List<FilterRule>) {
        binding.noMacrosPlaceholder.beVisibleIf(macros.isEmpty())
        val container = binding.macrosContainer
        container.removeAllViews()

        macros.forEach { macro ->
            val row = ItemMacroBinding.inflate(layoutInflater, container, false)
            row.macroName.text = macro.name
            row.macroSummary.text = summarize(macro)
            row.macroRun.setOnClickListener {
                Intent(this, MacroRunActivity::class.java).apply {
                    putExtra(MACRO_ID, macro.id)
                    startActivity(this)
                }
            }
            row.root.setOnClickListener {
                Intent(this, MacroEditorActivity::class.java).apply {
                    putExtra(MACRO_ID, macro.id)
                    startActivity(this)
                }
            }
            row.root.setOnLongClickListener {
                showMacroOptions(macro)
                true
            }
            container.addView(row.root)
        }
        updateTextColors(container)
    }

    private fun showMacroOptions(macro: FilterRule) {
        val items = arrayListOf(
            RadioItem(0, getString(R.string.edit_macro)),
            RadioItem(1, getString(R.string.delete_macro))
        )
        RadioGroupDialog(this, items) { selected ->
            when (selected as Int) {
                0 -> Intent(this, MacroEditorActivity::class.java).apply {
                    putExtra(MACRO_ID, macro.id)
                    startActivity(this)
                }

                1 -> ConfirmationDialog(
                    this,
                    getString(R.string.delete_macro_confirmation, macro.name)
                ) {
                    ensureBackgroundThread {
                        filterRulesDB.delete(macro)
                        refreshMacros()
                    }
                }
            }
        }
    }

    /** A one-line, plain-language reading of the macro's criteria and target. */
    private fun summarize(macro: FilterRule): String {
        val parts = ArrayList<String>()
        macro.senderContains?.let { parts += "sender: $it" }
        macro.keyword?.let { parts += "message: $it" }
        macro.senderClass?.let { senderClass ->
            parts += getString(
                when (senderClass) {
                    SenderClassifier.SHORT_CODE -> R.string.macro_short_code
                    SenderClassifier.ALPHANUMERIC -> R.string.macro_service_id
                    else -> R.string.macro_long_number
                }
            )
        }
        macro.senderInContacts?.let { inContacts ->
            parts += getString(if (inContacts) R.string.macro_yes else R.string.macro_no) +
                " — " + getString(R.string.macro_in_contacts).lowercase()
        }
        if (macro.containsLink == true) {
            parts += getString(R.string.macro_contains_link).lowercase()
        }

        var summary = parts.joinToString(", ") + " → " + FolderManager.displayName(this, macro.mailbox)
        if (macro.macroAutomatic) {
            summary += " · " + getString(R.string.macro_automatic_short)
        }
        return summary
    }
}
