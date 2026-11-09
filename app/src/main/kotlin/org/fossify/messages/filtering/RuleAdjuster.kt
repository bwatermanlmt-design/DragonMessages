package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule

/**
 * The rule adjuster ("Tidy rules"): finds rules that do the same job and
 * proposes merging them. Pure — no Android dependencies, no message
 * reads — so the whole behaviour is unit-testable on the JVM.
 *
 * "Same job" means the same target folder and the same tag assignment,
 * the same firing behaviour, and adjacent in the evaluated list order
 * (priority ascending, ties by id). Adjacency is what makes merging safe
 * under first-match-wins: with nothing between the pair, moving the
 * merged rule to the earlier rule's priority cannot steal a message
 * from any rule in between, because there is no rule in between.
 *
 * Two shapes qualify (design section 1.4): one slot differs and the
 * values OR-combine exactly, or one rule subsumes the other (the
 * narrower rule can never change an outcome the broader one would not
 * produce itself). Seeker exception rules are never merged: their whole
 * job is their position relative to one specific rule below them.
 *
 * Proposals are conflict-free by construction: candidate pairs are taken
 * greedily in list order, and any other pair touching an already-taken
 * rule is skipped. Ben applies a round, then re-runs for the next round.
 */
object RuleAdjuster {

    /** What kind of merge a proposal is (display grouping follows this order). */
    enum class Kind(val groupOrder: Int) {
        KEYWORD(0),
        SENDER(1),
        SENDER_CONTAINS(1),
        BOOLEAN(2),
        SUBSUMPTION(3),
    }

    /**
     * One proposed merge. For a merge, [merged] is the rule that replaces
     * the pair (a copy of [earlier] with the combined conditions, same id
     * and priority) and [dropped] is [later]. For a subsumption drop,
     * [merged] is null and [dropped] is the narrower rule, which the
     * broader rule (the other of the pair) already covers.
     */
    data class MergeProposal(
        val kind: Kind,
        val earlier: FilterRule,
        val later: FilterRule,
        val merged: FilterRule?,
        /** Plain words for what differs between the two rules. */
        val difference: String,
        /** True when the merged rule would have no conditions left (matches every message). */
        val catchAll: Boolean = false,
        val dropped: FilterRule,
    )

    /** The prefix the seeker writes on exception rules ("Seeker exception: ..."). */
    const val SEEKER_EXCEPTION_PREFIX = "Seeker exception: "

    fun isExceptionRule(rule: FilterRule): Boolean {
        return rule.name.startsWith(SEEKER_EXCEPTION_PREFIX)
    }

    /** All merge proposals for the list, conflict-free, in display order (grouped, earliest first). */
    fun findMerges(rules: List<FilterRule>): List<MergeProposal> {
        val ordered = rules.sortedWith(compareBy({ it.priority }, { it.id }))
        val used = HashSet<Long>()
        val out = ArrayList<MergeProposal>()
        for (i in 0 until ordered.size - 1) {
            val a = ordered[i]
            val b = ordered[i + 1]
            if (a.id in used || b.id in used) continue
            if (isExceptionRule(a) || isExceptionRule(b)) continue
            val proposal = proposePair(a, b) ?: continue
            used += a.id
            used += b.id
            out += proposal
        }
        return out.sortedWith(compareBy({ it.kind.groupOrder }, { it.earlier.priority }, { it.earlier.id }))
    }

    /** Whether a ticked proposal is still valid against the live rule list (re-run before applying). */
    fun isStillValid(proposal: MergeProposal, liveRules: List<FilterRule>): Boolean {
        return proposal in findMerges(liveRules)
    }

    private fun proposePair(a: FilterRule, b: FilterRule): MergeProposal? {
        if (!sameEffect(a, b) || !sameFiring(a, b)) return null
        val diffs = differingSlots(a, b)
        if (diffs.isEmpty()) {
            // Identical conditions: the later rule can never change an
            // outcome the earlier one would not produce itself.
            return MergeProposal(
                kind = Kind.SUBSUMPTION,
                earlier = a,
                later = b,
                merged = null,
                difference = "'${b.name}' can never fire: '${a.name}' catches everything it would catch",
                dropped = b
            )
        }
        if (diffs.size == 1) {
            mergeOneSlot(a, b, diffs[0])?.let { return it }
        }
        // Subsumption (either direction): the narrower rule is dropped
        // and the broader one kept untouched.
        if (subsumes(a, b)) {
            return MergeProposal(
                kind = Kind.SUBSUMPTION,
                earlier = a,
                later = b,
                merged = null,
                difference = "'${b.name}' can never fire: '${a.name}' catches everything it would catch",
                dropped = b
            )
        }
        if (subsumes(b, a)) {
            return MergeProposal(
                kind = Kind.SUBSUMPTION,
                earlier = a,
                later = b,
                merged = null,
                difference = "'${a.name}' can never fire: '${b.name}' catches everything it would catch",
                dropped = a
            )
        }
        return null
    }

