package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyTextView
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityTidyRulesBinding
import org.fossify.messages.databinding.ItemTidyProposalBinding
import org.fossify.messages.extensions.filterAuditDB
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.RuleAdjuster
import org.fossify.messages.helpers.FolderManager

/**
 * Tidy rules (the rule adjuster): rules that do the same job, proposed
 * for merging — the same propose-and-pick contract as the seeker. Only
 * ticked proposals are applied; unticked ones are dropped, and the next
 * run proposes them again if they still qualify. Nothing merges itself.
 *
 * A proposal is two adjacent rules (same folder, same tag, same firing
 * behaviour) whose conditions differ in exactly one slot that OR-combines
 * exactly, or one rule subsuming the other. Applying re-validates each
 * ticked proposal against the live rule list: a proposal whose source
 * rules changed since the screen opened is skipped, not applied.
 */
class TidyRulesActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityTidyRulesBinding::inflate)

    private var proposals: List<RuleAdjuster.MergeProposal> = emptyList()
    private val selected = LinkedHashSet<RuleAdjuster.MergeProposal>()
    private var firingCounts: Map<String, Int> = emptyMap()
    private var nameCounts: Map<String, Int> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.tidyRulesScrollview))

        binding.applyMergesButton.setOnClickListener { applySelectedMerges() }
        loadProposals()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.tidyRulesAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.tidyRulesCoordinator)
    }

    private fun loadProposals() {
        ensureBackgroundThread {
            val rules = try {
                filterRulesDB.getAll()
            } catch (e: Exception) {
                emptyList()
            }
            firingCounts = try {
                filterAuditDB.countByRuleName().associate { it.ruleName to it.firingCount }
            } catch (e: Exception) {
                emptyMap()
            }
            nameCounts = rules.groupingBy { it.name }.eachCount()
            proposals = RuleAdjuster.findMerges(rules)

            runOnUiThread {
                showProposals()
            }
        }
    }

    private fun showProposals() {
        binding.tidyLoading.beVisibleIf(false)
        binding.tidyEmpty.beVisibleIf(proposals.isEmpty())
        if (proposals.isEmpty()) {
            return
        }

        val container = binding.tidyContainer
        container.removeAllViews()

        var lastGroup: RuleAdjuster.Kind? = null
        for (proposal in proposals) {
            val group = proposal.kind
            if (group != lastGroup) {
                container.addView(groupHeader(group))
                lastGroup = group
            }
            container.addView(proposalRow(proposal))
        }
        updateTextColors(container)
        updateApplyButton()
    }

    private fun groupHeader(kind: RuleAdjuster.Kind) = MyTextView(this).apply {
        setText(
            when (kind) {
                RuleAdjuster.Kind.KEYWORD -> R.string.tidy_group_keyword
                RuleAdjuster.Kind.SENDER, RuleAdjuster.Kind.SENDER_CONTAINS -> R.string.tidy_group_sender
                RuleAdjuster.Kind.BOOLEAN -> R.string.tidy_group_boolean
                RuleAdjuster.Kind.SUBSUMPTION -> R.string.tidy_group_subsumption
            }
        )
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(org.fossify.commons.R.dimen.bigger_text_size))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        alpha = 0.65f
        setPadding(
            resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin),
            resources.getDimensionPixelSize(org.fossify.commons.R.dimen.small_margin),
            resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin),
            0
        )
    }

    private fun proposalRow(proposal: RuleAdjuster.MergeProposal) =
        ItemTidyProposalBinding.inflate(layoutInflater, binding.tidyContainer, false).apply {
            proposalTitle.text = getString(
                R.string.tidy_title,
                proposal.earlier.name,
                proposal.later.name,
                FolderManager.displayName(this@TidyRulesActivity, proposal.earlier.mailbox)
            )
            proposalDifference.text = proposal.difference
            if (proposal.merged != null) {
                proposalMerged.beVisibleIf(true)
                proposalMerged.text = getString(
                    R.string.tidy_merged_rule,
                    proposal.earlier.name + " + merged",
                    RuleAdjuster.describeConditions(proposal.merged)
                )
            } else {
                proposalMerged.beVisibleIf(false)
            }
            proposalEvidence.text = evidenceText(proposal)
            proposalWarning.beVisibleIf(proposal.catchAll)
            if (proposal.catchAll) {
                proposalWarning.setText(R.string.tidy_catch_all_warning)
            }
            proposalCheckbox.isChecked = proposal in selected
            root.setOnClickListener {
                if (proposal in selected) {
                    selected.remove(proposal)
                } else {
                    selected.add(proposal)
                }
                proposalCheckbox.isChecked = proposal in selected
                updateApplyButton()
            }
        }.root

    /** How often each rule has fired, from the audit log (by rule name — approximate when a name is shared). */
    private fun evidenceText(proposal: RuleAdjuster.MergeProposal): String {
        val earlierCount = firingCounts[proposal.earlier.name] ?: 0
        val laterCount = firingCounts[proposal.later.name] ?: 0
        val text = getString(
            R.string.tidy_evidence,
            proposal.earlier.name,
            firedWords(earlierCount),
            proposal.later.name,
            firedWords(laterCount)
        )
        val sharedName = (nameCounts[proposal.earlier.name] ?: 0) > 1 ||
            (nameCounts[proposal.later.name] ?: 0) > 1
        return if (sharedName) {
            "$text\n${getString(R.string.tidy_shared_name_note)}"
        } else {
            text
        }
    }

    private fun firedWords(count: Int): String = when (count) {
        0 -> getString(R.string.tidy_never_fired)
        1 -> getString(R.string.tidy_fired_once)
        else -> getString(R.string.tidy_fired_times, count)
    }

    private fun updateApplyButton() {
        val count = selected.size
        binding.applyMergesButton.beVisibleIf(proposals.isNotEmpty())
        binding.applyMergesButton.text = if (count == 0) {
            getString(R.string.tidy_apply_none)
        } else {
            resources.getQuantityString(R.plurals.tidy_apply, count, count)
        }
    }

    private fun applySelectedMerges() {
        if (selected.isEmpty()) {
            toast(R.string.tidy_pick_one)
            return
        }
        val toApply = selected.toList()
        ensureBackgroundThread {
            val liveRules = try {
                filterRulesDB.getAll()
            } catch (e: Exception) {
                emptyList()
            }
            var applied = 0
            var skipped = 0
            for (proposal in toApply) {
                // Re-validate against the live list: a rule edited after
                // the screen opened makes the proposal stale, and a
                // stale proposal is skipped, never half-applied.
                if (!RuleAdjuster.isStillValid(proposal, liveRules)) {
                    skipped++
                    continue
                }
                val merged = proposal.merged
                if (merged != null) {
                    filterRulesDB.insertOrUpdate(
                        merged.copy(name = proposal.earlier.name + " + merged")
                    )
                }
                filterRulesDB.delete(proposal.dropped)
                applied++
            }
            runOnUiThread {
                toast(
                    if (skipped > 0) {
                        getString(R.string.tidy_applied_skipped, applied, skipped)
                    } else {
                        resources.getQuantityString(R.plurals.tidy_applied, applied, applied)
                    }
                )
                finish()
            }
        }
    }
}
