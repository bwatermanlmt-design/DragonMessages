package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule adjuster: adjacent pairs only, one slot differing and
 * OR-combinable, or one rule subsuming the other. Every refusal in the
 * design has a test here.
 */
class RuleAdjusterTest {

    private fun rule(
        id: Long = 0,
        name: String = "Rule",
        priority: Int = 10,
        enabled: Boolean = true,
        mailbox: String = Mailbox.SPAM,
        assignTag: String? = null,
        keyword: String? = null,
        keywordsJson: String? = null,
        senderExact: String? = null,
        sendersJson: String? = null,
        senderContains: String? = null,
        senderContainsJson: String? = null,
        senderPattern: String? = null,
        senderInContacts: Boolean? = null,
        senderTag: String? = null,
        containsLink: Boolean? = null,
        senderClass: String? = null,
        repliedTo: Boolean? = null,
        manuallyFiled: Boolean? = null,
        hasImage: Boolean? = null,
        conditionMode: String = RuleEngine.CONDITION_MODE_ALL,
        keywordRegex: Boolean = false,
        isMacro: Boolean = false,
        macroAutomatic: Boolean = false
    ) = FilterRule(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority,
        senderExact = senderExact,
        sendersJson = sendersJson,
        senderPattern = senderPattern,
        senderContains = senderContains,
        senderContainsJson = senderContainsJson,
        senderInContacts = senderInContacts,
        senderTag = senderTag,
        keyword = keyword,
        keywordsJson = keywordsJson,
        conditionMode = conditionMode,
        keywordRegex = keywordRegex,
        containsLink = containsLink,
        senderClass = senderClass,
        repliedTo = repliedTo,
        manuallyFiled = manuallyFiled,
        hasImage = hasImage,
        mailbox = mailbox,
        assignTag = assignTag,
        isMacro = isMacro,
        macroAutomatic = macroAutomatic
    )

    // region shape 1: keyword union

    @Test
    fun `keyword pair merges into the union, earlier priority kept`() {
        val a = rule(id = 1, name = "A", priority = 10, keyword = "prize")
        val b = rule(id = 2, name = "B", priority = 11, keyword = "claim")
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        val proposal = proposals[0]
        assertEquals(RuleAdjuster.Kind.KEYWORD, proposal.kind)
        assertEquals(listOf("prize", "claim"), RuleEngine.effectiveKeywords(proposal.merged!!))
        assertEquals("prize", proposal.merged.keyword)
        assertEquals(10, proposal.merged.priority)
        assertEquals(1L, proposal.merged.id)
        assertEquals(b, proposal.dropped)
        assertFalse(proposal.catchAll)
    }

    @Test
    fun `keyword union deduplicates case-insensitively`() {
        val a = rule(id = 1, priority = 10, keywordsJson = RuleEngine.keywordsToJson(listOf("Prize", "win")))
        val b = rule(id = 2, priority = 11, keywordsJson = RuleEngine.keywordsToJson(listOf("prize", "claim")))
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        assertEquals(listOf("Prize", "win", "claim"), RuleEngine.effectiveKeywords(proposals[0].merged!!))
    }

    // region shape 1: sender unions

    @Test
    fun `sender pair merges into senders_json with first entry kept as senderExact`() {
        val a = rule(id = 1, priority = 10, senderExact = "+14805550100")
        val b = rule(id = 2, priority = 11, senderExact = "+14805550101")
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        val merged = proposals[0].merged!!
        assertEquals(RuleAdjuster.Kind.SENDER, proposals[0].kind)
        assertEquals(listOf("+14805550100", "+14805550101"), RuleEngine.effectiveSenders(merged))
        assertEquals("+14805550100", merged.senderExact)
    }

    @Test
    fun `differently formatted same number is the same rule - drop the later one`() {
        val a = rule(id = 1, priority = 10, senderExact = "+14805550100")
        val b = rule(id = 2, priority = 11, senderExact = "4805550100")
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        assertEquals(RuleAdjuster.Kind.SUBSUMPTION, proposals[0].kind)
        assertEquals(b, proposals[0].dropped)
    }

    @Test
    fun `sender-contains pair merges into sender_contains_json`() {
        val a = rule(id = 1, priority = 10, senderContains = "bank")
        val b = rule(id = 2, priority = 11, senderContains = "prize")
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        val merged = proposals[0].merged!!
        assertEquals(RuleAdjuster.Kind.SENDER_CONTAINS, proposals[0].kind)
        assertEquals(listOf("bank", "prize"), RuleEngine.effectiveSenderContains(merged))
        assertEquals("bank", merged.senderContains)
    }

    // region shape 1: boolean pair

