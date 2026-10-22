package org.fossify.messages.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.messages.databinding.ItemFilterAuditEntryBinding
import org.fossify.messages.models.FilterAuditEntry

/** Read-only list of filter verdicts: sender, mailbox, rule, channel, time. */
class FilterAuditAdapter(
    private val entries: List<FilterAuditEntry>
) : RecyclerView.Adapter<FilterAuditAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFilterAuditEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount() = entries.size

    class ViewHolder(
        private val binding: ItemFilterAuditEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FilterAuditEntry) {
            val context = binding.root.context
            val textColor = context.getProperTextColor()
            binding.auditEntryTitle.apply {
                text = "${entry.sender} → ${entry.mailbox}"
                setTextColor(textColor)
            }
            binding.auditEntrySubtitle.apply {
                val time = entry.timestamp.formatDateOrTime(
                    context = context,
                    hideTimeOnOtherDays = true,
                    showCurrentYear = false
                )
                text = "${entry.ruleName} • ${entry.channel.uppercase()} • $time"
                setTextColor(textColor)
            }
        }
    }
}
