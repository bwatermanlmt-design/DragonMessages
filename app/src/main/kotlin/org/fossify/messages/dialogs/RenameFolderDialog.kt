package org.fossify.messages.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.DialogFolderNameBinding
import org.fossify.messages.helpers.FolderManager
import org.fossify.messages.models.Folder

/** Rename one of Ben's folders; the folder's id (and its filings) never change. */
class RenameFolderDialog(
    val activity: BaseSimpleActivity,
    val folder: Folder,
    val callback: () -> Unit
) {
    init {
        val binding = DialogFolderNameBinding.inflate(activity.layoutInflater)
        binding.folderNameEdittext.setText(folder.name)
        binding.folderLabelTitle.beGone()
        binding.folderLabelValue.beGone()

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.rename_folder) { alertDialog ->
                    alertDialog.showKeyboard(binding.folderNameEdittext)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = binding.folderNameEdittext.value.trim()
                        if (name.isNotEmpty() && name != folder.name) {
                            ensureBackgroundThread {
                                FolderManager.renameFolder(activity, folder, name)
                                activity.runOnUiThread {
                                    callback()
                                }
                            }
                        }

                        alertDialog.dismiss()
                    }
                }
            }
    }
}
