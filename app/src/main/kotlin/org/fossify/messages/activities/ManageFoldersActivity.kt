package org.fossify.messages.activities

import android.content.res.ColorStateList
import android.os.Bundle
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityManageFoldersBinding
import org.fossify.messages.databinding.ItemLabelMappingBinding
import org.fossify.messages.databinding.ItemManageFolderBinding
import org.fossify.messages.dialogs.CreateFolderDialog
import org.fossify.messages.dialogs.RenameFolderDialog
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.foldersDB
import org.fossify.messages.helpers.FilterEvaluator
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.models.Folder
import org.fossify.messages.models.LabelMapping
import org.fossify.messages.models.Mailbox

/**
 * Folder management (revision 7), kept plain: Ben's folders with their
 * counts (tap one to rename or delete it) and the label mappings in the
 * order the filters consult them (tap one to remove it), with creation
 * for both. Deleting a folder re-homes its threads — nothing is deleted
 * but the folder itself.
 */
class ManageFoldersActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityManageFoldersBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.manageFoldersScrollview))

        binding.newFolderButton.setOnClickListener {
            CreateFolderDialog(this) {
                refreshContent()
            }
        }
        binding.addMappingButton.setOnClickListener {
            addLabelMapping()
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.manageFoldersAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.manageFoldersCoordinator)
        refreshContent()
    }

    private fun refreshContent() {
        ensureBackgroundThread {
            val folders = FolderManager.allFolders(this)
            val counts = HashMap<String, Int>()
            folders.forEach { folder ->
                counts[folder.id] = try {
                    conversationsDB.getCountByMailbox(Mailbox.customMailboxId(folder.id))
                } catch (e: Exception) {
                    0
                }
            }
            val mappings = try {
                foldersDB.getAllLabelMappings()
            } catch (e: Exception) {
                emptyList()
            }

            runOnUiThread {
                showFolders(folders, counts)
                showMappings(mappings, folders)
            }
        }
    }

    private fun showFolders(folders: List<Folder>, counts: Map<String, Int>) {
        binding.noFoldersPlaceholder.beVisibleIf(folders.isEmpty())
        val container = binding.foldersContainer
        container.removeAllViews()

        folders.forEach { folder ->
            val row = ItemManageFolderBinding.inflate(layoutInflater, container, false)
            row.manageFolderDot.backgroundTintList = ColorStateList.valueOf(folder.color)
            row.manageFolderName.text = folder.name
            row.manageFolderCount.text = (counts[folder.id] ?: 0).toString()
            row.root.setOnClickListener {
                showFolderOptions(folder)
            }
            container.addView(row.root)
        }
        updateTextColors(container)
    }

    private fun showMappings(mappings: List<LabelMapping>, folders: List<Folder>) {
        binding.noMappingsPlaceholder.beVisibleIf(mappings.isEmpty())
        val container = binding.mappingsContainer
        container.removeAllViews()

        mappings.forEach { mapping ->
            val row = ItemLabelMappingBinding.inflate(layoutInflater, container, false)
            val targetName = folders.firstOrNull { Mailbox.customMailboxId(it.id) == mapping.mailbox }?.name
                ?: FolderManager.displayName(this, mapping.mailbox)
            row.mappingText.text = getString(R.string.mapping_goes_to, mapping.label, targetName)
            row.root.setOnClickListener {
                ConfirmationDialog(this, getString(R.string.remove_mapping) + "?") {
                    ensureBackgroundThread {
                        foldersDB.deleteLabelMapping(mapping)
                        refreshContent()
                    }
                }
            }
            container.addView(row.root)
        }
        updateTextColors(container)
    }

    private fun showFolderOptions(folder: Folder) {
        val items = arrayListOf(
            RadioItem(0, getString(R.string.rename_folder)),
            RadioItem(1, getString(R.string.delete_folder))
        )
        RadioGroupDialog(this, items) { selected ->
            when (selected as Int) {
                0 -> RenameFolderDialog(this, folder) {
                    refreshContent()
                }

                1 -> ConfirmationDialog(
                    this,
                    getString(R.string.delete_folder_confirmation, folder.name)
                ) {
                    ensureBackgroundThread {
                        FolderManager.deleteFolder(this@ManageFoldersActivity, folder)
                        refreshContent()
                    }
                }
            }
        }
    }

    /** Add a mapping: pick an address-book label, then the folder it files to. */
    private fun addLabelMapping() {
        ensureBackgroundThread {
            val titles = FilterEvaluator.contactGroupTitles(this)
            val folders = FolderManager.allFolders(this)
            runOnUiThread {
                if (folders.isEmpty()) {
                    toast(R.string.no_folders_yet)
                    return@runOnUiThread
                }
                if (titles.isEmpty()) {
                    toast(R.string.no_label)
                    return@runOnUiThread
                }

                val labelItems = titles.mapIndexed { index, title -> RadioItem(index, title) }
                RadioGroupDialog(this, ArrayList(labelItems), -1, R.string.pick_a_label) { pickedLabel ->
                    val label = titles[pickedLabel as Int]
                    val folderItems = folders.mapIndexed { index, folder -> RadioItem(index, folder.name) }
                    RadioGroupDialog(this, ArrayList(folderItems), -1, R.string.pick_a_folder) { pickedFolder ->
                        val folder = folders[pickedFolder as Int]
                        ensureBackgroundThread {
                            FolderManager.addLabelMapping(
                                this@ManageFoldersActivity,
                                label,
                                Mailbox.customMailboxId(folder.id)
                            )
                            refreshContent()
                        }
                    }
                }
            }
        }
    }
}
