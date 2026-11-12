package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternSeekerTest {

    private fun msg(
        sender: String,
        body: String,
        inContacts: Boolean = false,
        repliedTo: Boolean = false,
    ) = SeekerMessage(sender, body, inContacts, repliedTo)

    private fun mine(
        seed: List<SeekerMessage>,
        ham: List<SeekerMessage>,
        rules: List<FilterRule> = emptyList(),
    ) = PatternSeeker.mine(seed, ham, rules)

    // -- Rung 0: sender numbers -------------------------------------------------

    @Test
    fun senderNumberIsNeverProposedAsFilter() {
        // Ben, 2026-11-12: a sender's number is for blocking, never for a
        // filter rule. The seeker proposes patterns only.
        val seed = listOf(msg("+15551234567", "Claim your free prize now"))
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        assertTrue(proposals.none { it.kind == SeekerKind.SENDER })
        assertTrue(proposals.any { it.kind == SeekerKind.KEYWORD })
    }

    @Test
    fun senderInHamIsNotProposed() {
        val seed = listOf(msg("+15551234567", "Claim your free prize now"))
        val ham = listOf(msg("+15551234567", "Dinner at six"))
        val proposals = mine(seed, ham)

        assertNull(proposals.firstOrNull { it.kind == SeekerKind.SENDER })
    }

    @Test
    fun senderInContactsIsSkipped() {
        val seed = listOf(msg("+15551234567", "Claim your free prize now", inContacts = true))
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        assertNull(proposals.firstOrNull { it.kind == SeekerKind.SENDER })
    }

    // -- Rung 1: single words ---------------------------------------------------

    @Test
    fun wordOnlyInSeedIsProposedWithCounts() {
        val seed = listOf(
            msg("+15551111111", "Claim your prize today"),
            msg("+15552222222", "Your prize is waiting")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        val prize = proposals.firstOrNull { it.kind == SeekerKind.KEYWORD && it.display == "\"prize\"" }
        assertNotNull(prize)
        assertEquals(2, prize!!.seedMatches)
        assertEquals(0, prize.hamMatches)
        assertEquals("prize", prize.rule.keyword)
        assertEquals(PatternSeeker.SEEKER_PRIORITY, prize.rule.priority)
        assertEquals(Mailbox.SPAM, prize.rule.mailbox)
    }

    @Test
    fun singleHitPatternIsProposed() {
        val seed = listOf(
            msg("+15551111111", "Claim your prize today"),
            msg("+15552222222", "Your prize is waiting")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        // "claim" covers exactly one seed message: coverage 1 still qualifies.
        val claim = proposals.firstOrNull { it.kind == SeekerKind.KEYWORD && it.display == "\"claim\"" }
        assertNotNull(claim)
        assertEquals(1, claim!!.seedMatches)
    }

    @Test
    fun wordInHamIsNotProposed() {
        val seed = listOf(msg("+15551111111", "Claim your prize today"))
        val ham = listOf(msg("+15559876543", "You won a prize at bingo"))
        val proposals = mine(seed, ham)

        assertNull(proposals.firstOrNull { it.kind == SeekerKind.KEYWORD && it.display == "\"prize\"" })
    }

    // -- Rung 2: phrases --------------------------------------------------------

    @Test
    fun phraseProposed() {
        val seed = listOf(
            msg("+15551111111", "Win a big prize today"),
            msg("+15552222222", "Win a big prize now")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        val phrase = proposals.firstOrNull { it.kind == SeekerKind.PHRASE && it.display == "\"big prize\"" }
        assertNotNull(phrase)
        assertEquals(2, phrase!!.seedMatches)
        assertEquals("big prize", phrase.rule.keyword)
    }

    @Test
    fun phraseHittingHamIsNotProposed() {
        val seed = listOf(msg("+15551111111", "Win a big prize today"))
        val ham = listOf(msg("+15559876543", "A big prize for the winner"))
        val proposals = mine(seed, ham)

        assertNull(proposals.firstOrNull { it.kind == SeekerKind.PHRASE && it.display == "\"big prize\"" })
    }

    // -- Link domains -----------------------------------------------------------

    @Test
    fun domainProposed() {
        val seed = listOf(msg("+15551111111", "Go to https://givtxt.com/abc to claim"))
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        val domain = proposals.firstOrNull { it.kind == SeekerKind.DOMAIN && it.display == "givtxt.com" }
        assertNotNull(domain)
        assertEquals("givtxt.com", domain!!.rule.keyword)
        assertEquals(Mailbox.SPAM, domain.rule.mailbox)
    }

    @Test
    fun domainInHamIsNotProposed() {
        val seed = listOf(msg("+15551111111", "Go to https://givtxt.com/abc to claim"))
        val ham = listOf(msg("+15559876543", "See givtxt.com for details"))
        val proposals = mine(seed, ham)

        assertNull(proposals.firstOrNull { it.kind == SeekerKind.DOMAIN })
    }

    // -- Rung 3: AND combinations ----------------------------------------------

    @Test
    fun andCombinationWhenSinglesFail() {
        val seed = listOf(
            msg("+15551111111", "Claim your prize today"),
            msg("+15552222222", "Prize claim ends soon")
        )
        val ham = listOf(
            msg("+15559876543", "Your prize is ready"),
            msg("+15558765432", "Claim your reward")
        )
        val proposals = mine(seed, ham)

        // Neither "prize" nor "claim" qualifies alone: each appears in ham.
        assertNull(proposals.firstOrNull { it.kind == SeekerKind.KEYWORD && it.display == "\"prize\"" })
        assertNull(proposals.firstOrNull { it.kind == SeekerKind.KEYWORD && it.display == "\"claim\"" })

        val and = proposals.firstOrNull { it.kind == SeekerKind.AND_COMBINATION }
        assertNotNull(and)
        assertEquals("\"claim\" AND \"prize\"", and!!.display)
        assertEquals(2, and.seedMatches)
        assertTrue(and.rule.keywordRegex)
        // The stored regex really matches the seed bodies through the engine.
        for (message in seed) {
            val facts = MessageFacts(sender = message.sender, body = message.body, senderInContacts = false)
            assertTrue(RuleEngine.matches(and.rule, facts))
        }
    }

    @Test
    fun andNotProposedWhenSingleQualifies() {
        val seed = listOf(msg("+15551111111", "Claim your prize today"))
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        // "prize" qualifies alone, so no AND combination is needed.
        assertTrue(proposals.none { it.kind == SeekerKind.AND_COMBINATION })
    }

    // -- Replacement and drop-out ----------------------------------------------

    @Test
    fun proposalCoveringOldRuleOffersReplacement() {
        val oldRule = FilterRule(name = "Old win rule", priority = 20, keyword = "win", mailbox = Mailbox.SPAM)
        val seed = listOf(
            msg("+15551111111", "Win a prize today"),
            msg("+15552222222", "Win a prize now")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham, listOf(oldRule))

        val prize = proposals.firstOrNull { it.kind == SeekerKind.KEYWORD && it.display == "\"prize\"" }
        assertNotNull(prize)
        assertEquals(oldRule, prize!!.replacesRule)
    }

    @Test
    fun candidateFullyCoveredByOldRuleIsDropped() {
        val oldRule = FilterRule(name = "Old prize rule", priority = 20, keyword = "prize", mailbox = Mailbox.SPAM)
        val seed = listOf(
            msg("+15551111111", "Claim your prize today"),
            msg("+15552222222", "Win a prize now")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham, listOf(oldRule))

        // "claim" covers only a subset of what the old rule already catches.
        assertNull(proposals.firstOrNull { it.kind == SeekerKind.KEYWORD && it.display == "\"claim\"" })
    }

    // -- Refine -----------------------------------------------------------------

    @Test
    fun refineReturnsExceptionFirst() {
        val target = FilterRule(name = "Prize rule", priority = 15, keyword = "prize", mailbox = Mailbox.SPAM)
        val seed = listOf(
            msg("+15551111111", "Claim your prize today"),
            msg("+15552222222", "Prize winner alert")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val misfiled = msg("+15550001111", "Your prize is waiting")

        val refined = PatternSeeker.refine(target, misfiled, seed, ham, emptyList())
        assertFalse(refined.isEmpty())

        val exception = refined.first()
        assertTrue(exception.isException)
        assertEquals(target, exception.narrowsRule)
        assertEquals("+15550001111", exception.rule.senderExact)
        assertEquals(Mailbox.QUARANTINE, exception.rule.mailbox)
        assertEquals(13, exception.rule.priority)
    }

    @Test
    fun refineNarrowerVariantAvoidsNewHam() {
        val target = FilterRule(name = "Prize rule", priority = 15, keyword = "prize", mailbox = Mailbox.SPAM)
        val seed = listOf(
            msg("+15551111111", "Claim your prize today"),
            msg("+15552222222", "Prize winner alert")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val misfiled = msg("+15550001111", "Your prize is waiting")

        val refined = PatternSeeker.refine(target, misfiled, seed, ham, emptyList())

        // "claim" still covers the target's seed spam and dodges the misfiled message.
        val variant = refined.firstOrNull { it.narrowsRule == target && !it.isException }
        assertNotNull(variant)
        assertEquals(target, variant!!.narrowsRule)
        // The variant's rule must not match the misfiled message.
        val facts = MessageFacts(sender = misfiled.sender, body = misfiled.body, senderInContacts = false)
        assertFalse(RuleEngine.matches(variant.rule, facts))
    }

    // -- Grouping and basics ----------------------------------------------------

    @Test
    fun emptySeedHasNoProposals() {
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        assertTrue(mine(emptyList(), ham).isEmpty())
    }

    @Test
    fun sameSenderToleratesFormatting() {
        assertTrue(PatternSeeker.sameSender("+1 (555) 123-4567", "5551234567"))
        assertTrue(PatternSeeker.sameSender("USPS", "usps"))
        assertFalse(PatternSeeker.sameSender("5551234567", "5551234568"))
    }

    @Test
    fun proposalsGroupedByType() {
        val seed = listOf(
            msg("+15551111111", "Claim your prize today"),
            msg("+15552222222", "Prize claim ends soon")
        )
        val ham = listOf(
            msg("+15559876543", "Your prize is ready"),
            msg("+15558765432", "Claim your reward"),
            msg("+15557654321", "Dinner at six")
        )
        val proposals = mine(seed, ham)
        assertFalse(proposals.isEmpty())

        val groupOrder = mapOf(
            SeekerKind.DOMAIN to 0,
            SeekerKind.PHRASE to 1,
            SeekerKind.AND_COMBINATION to 2,
            SeekerKind.KEYWORD to 3,
        )
        val groups = proposals.map { groupOrder[it.kind] ?: 99 }
        assertEquals(groups.sorted(), groups)
        assertTrue(proposals.none { it.kind == SeekerKind.SENDER })
        assertTrue(proposals.any { it.kind == SeekerKind.AND_COMBINATION })
    }

    // -- V2: word pile ----------------------------------------------------------

    @Test
    fun wordPileHasTwoBuckets() {
        val seed = listOf(msg("+15551111111", "Claim your prize today"))
        val ham = listOf(msg("+15559876543", "You won a prize at bingo"))
        val pile = wordPile(seed, ham)

        // "prize" is in both buckets; "claim" only in spam; "bingo" only in ham.
        assertEquals(WordBucket(spamCount = 1, hamCount = 1), pile["prize"])
        assertEquals(WordBucket(spamCount = 1, hamCount = 0), pile["claim"])
        assertEquals(WordBucket(spamCount = 0, hamCount = 1), pile["bingo"])
    }

    // -- V2: phrase lengthening -------------------------------------------------

    @Test
    fun longerPhraseProposedWhenBigramHitsHam() {
        val seed = listOf(msg("+15551111111", "Win a big prize today"))
        val ham = listOf(
            msg("+15559876543", "Win a big car"),
            msg("+15558765432", "A big prize draw"),
            msg("+15557654321", "Prize today only")
        )
        val proposals = mine(seed, ham)

        // Every bigram hits ham, so none survives — but longer phrases come out clean.
        assertNull(proposals.firstOrNull { it.kind == SeekerKind.PHRASE && it.display == "\"big prize\"" })
        val longer = proposals.firstOrNull { it.kind == SeekerKind.PHRASE && it.display == "\"a big prize today\"" }
        assertNotNull(longer)
        assertEquals(1, longer!!.seedMatches)
    }

    @Test
    fun phrasesCappedAtTwoPerGroup() {
        val seed = listOf(
            msg("+15551111111", "Win a big prize today"),
            msg("+15552222222", "Win a big prize now")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        // Six phrases cover both messages; only the top two survive per group.
        val bothCovering = proposals.filter { it.kind == SeekerKind.PHRASE && it.seedMatches == 2 }
        assertEquals(2, bothCovering.size)
    }

    // -- V2: AND triples ---------------------------------------------------------

    @Test
    fun andTripleWhenPairHitsHam() {
        val seed = listOf(
            msg("+15551111111", "Claim prize alpha"),
            msg("+15552222222", "Claim prize beta")
        )
        val ham = listOf(
            msg("+15559876543", "Claim prize gamma"),
            msg("+15558765432", "Alpha delta"),
            msg("+15557654321", "Beta epsilon")
        )
        val proposals = mine(seed, ham)

        // The pair "claim" + "prize" hits ham, so it is out — but a triple is clean.
        assertNull(
            proposals.firstOrNull { it.kind == SeekerKind.AND_COMBINATION && it.display == "\"claim\" AND \"prize\"" }
        )
        val triple = proposals.firstOrNull {
            it.kind == SeekerKind.AND_COMBINATION && it.display == "\"alpha\" AND \"claim\" AND \"prize\""
        }
        assertNotNull(triple)
        assertEquals(1, triple!!.seedMatches)
        assertTrue(triple.rule.keywordRegex)
        // The stored regex really matches the seed body through the engine.
        val facts = MessageFacts(sender = seed[0].sender, body = seed[0].body, senderInContacts = false)
        assertTrue(RuleEngine.matches(triple.rule, facts))
    }

    @Test
    fun andCombinationsCappedAtFive() {
        // Every word appears in ham, so none qualifies alone — but the
        // six pairs are clean, and only five survive the cap.
        val seed = listOf(
            msg("+15551111111", "Red apple"),
            msg("+15552222222", "Red banana"),
            msg("+15553333333", "Blue cherry"),
            msg("+15554444444", "Blue durian"),
            msg("+15555555555", "Green elderberry"),
            msg("+15556666666", "Green fig")
        )
        val ham = listOf(
            msg("+15559876543", "Red car"),
            msg("+15558765432", "Blue sky"),
            msg("+15557654321", "Green grass"),
            msg("+15556543210", "Apple pie"),
            msg("+15555432109", "Banana bread"),
            msg("+15554321098", "Cherry bomb"),
            msg("+15553210987", "Durian smell"),
            msg("+15552109876", "Elderberry wine"),
            msg("+15551098765", "Fig leaf")
        )
        val proposals = mine(seed, ham)

        val combos = proposals.filter { it.kind == SeekerKind.AND_COMBINATION }
        assertEquals(5, combos.size)
    }

    // -- Exact duplicates ------------------------------------------------------

    @Test
    fun exactDuplicateKeywordProposalIsDropped() {
        val seed = listOf(msg("+15551111111", "Vote today"))
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val rules = listOf(
            FilterRule(name = "Vote to Spam", priority = 15, keyword = "vote", mailbox = Mailbox.SPAM)
        )
        val proposals = mine(seed, ham, rules)

        // The "vote" proposal IS the existing rule — dropped, not offered
        // as a delete-and-recreate replacement.
        assertTrue(proposals.none { it.rule.keyword == "vote" })
    }

    @Test
    fun exactDuplicateDomainProposalIsDropped() {
        val seed = listOf(msg("+15551111111", "See https://example.com/x for details"))
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val rules = listOf(
            FilterRule(name = "example.com to Spam", priority = 15, keyword = "example.com", mailbox = Mailbox.SPAM)
        )
        val proposals = mine(seed, ham, rules)

        assertTrue(proposals.none { it.display == "example.com" })
    }

    // -- Thread-scoped suggestions ----------------------------------------------

    @Test
    fun forThreadKeepsOnlyProposalsMatchingTheMessage() {
        val target = msg("+15551111111", "Claim your free prize now")
        val other = msg("+15552222222", "Zebra quokka wombat")
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(listOf(target, other), ham)

        val scoped = PatternSeeker.forThread(proposals, target)
        assertTrue(scoped.isNotEmpty())
        assertTrue(scoped.all { PatternSeeker.matchesMessage(it.rule, target) })
        assertTrue(scoped.none { it.rule.keyword == "zebra" })
        assertTrue(scoped.any { it.rule.keyword == "prize" })
    }

    @Test
    fun forThreadWithNoSafePatternIsEmpty() {
        val target = msg("+15551111111", "Dinner at six")
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(listOf(target), ham)

        assertTrue(PatternSeeker.forThread(proposals, target).isEmpty())
    }

    // -- V2: single-word cap -----------------------------------------------------

    @Test
    fun singleWordsCappedAtTen() {
        val seed = listOf(
            msg("+15551111111", "Alpha bravo charlie delta"),
            msg("+15552222222", "Echo foxtrot golf hotel"),
            msg("+15553333333", "India juliet kilo lima")
        )
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        // Twelve spam-only words, each covering one message: ten at most.
        val keywords = proposals.filter { it.kind == SeekerKind.KEYWORD }
        assertEquals(10, keywords.size)
    }
}