    @Test
    fun `true-false boolean pair drops the condition in ALL mode`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", containsLink = true)
        val b = rule(id = 2, priority = 11, keyword = "prize", containsLink = false)
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        assertEquals(RuleAdjuster.Kind.BOOLEAN, proposals[0].kind)
        assertEquals(listOf("prize"), RuleEngine.effectiveKeywords(proposals[0].merged!!))
        assertEquals(null, proposals[0].merged!!.containsLink)
        assertFalse(proposals[0].catchAll)
    }

    @Test
    fun `true-false boolean pair is refused in ANY mode`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", containsLink = true, conditionMode = RuleEngine.CONDITION_MODE_ANY)
        val b = rule(id = 2, priority = 11, keyword = "prize", containsLink = false, conditionMode = RuleEngine.CONDITION_MODE_ANY)
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `boolean-only pair becomes a catch-all merge with the warning flag`() {
        val a = rule(id = 1, priority = 10, containsLink = true)
        val b = rule(id = 2, priority = 11, containsLink = false)
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        assertTrue(proposals[0].catchAll)
        assertTrue(RuleAdjuster.hasNoConditions(proposals[0].merged!!))
    }

    @Test
    fun `hasImage true-false pair drops the condition in ALL mode`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", hasImage = true)
        val b = rule(id = 2, priority = 11, keyword = "prize", hasImage = false)
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        assertEquals(null, proposals[0].merged!!.hasImage)
    }

    // region shape 2: subsumption

    @Test
    fun `extra-condition rule is subsumed by the plainer adjacent rule in ALL mode`() {
        val plain = rule(id = 1, name = "Plain", priority = 10, keyword = "prize")
        val narrow = rule(id = 2, name = "Narrow", priority = 11, keyword = "prize", containsLink = true)
        val proposals = RuleAdjuster.findMerges(listOf(plain, narrow))
        assertEquals(1, proposals.size)
        assertEquals(RuleAdjuster.Kind.SUBSUMPTION, proposals[0].kind)
        assertEquals(narrow, proposals[0].dropped)
        assertEquals(null, proposals[0].merged)
    }

    @Test
    fun `subsumption works when the broader rule is the later one`() {
        val narrow = rule(id = 1, name = "Narrow", priority = 10, keyword = "prize", containsLink = true)
        val plain = rule(id = 2, name = "Plain", priority = 11, keyword = "prize")
        val proposals = RuleAdjuster.findMerges(listOf(narrow, plain))
        assertEquals(1, proposals.size)
        assertEquals(RuleAdjuster.Kind.SUBSUMPTION, proposals[0].kind)
        assertEquals(narrow, proposals[0].dropped)
    }

    @Test
    fun `ANY mode subsumption flips direction - more conditions is broader`() {
        val one = rule(id = 1, name = "One", priority = 10, keyword = "prize", conditionMode = RuleEngine.CONDITION_MODE_ANY)
        val two = rule(id = 2, name = "Two", priority = 11, keyword = "prize", containsLink = true, conditionMode = RuleEngine.CONDITION_MODE_ANY)
        val proposals = RuleAdjuster.findMerges(listOf(one, two))
        assertEquals(1, proposals.size)
        assertEquals(RuleAdjuster.Kind.SUBSUMPTION, proposals[0].kind)
        assertEquals(one, proposals[0].dropped)
    }

    @Test
    fun `identical adjacent rules propose dropping the later one`() {
        val a = rule(id = 1, name = "A", priority = 10, keyword = "prize")
        val b = rule(id = 2, name = "B", priority = 11, keyword = "prize")
        val proposals = RuleAdjuster.findMerges(listOf(a, b))
        assertEquals(1, proposals.size)
        assertEquals(RuleAdjuster.Kind.SUBSUMPTION, proposals[0].kind)
        assertEquals(b, proposals[0].dropped)
    }

    @Test
    fun `keyword list superset merges to the broader list`() {
        val broad = rule(id = 1, name = "Broad", priority = 10, keywordsJson = RuleEngine.keywordsToJson(listOf("prize", "claim")))
        val narrow = rule(id = 2, name = "Narrow", priority = 11, keyword = "prize")
        val proposals = RuleAdjuster.findMerges(listOf(broad, narrow))
        assertEquals(1, proposals.size)
        assertEquals(RuleAdjuster.Kind.KEYWORD, proposals[0].kind)
        assertEquals(listOf("prize", "claim"), RuleEngine.effectiveKeywords(proposals[0].merged!!))
        assertEquals(narrow, proposals[0].dropped)
    }

    // region refusals

    @Test
    fun `different folders never merge`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", mailbox = Mailbox.SPAM)
        val b = rule(id = 2, priority = 11, keyword = "claim", mailbox = Mailbox.AUTH)
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `different tags never merge`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", assignTag = "x")
        val b = rule(id = 2, priority = 11, keyword = "claim", assignTag = "y")
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `enabled and disabled never merge`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", enabled = true)
        val b = rule(id = 2, priority = 11, keyword = "claim", enabled = false)
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `two disabled rules may merge`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", enabled = false)
        val b = rule(id = 2, priority = 11, keyword = "claim", enabled = false)
        assertEquals(1, RuleAdjuster.findMerges(listOf(a, b)).size)
    }

    @Test
    fun `macro and ordinary rule never merge`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", isMacro = true, macroAutomatic = true)
        val b = rule(id = 2, priority = 11, keyword = "claim")
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `different condition modes never merge`() {
        val a = rule(id = 1, priority = 10, keyword = "prize")
        val b = rule(id = 2, priority = 11, keyword = "claim", conditionMode = RuleEngine.CONDITION_MODE_ANY)
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `regex and substring keyword rules never merge`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", keywordRegex = false)
        val b = rule(id = 2, priority = 11, keyword = "claim", keywordRegex = true)
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `non-adjacent pairs are never proposed`() {
        // The outer two are identical, but the middle rule stands between
        // them — and it blocks both adjacent pairs too, differing from
        // each neighbour in two slots (keyword AND contains-link), so no
        // pair in the list merges at all.
        val a = rule(id = 1, priority = 10, keyword = "prize")
        val between = rule(id = 2, priority = 11, keyword = "other", containsLink = true)
        val b = rule(id = 3, priority = 12, keyword = "prize")
        assertTrue(RuleAdjuster.findMerges(listOf(a, between, b)).isEmpty())
    }

    @Test
    fun `a disabled rule between two mergeable rules blocks the proposal`() {
        val a = rule(id = 1, priority = 10, keyword = "prize")
        val between = rule(id = 2, priority = 11, keyword = "other", enabled = false)
        val b = rule(id = 3, priority = 12, keyword = "claim")
        assertTrue(RuleAdjuster.findMerges(listOf(a, between, b)).isEmpty())
    }

    @Test
    fun `seeker exception rules are never merged`() {
        val exception = rule(id = 1, name = "Seeker exception: +14805550100 - 0 spam, 0 ham", priority = 10, senderExact = "+14805550100")
        val broad = rule(id = 2, priority = 11, senderExact = "+14805550101")
        assertTrue(RuleAdjuster.findMerges(listOf(exception, broad)).isEmpty())
    }

    @Test
    fun `two-slot differences are out of v1`() {
        val a = rule(id = 1, priority = 10, keyword = "prize", containsLink = true)
        val b = rule(id = 2, priority = 11, keyword = "claim", containsLink = false)
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `sender tag differences are not mergeable`() {
        val a = rule(id = 1, priority = 10, senderTag = "a")
        val b = rule(id = 2, priority = 11, senderTag = "b")
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `sender pattern differences are not mergeable`() {
        val a = rule(id = 1, priority = 10, senderPattern = "480*")
        val b = rule(id = 2, priority = 11, senderPattern = "481*")
        assertTrue(RuleAdjuster.findMerges(listOf(a, b)).isEmpty())
    }

    @Test
    fun `a pair differing only by set-vs-unset keyword is subsumption not a union`() {
        // A (no keyword) subsumes B (keyword): the union must be A, not B.
        val plain = rule(id = 1, name = "Plain", priority = 10, containsLink = true)
        val keyed = rule(id = 2, name = "Keyed", priority = 11, containsLink = true, keyword = "prize")
        val proposals = RuleAdjuster.findMerges(listOf(plain, keyed))
        assertEquals(1, proposals.size)
        assertEquals(RuleAdjuster.Kind.SUBSUMPTION, proposals[0].kind)
        assertEquals(keyed, proposals[0].dropped)
    }

    // region conflict-free greedy

    @Test
    fun `a rule appears in at most one proposal per pass`() {
        val a = rule(id = 1, priority = 10, keyword = "prize")
        val b = rule(id = 2, priority = 11, keyword = "claim")
        val c = rule(id = 3, priority = 12, keyword = "win")
        val proposals = RuleAdjuster.findMerges(listOf(a, b, c))
        assertEquals(1, proposals.size)
        assertEquals(a, proposals[0].earlier)
        assertEquals(b, proposals[0].later)
    }

    @Test
    fun `proposals group by kind with earliest first within a group`() {
        val senderA = rule(id = 1, priority = 10, senderExact = "+14805550100")
        val senderB = rule(id = 2, priority = 11, senderExact = "+14805550101")
        val keyA = rule(id = 3, priority = 12, keyword = "prize")
        val keyB = rule(id = 4, priority = 13, keyword = "claim")
        val proposals = RuleAdjuster.findMerges(listOf(senderA, senderB, keyA, keyB))
        assertEquals(2, proposals.size)
        assertEquals(RuleAdjuster.Kind.KEYWORD, proposals[0].kind)
        assertEquals(RuleAdjuster.Kind.SENDER, proposals[1].kind)
    }

    @Test
    fun `merged rule still matches everything the pair matched`() {
        val a = rule(id = 1, priority = 10, keyword = "prize")
        val b = rule(id = 2, priority = 11, keyword = "claim")
        val merged = RuleAdjuster.findMerges(listOf(a, b))[0].merged!!
        val factsPrize = MessageFacts(sender = "+14805550137", body = "you won a prize", senderInContacts = false)
        val factsClaim = MessageFacts(sender = "+14805550137", body = "file your claim", senderInContacts = false)
        val factsOther = MessageFacts(sender = "+14805550137", body = "hello there", senderInContacts = false)
        assertTrue(RuleEngine.matches(merged, factsPrize))
        assertTrue(RuleEngine.matches(merged, factsClaim))
        assertFalse(RuleEngine.matches(merged, factsOther))
        assertFalse(RuleEngine.matches(a, factsClaim))
        assertFalse(RuleEngine.matches(b, factsPrize))
    }
}
