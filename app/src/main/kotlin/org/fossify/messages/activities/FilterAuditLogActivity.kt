package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.adapters.FilterAuditAdapter
import org.fossify.messages.databinding.ActivityFilterAuditLogBinding
import org.fossify.messages.extensions.filterAuditDB

/**
 * Read-only view of the filter audit log: every verdict the rule engine has
 * made, newest first, so "why did this message land here?" is answerable.
 */
class FilterAuditLogActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityFilterAuditLogBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.auditList))
        setupMaterialScrollListener(
            scrollingView = binding.auditList,
            topAppBar = binding.auditAppbar
        )

        loadEntries()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.auditAppbar, NavigationIcon.Arrow)
        loadEntries()
    }

    private fun loadEntries() {
        ensureBackgroundThread {
            val entries = try {
                filterAuditDB.getRecent(MAX_ENTRIES)
            } catch (e: Exception) {
                emptyList()
            }

            runOnUiThread {
                binding.auditList.adapter = FilterAuditAdapter(entries)
                binding.noEntriesPlaceholder.beVisibleIf(entries.isEmpty())
                binding.auditList.beVisibleIf(entries.isNotEmpty())
            }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