    /** Same target folder and same tag assignment. */
    private fun sameEffect(a: FilterRule, b: FilterRule): Boolean {
        return a.mailbox == b.mailbox && a.assignTag == b.assignTag
    }

    /** Same enabled state, macro-ness, condition mode, and regex flag. */
    private fun sameFiring(a: FilterRule, b: FilterRule): Boolean {
        return a.enabled == b.enabled &&
            a.isMacro == b.isMacro &&
            a.macroAutomatic == b.macroAutomatic &&
            a.conditionMode == b.conditionMode &&
            a.keywordRegex == b.keywordRegex
    }

    /** One condition slot, for the one-slot-differs check. */
    private enum class Slot {
        KEYWORD, SENDER, SENDER_CONTAINS, SENDER_PATTERN, SENDER_IN_CONTACTS,
        SENDER_TAG, CONTAINS_LINK, SENDER_CLASS, REPLIED_TO, MANUALLY_FILED, HAS_IMAGE
    }

    /** The slots whose values differ between the two rules. */
    private fun differingSlots(a: FilterRule, b: FilterRule): List<Slot> {
        val out = ArrayList<Slot>()
        if (!keywordSetsEqual(RuleEngine.effectiveKeywords(a), RuleEngine.effectiveKeywords(b))) out += Slot.KEYWORD
        if (!senderSetsEqual(RuleEngine.effectiveSenders(a), RuleEngine.effectiveSenders(b))) out += Slot.SENDER
        if (!containsSetsEqual(RuleEngine.effectiveSenderContains(a), RuleEngine.effectiveSenderContains(b))) out += Slot.SENDER_CONTAINS
        if (a.senderPattern != b.senderPattern) out += Slot.SENDER_PATTERN
        if (a.senderInContacts != b.senderInContacts) out += Slot.SENDER_IN_CONTACTS
        if (a.senderTag != b.senderTag) out += Slot.SENDER_TAG
        if (a.containsLink != b.containsLink) out += Slot.CONTAINS_LINK
        if (a.senderClass != b.senderClass) out += Slot.SENDER_CLASS
        if (a.repliedTo != b.repliedTo) out += Slot.REPLIED_TO
        if (a.manuallyFiled != b.manuallyFiled) out += Slot.MANUALLY_FILED
        if (a.hasImage != b.hasImage) out += Slot.HAS_IMAGE
        return out
    }

