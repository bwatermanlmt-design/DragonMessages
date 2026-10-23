package org.fossify.messages.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.DialogFolderNameBinding
import org.fossify.messages.helpers.FilterEvaluator
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.models.Folder

/**
 * Create one of Ben's own folders: a name, a colour assigned from the
 * palette, and — optionally, in the same breath — one address-book label
 * whose contacts will file here. Used by the folder view, the folder
 * manager, and Send to folder's "New folder…" entry.
 */
class CreateFolderDialog(
    val activity: BaseSimpleActivity,
    val callback: (Folder) -> Unit
) {
    private var selectedLabel: String? = null

    init {
        val binding = DialogFolderNameBinding.inflate(activity.layoutInflater)

        binding.folderLabelValue.setOnClickListener {
            pickLabel(binding)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.new_folder_title) { alertDialog ->
                    alertDialog.showKeyboard(binding.folderNameEdittext)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = binding.folderNameEdittext.value.trim()
                        if (name.isEmpty()) {
                            activity.toast(R.string.folder_name_hint)
                            return@setOnClickListener
                        }

                        alertDialog.dismiss()
                        ensureBackgroundThread {
                            val folder = FolderManager.createFolder(activity, name, selectedLabel)
                            activity.runOnUiThread {
                                callback(folder)
                            }
                        }
                    }
                }
            }
    }

    private fun pickLabel(binding: DialogFolderNameBinding) {
        ensureBackgroundThread {
            val titles = FilterEvaluator.contactGroupTitles(activity)
            activity.runOnUiThread {
                if (titles.isEmpty()) {
                    activity.toast(R.string.no_label)
                    return@runOnUiThread
                }

                val items = arrayListOf(RadioItem(0, activity.getString(R.string.no_label)))
                titles.forEachIndexed { index, title ->
                    items += RadioItem(index + 1, title)
                }
                val checked = titles.indexOf(selectedLabel) + 1
                RadioGroupDialog(activity, items, checked) { selected ->
                    val id = selected as Int
                    selectedLabel = if (id == 0) null else titles[id - 1]
                    binding.folderLabelValue.text = selectedLabel ?: activity.getString(R.string.no_label)
                }
            }
        }
    }
}
