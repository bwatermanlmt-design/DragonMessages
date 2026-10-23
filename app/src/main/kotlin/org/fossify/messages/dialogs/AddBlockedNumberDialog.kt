package org.fossify.messages.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.databinding.DialogAddBlockedNumberBinding
import org.fossify.messages.helpers.FilterEvaluator

/**
 * Add a number to the permanent blocked record by hand: the same block the
 * conversation-level Block actions apply (app record + system block list).
 */
class AddBlockedNumberDialog(val activity: BaseSimpleActivity, val callback: () -> Unit) {
    init {
        val binding = DialogAddBlockedNumberBinding.inflate(activity.layoutInflater)

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    alertDialog.showKeyboard(binding.addBlockedNumberEdittext)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newBlockedNumber = binding.addBlockedNumberEdittext.value
                        if (newBlockedNumber.isNotEmpty()) {
                            ensureBackgroundThread {
                                FilterEvaluator.blockNumber(activity, newBlockedNumber)
                                callback()
                            }
                        }

                        alertDialog.dismiss()
                    }
                }
            }
    }
}