    /** The one-slot merge, or null when this slot cannot OR-combine exactly. */
    private fun mergeOneSlot(a: FilterRule, b: FilterRule, slot: Slot): MergeProposal? {
        return when (slot) {
            Slot.KEYWORD -> {
                val aList = RuleEngine.effectiveKeywords(a)
                val bList = RuleEngine.effectiveKeywords(b)
                if (aList.isEmpty() || bList.isEmpty()) return null
                val union = unionKeywords(aList, bList)
                MergeProposal(
                    kind = Kind.KEYWORD,
                    earlier = a,
                    later = b,
                    merged = a.copy(
                        keywordsJson = RuleEngine.keywordsToJson(union),
                        keyword = union.firstOrNull()
                    ),
                    difference = "keyword ${quotedList(aList)} vs keyword ${quotedList(bList)}",
                    dropped = b
                ).withCatchAll()
            }
            Slot.SENDER -> {
                val aList = RuleEngine.effectiveSenders(a)
                val bList = RuleEngine.effectiveSenders(b)
                if (aList.isEmpty() || bList.isEmpty()) return null
                val union = unionSenders(aList, bList)
                MergeProposal(
                    kind = Kind.SENDER,
                    earlier = a,
                    later = b,
                    merged = a.copy(
                        sendersJson = RuleEngine.stringListToJson(union),
                        senderExact = union.firstOrNull()
                    ),
                    difference = "sender ${aList.joinToString(", ")} vs sender ${bList.joinToString(", ")}",
                    dropped = b
                ).withCatchAll()
            }
            Slot.SENDER_CONTAINS -> {
                val aList = RuleEngine.effectiveSenderContains(a)
                val bList = RuleEngine.effectiveSenderContains(b)
                if (aList.isEmpty() || bList.isEmpty()) return null
                val union = unionContains(aList, bList)
                MergeProposal(
                    kind = Kind.SENDER_CONTAINS,
                    earlier = a,
                    later = b,
                    merged = a.copy(
                        senderContainsJson = RuleEngine.stringListToJson(union),
                        senderContains = union.firstOrNull()
                    ),
                    difference = "sender contains ${quotedList(aList)} vs sender contains ${quotedList(bList)}",
                    dropped = b
                ).withCatchAll()
            }
            Slot.SENDER_IN_CONTACTS -> booleanMerge(a, b, slot, a.senderInContacts, b.senderInContacts) { rule, value ->
                rule.copy(senderInContacts = value)
            }?.copy(difference = "in contacts: ${boolWord(a.senderInContacts)} vs ${boolWord(b.senderInContacts)}")
            Slot.CONTAINS_LINK -> booleanMerge(a, b, slot, a.containsLink, b.containsLink) { rule, value ->
                rule.copy(containsLink = value)
            }?.copy(difference = "contains a link: ${boolWord(a.containsLink)} vs ${boolWord(b.containsLink)}")
            Slot.REPLIED_TO -> booleanMerge(a, b, slot, a.repliedTo, b.repliedTo) { rule, value ->
                rule.copy(repliedTo = value)
            }?.copy(difference = "you replied: ${boolWord(a.repliedTo)} vs ${boolWord(b.repliedTo)}")
            Slot.MANUALLY_FILED -> booleanMerge(a, b, slot, a.manuallyFiled, b.manuallyFiled) { rule, value ->
                rule.copy(manuallyFiled = value)
            }?.copy(difference = "filed by hand: ${boolWord(a.manuallyFiled)} vs ${boolWord(b.manuallyFiled)}")
            Slot.HAS_IMAGE -> booleanMerge(a, b, slot, a.hasImage, b.hasImage) { rule, value ->
                rule.copy(hasImage = value)
            }?.copy(difference = "has an image: ${boolWord(a.hasImage)} vs ${boolWord(b.hasImage)}")
            Slot.SENDER_PATTERN, Slot.SENDER_TAG, Slot.SENDER_CLASS -> null
        }
    }

    /**
     * A true/false boolean pair drops the condition — but only in ALL
     * mode. In ANY mode the union of "X OR link" and "X OR no-link" is
     * every message, and dropping the slot would give "X": narrower,
     * not the union. Set-vs-unset is not this shape; it is subsumption.
     */
    private fun booleanMerge(
        a: FilterRule,
        b: FilterRule,
        slot: Slot,
        aValue: Boolean?,
        bValue: Boolean?,
        drop: (FilterRule, Boolean?) -> FilterRule
    ): MergeProposal? {
        if (aValue == null || bValue == null || aValue == bValue) return null
        if (a.conditionMode != RuleEngine.CONDITION_MODE_ALL) return null
        return MergeProposal(
            kind = Kind.BOOLEAN,
            earlier = a,
            later = b,
            merged = drop(a, null),
            difference = "",
            dropped = b
        ).withCatchAll()
    }

    private fun MergeProposal.withCatchAll(): MergeProposal {
        val merged = merged ?: return this
        return if (hasNoConditions(merged)) copy(catchAll = true) else this
    }

    /** Whether the rule has no set condition at all (matches every message). */
    fun hasNoConditions(rule: FilterRule): Boolean {
        return RuleEngine.effectiveSenders(rule).isEmpty() &&
            rule.senderPattern == null &&
            RuleEngine.effectiveSenderContains(rule).isEmpty() &&
            rule.senderInContacts == null &&
            rule.senderTag == null &&
            RuleEngine.effectiveKeywords(rule).isEmpty() &&
            rule.containsLink == null &&
            rule.senderClass == null &&
            rule.repliedTo == null &&
            rule.manuallyFiled == null &&
            rule.hasImage == null
    }

