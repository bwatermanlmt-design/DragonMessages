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
import org.fossify.messages.databinding.ActivitySuggestRulesBinding
import org.fossify.messages.databinding.ItemSeekerProposalBinding
import org.fossify.messages.extensions.filterRulesDB
import org.fossify.messages.filtering.PatternSeeker
import org.fossify.messages.filtering.SeekerKind
import org.fossify.messages.filtering.SeekerProposal
import org.fossify.messages.helpers.SeekerData
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * Suggest rules (the pattern seeker): patterns mined from the Spam
 * folder that never matched a good message, with their evidence, for
 * Ben to pick. Only ticked proposals become rules, at priority 15.
 *
 * Refine mode (EXTRA_REFINE_RULE_ID + EXTRA_REFINE_THREAD_ID): the
 * same mining pass re-run with the misfiled message as one more ham
 * example — an exception rule first, then narrower variants of the
 * target rule. Entered from "Why is this here?".
 */
class SuggestRulesActivity : SimpleActivity() {

    companion object {
        const val EXTRA_REFINE_RULE_ID = "refine_rule_id"
        const val EXTRA_REFINE_THREAD_ID = "refine_thread_id"
    }

    private val binding by viewBinding(ActivitySuggestRulesBinding::inflate)

    private var proposals: List<SeekerProposal> = emptyList()
    private val selected = LinkedHashSet<SeekerProposal>()
    private var isRefineMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.suggestRulesScrollview))

        binding.createRulesButton.setOnClickListener { createSelectedRules() }
        loadProposals()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.suggestRulesAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.suggestRulesCoordinator)
    }

    private fun loadProposals() {
        ensureBackgroundThread {
            val data = SeekerData.load(this)
            val refineRuleId = intent.getLongExtra(EXTRA_REFINE_RULE_ID, -1)
            val refineThreadId = intent.getLongExtra(EXTRA_REFINE_THREAD_ID, -1)
            var emptyTextId = R.string.seeker_empty_no_patterns
            if (refineRuleId != -1L && refineThreadId != -1L) {
                isRefineMode = true
                val rule = data.rules.firstOrNull { it.id == refineRuleId }
                val misfiled = SeekerData.latestInbound(this, refineThreadId)
                proposals = if (rule != null && misfiled != null) {
                    PatternSeeker.refine(rule, misfiled, data.seed, data.ham, data.rules)
                } else {
                    emptyList()
                }
                emptyTextId = R.string.seeker_refine_empty
            } else {
                proposals = PatternSeeker.mine(data.seed, data.ham, data.rules)
                emptyTextId = if (data.seed.isEmpty()) {
                    R.string.seeker_empty_no_spam
                } else {
                    R.string.seeker_empty_no_patterns
                }
            }

            runOnUiThread {
                showProposals(emptyTextId)
            }
        }
    }

    private fun showProposals(emptyTextId: Int) {
        binding.seekerLoading.beVisibleIf(false)
        binding.seekerEmpty.beVisibleIf(proposals.isEmpty())
        if (proposals.isEmpty()) {
            binding.seekerEmpty.setText(emptyTextId)
            return
        }

        if (isRefineMode) {
            binding.suggestRulesSubtitle.setText(R.string.seeker_refine_subtitle)
            binding.suggestRulesToolbar.title = getString(R.string.seeker_refine_title)
        }

        val container = binding.seekerContainer
        container.removeAllViews()

        var lastGroup: SeekerKind? = null
        for (proposal in proposals) {
            val group = proposal.kind
            if (group != lastGroup) {
                container.addView(groupHeader(group))
                lastGroup = group
            }
            container.addView(proposalRow(proposal))
        }
        updateTextColors(container)
        updateCreateButton()
    }

    private fun groupHeader(kind: SeekerKind) = MyTextView(this).apply {
        setText(
            when (kind) {
                SeekerKind.SENDER -> R.string.seeker_group_sender
                SeekerKind.KEYWORD, SeekerKind.DOMAIN -> R.string.seeker_group_keyword
                SeekerKind.PHRASE -> R.string.seeker_group_phrase
                SeekerKind.AND_COMBINATION -> R.string.seeker_group_combination
            }
        )
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(org.fossify.commons.R.dimen.bigger_text_size))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        alpha = 0.65f
        setPadding(
            resources.getDimensionPixelSize(R.dimen.activity_margin),
            resources.getDimensionPixelSize(R.dimen.small_margin),
            resources.getDimensionPixelSize(R.dimen.activity_margin),
            0
        )
    }

    private fun proposalRow(proposal: SeekerProposal) =
        ItemSeekerProposalBinding.inflate(layoutInflater, binding.seekerContainer, false).apply {
            proposalPattern.text = proposal.display
            proposalEvidence.text = if (proposal.isException) {
                getString(R.string.seeker_exception_evidence)
            } else {
                getString(R.string.seeker_evidence, proposal.seedMatches)
            }
            val example = proposal.exampleBody.replace(Regex("\\s+"), " ").trim().take(120)
            proposalExample.text = getString(R.string.seeker_example, proposal.exampleSender, example)
            proposal.replacesRule?.let { old ->
                proposalReplaces.beVisibleIf(true)
                proposalReplaces.text = getString(R.string.seeker_replaces, old.name)
            }
            proposal.narrowsRule?.takeIf { !proposal.isException }?.let { target ->
                proposalReplaces.beVisibleIf(true)
                proposalReplaces.text = getString(R.string.seeker_narrows, target.name)
            }
            proposalCheckbox.isChecked = proposal in selected
            root.setOnClickListener {
                if (proposal in selected) {
                    selected.remove(proposal)
                } else {
                    selected.add(proposal)
                }
                proposalCheckbox.isChecked = proposal in selected
                updateCreateButton()
            }
        }.root

    private fun updateCreateButton() {
        val count = selected.size
        binding.createRulesButton.beVisibleIf(proposals.isNotEmpty())
        binding.createRulesButton.text = if (count == 0) {
            getString(R.string.seeker_create_rules_none)
        } else {
            resources.getQuantityString(R.plurals.seeker_create_rules, count, count)
        }
    }

    private fun createSelectedRules() {
        if (selected.isEmpty()) {
            toast(R.string.seeker_pick_one)
            return
        }
        val toCreate = selected.toList()
        ensureBackgroundThread {
            for (proposal in toCreate) {
                when {
                    proposal.isException -> {
                        filterRulesDB.insertOrUpdate(proposal.rule)
                    }
                    proposal.narrowsRule != null -> {
                        filterRulesDB.insertOrUpdate(narrowed(proposal.narrowsRule, proposal))
                    }
                    else -> {
                        proposal.replacesRule?.let { filterRulesDB.delete(it) }
                        filterRulesDB.insertOrUpdate(proposal.rule)
                    }
                }
            }
            val count = toCreate.size
            runOnUiThread {
                toast(resources.getQuantityString(R.plurals.seeker_rules_created, count, count))
                finish()
            }
        }
    }

    /**
     * Rewrite the target rule in place with the proposal's match: the
     * proposal's keyword (or sender) replaces the target's, and the
     * multi-keyword list is cleared — it would otherwise keep matching
     * the old pattern alongside the new one.
     */
    private fun narrowed(target: FilterRule, proposal: SeekerProposal): FilterRule {
        return target.copy(
            name = proposal.rule.name,
            senderExact = proposal.rule.senderExact,
            keyword = proposal.rule.keyword,
            keywordsJson = null,
            keywordRegex = proposal.rule.keywordRegex,
        )
    }
}
