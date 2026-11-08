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
    fun senderWithNoHamIsProposed() {
        val seed = listOf(msg("+15551234567", "Claim your free prize now"))
        val ham = listOf(msg("+15559876543", "Dinner at six"))
        val proposals = mine(seed, ham)

        val sender = proposals.firstOrNull { it.kind == SeekerKind.SENDER }
        assertNotNull(sender)
        assertEquals("+15551234567", sender!!.display)
        assertEquals(1, sender.seedMatches)
        assertEquals(0, sender.hamMatches)
        assertEquals("+15551234567", sender.rule.senderExact)
        assertEquals(PatternSeeker.SEEKER_PRIORITY, sender.rule.priority)
        assertEquals(Mailbox.SPAM, sender.rule.mailbox)
        assertTrue(sender.rule.name.startsWith("Seeker:"))
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
            SeekerKind.SENDER to 0,
            SeekerKind.KEYWORD to 1,
            SeekerKind.DOMAIN to 1,
            SeekerKind.PHRASE to 2,
            SeekerKind.AND_COMBINATION to 3,
        )
        val groups = proposals.map { groupOrder[it.kind] ?: 99 }
        assertEquals(groups.sorted(), groups)
        assertTrue(proposals.any { it.kind == SeekerKind.SENDER })
        assertTrue(proposals.any { it.kind == SeekerKind.AND_COMBINATION })
    }
}