    /** Whether [broader]'s match set contains [narrower]'s, under the rules' shared condition mode. */
    private fun subsumes(broader: FilterRule, narrower: FilterRule): Boolean {
        return if (broader.conditionMode == RuleEngine.CONDITION_MODE_ANY) {
            subsumesAny(broader, narrower)
        } else {
            subsumesAll(broader, narrower)
        }
    }

    /**
     * ALL mode: a rule is broader when every condition it sets is also
     * set in the narrower rule to the same value (a list slot covers
     * when it holds every entry of the narrower list). A condition the
     * broader rule does not set constrains nothing.
     */
    private fun subsumesAll(broader: FilterRule, narrower: FilterRule): Boolean {
        if (hasNoConditions(broader)) return true
        if (!scalarCovers(broader.senderPattern, narrower.senderPattern)) return false
        if (!scalarCovers(broader.senderTag, narrower.senderTag)) return false
        if (!scalarCovers(broader.senderClass, narrower.senderClass)) return false
        if (!boolCovers(broader.senderInContacts, narrower.senderInContacts)) return false
        if (!boolCovers(broader.containsLink, narrower.containsLink)) return false
        if (!boolCovers(broader.repliedTo, narrower.repliedTo)) return false
        if (!boolCovers(broader.manuallyFiled, narrower.manuallyFiled)) return false
        if (!boolCovers(broader.hasImage, narrower.hasImage)) return false
        if (!listCoversAll(RuleEngine.effectiveKeywords(broader), RuleEngine.effectiveKeywords(narrower)) { x, y -> x.equals(y, ignoreCase = true) }) return false
        if (!listCoversAll(RuleEngine.effectiveSenders(broader), RuleEngine.effectiveSenders(narrower)) { x, y -> PhoneNumbers.equal(x, y) }) return false
        if (!listCoversAll(RuleEngine.effectiveSenderContains(broader), RuleEngine.effectiveSenderContains(narrower)) { x, y -> x.equals(y, ignoreCase = true) }) return false
        return true
    }

    /**
     * ANY mode: more conditions means a broader match, so a rule is
     * broader when every condition the narrower rule sets is also set
     * in it to the same value (a list slot covers when it holds every
     * entry of the narrower list).
     */
    private fun subsumesAny(broader: FilterRule, narrower: FilterRule): Boolean {
        if (hasNoConditions(broader)) return true
        if (hasNoConditions(narrower)) return false
        if (!scalarCovered(narrower.senderPattern, broader.senderPattern)) return false
        if (!scalarCovered(narrower.senderTag, broader.senderTag)) return false
        if (!scalarCovered(narrower.senderClass, broader.senderClass)) return false
        if (!boolCovered(narrower.senderInContacts, broader.senderInContacts)) return false
        if (!boolCovered(narrower.containsLink, broader.containsLink)) return false
        if (!boolCovered(narrower.repliedTo, broader.repliedTo)) return false
        if (!boolCovered(narrower.manuallyFiled, broader.manuallyFiled)) return false
        if (!boolCovered(narrower.hasImage, broader.hasImage)) return false
        if (!listCoversAny(RuleEngine.effectiveKeywords(broader), RuleEngine.effectiveKeywords(narrower)) { x, y -> x.equals(y, ignoreCase = true) }) return false
        if (!listCoversAny(RuleEngine.effectiveSenders(broader), RuleEngine.effectiveSenders(narrower)) { x, y -> PhoneNumbers.equal(x, y) }) return false
        if (!listCoversAny(RuleEngine.effectiveSenderContains(broader), RuleEngine.effectiveSenderContains(narrower)) { x, y -> x.equals(y, ignoreCase = true) }) return false
        return true
    }

    /** ALL-mode scalar cover: the broader value is unset, or equal to the narrower one. */
    private fun scalarCovers(broaderValue: String?, narrowerValue: String?): Boolean {
        return broaderValue == null || broaderValue == narrowerValue
    }

    private fun boolCovers(broaderValue: Boolean?, narrowerValue: Boolean?): Boolean {
        return broaderValue == null || broaderValue == narrowerValue
    }

    /** ANY-mode scalar cover: the narrower value is unset, or equal to the broader one. */
    private fun scalarCovered(narrowerValue: String?, broaderValue: String?): Boolean {
        return narrowerValue == null || narrowerValue == broaderValue
    }

    private fun boolCovered(narrowerValue: Boolean?, broaderValue: Boolean?): Boolean {
        return narrowerValue == null || narrowerValue == broaderValue
    }

    /**
     * ALL-mode list cover: the broader list must not constrain more
     * than the narrower one — it is empty (no constraint), or it holds
     * every entry of the narrower list.
     */
    private fun listCoversAll(broaderList: List<String>, narrowerList: List<String>, equal: (String, String) -> Boolean): Boolean {
        if (broaderList.isEmpty()) return true
        if (narrowerList.isEmpty()) return false
        return narrowerList.all { narrow -> broaderList.any { equal(it, narrow) } }
    }

    /** ANY-mode list cover: the narrower list is empty, or the broader list holds every entry of it. */
    private fun listCoversAny(broaderList: List<String>, narrowerList: List<String>, equal: (String, String) -> Boolean): Boolean {
        if (narrowerList.isEmpty()) return true
        return broaderList.isNotEmpty() && narrowerList.all { narrow -> broaderList.any { equal(it, narrow) } }
    }

    /** Plain-words reading of a rule's conditions, for proposal rows ("keyword 'prize' OR 'claim', has an image"). */
    fun describeConditions(rule: FilterRule): String {
        val parts = ArrayList<String>()
        val senders = RuleEngine.effectiveSenders(rule)
        if (senders.isNotEmpty()) {
            parts += "sender " + senders.joinToString(" OR ")
        }
        rule.senderPattern?.let { parts += "sender matches '$it'" }
        val contains = RuleEngine.effectiveSenderContains(rule)
        if (contains.isNotEmpty()) {
            parts += "sender contains " + contains.joinToString(" OR ") { "'$it'" }
        }
        rule.senderInContacts?.let { parts += if (it) "in contacts" else "not in contacts" }
        rule.senderTag?.let { parts += "tag '$it'" }
        val keywords = RuleEngine.effectiveKeywords(rule)
        if (keywords.isNotEmpty()) {
            parts += "keyword " + keywords.joinToString(" OR ") { "'$it'" }
        }
        rule.containsLink?.let { parts += if (it) "has a link" else "has no link" }
        rule.senderClass?.let {
            parts += when (it) {
                SenderClassifier.SHORT_CODE -> "short code"
                SenderClassifier.ALPHANUMERIC -> "alphanumeric sender"
                else -> "long number"
            }
        }
        rule.repliedTo?.let { parts += if (it) "you replied" else "you never replied" }
        rule.manuallyFiled?.let { parts += if (it) "filed by hand" else "not filed by hand" }
        rule.hasImage?.let { parts += if (it) "has an image" else "has no image" }
        if (parts.isEmpty()) return "matches every message"
        return if (rule.conditionMode == RuleEngine.CONDITION_MODE_ANY) {
            parts.joinToString(" OR ")
        } else {
            parts.joinToString(", ")
        }
    }

    private fun boolWord(value: Boolean?): String = when (value) {
        true -> "yes"
        false -> "no"
        null -> "either"
    }

    private fun quotedList(values: List<String>): String {
        return values.joinToString(", ") { "'$it'" }
    }

    private fun keywordSetsEqual(a: List<String>, b: List<String>): Boolean {
        return a.size == b.size && a.all { x -> b.any { it.equals(x, ignoreCase = true) } }
    }

    private fun senderSetsEqual(a: List<String>, b: List<String>): Boolean {
        return a.size == b.size && a.all { x -> b.any { PhoneNumbers.equal(it, x) } }
    }

    private fun containsSetsEqual(a: List<String>, b: List<String>): Boolean {
        return a.size == b.size && a.all { x -> b.any { it.equals(x, ignoreCase = true) } }
    }

    private fun unionKeywords(a: List<String>, b: List<String>): List<String> {
        val out = ArrayList(a)
        b.forEach { value ->
            if (out.none { it.equals(value, ignoreCase = true) }) out += value
        }
        return out
    }

    private fun unionSenders(a: List<String>, b: List<String>): List<String> {
        val out = ArrayList(a)
        b.forEach { value ->
            if (out.none { PhoneNumbers.equal(it, value) }) out += value
        }
        return out
    }

    private fun unionContains(a: List<String>, b: List<String>): List<String> {
        val out = ArrayList(a)
        b.forEach { value ->
            if (out.none { it.equals(value, ignoreCase = true) }) out += value
        }
        return out
    }
}
