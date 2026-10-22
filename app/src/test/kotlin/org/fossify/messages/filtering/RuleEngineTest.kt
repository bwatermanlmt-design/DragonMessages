package org.fossify.messages.filtering

import org.fossify.messages.helpers.FilterRuleSet
import org.fossify.messages.helpers.FilterRulesExporter
import org.fossify.messages.helpers.FilterRulesImporter
import org.fossify.messages.helpers.toRuleSet
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox
import org.fossify.messages.models.SenderTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val starterRules = StarterRules.defaultRules()

    private fun facts(
        sender: String = "+14805550137",
        body: String = "hello there",
        inContacts: Boolean = false,
        tags: Set<String> = emptySet()
    ) = MessageFacts(
        sender = sender,
        body = body,
        senderInContacts = inContacts,
        senderTags = tags
    )

    // region starter pack

    @Test
    fun `stop2end fingerprint goes to spam and assigns campaigns tag`() {
        val verdict = RuleEngine.evaluate(
            starterRules,
            facts(body = "Ben, early voting is open! Reply Stop2End to opt out")
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
        assertEquals(StarterRules.TAG_CAMPAIGNS, verdict.assignTag)
        assertTrue(verdict.silencesNotifications)
    }

    @Test
    fun `keyword matching is case insensitive`() {
        val verdict = RuleEngine.evaluate(starterRules, facts(body = "reply stop2end"))
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `gerrit mail goes to quarantine`() {
        val verdict = RuleEngine.evaluate(
            starterRules,
            facts(body = "Hi Gerrit, your brokerage account needs attention")
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
        assertTrue(verdict.silencesNotifications)
    }

    @Test
    fun `short code goes to auth services`() {
        val verdict = RuleEngine.evaluate(
            starterRules,
            facts(sender = "40404", body = "Your verification code is 123456")
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
        assertFalse(verdict.silencesNotifications)
    }

    @Test
    fun `contact sender goes to contacts`() {
        val verdict = RuleEngine.evaluate(starterRules, facts(inContacts = true))
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
        assertFalse(verdict.silencesNotifications)
    }

    @Test
    fun `approved tag sender goes to contacts`() {
        val verdict = RuleEngine.evaluate(
            starterRules,
            facts(tags = setOf(StarterRules.TAG_APPROVED))
        )
        assertEquals(Mailbox.CONTACTS, verdict.mailbox)
    }

    @Test
    fun `unknown long number defaults to quarantine`() {
        val verdict = RuleEngine.evaluate(starterRules, facts())
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
        assertEquals("Default: unknown senders to Quarantine", verdict.ruleName)
    }

    @Test
    fun `first match wins - spam fingerprint beats contacts and short code`() {
        val fromContact = RuleEngine.evaluate(
            starterRules,
            facts(body = "deal inside! Stop2End", inContacts = true)
        )
        assertEquals(Mailbox.SPAM, fromContact.mailbox)

        val fromShortCode = RuleEngine.evaluate(
            starterRules,
            facts(sender = "72727", body = "your code, Stop2End")
        )
        assertEquals(Mailbox.SPAM, fromShortCode.mailbox)
    }

    @Test
    fun `rules are honoured in priority order even when passed unordered`() {
        val verdict = RuleEngine.evaluate(
            starterRules.shuffled(),
            facts(body = "Stop2End", inContacts = true)
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    // endregion

    // region engine semantics

    @Test
    fun `no rules at all falls back to built in quarantine default`() {
        val verdict = RuleEngine.evaluate(emptyList(), facts())
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
        assertNull(verdict.matchedRule)
    }

    @Test
    fun `disabled rules are skipped`() {
        val rules = listOf(
            FilterRule(name = "off", priority = 1, enabled = false, keyword = "hello", mailbox = Mailbox.SPAM),
            FilterRule(name = "on", priority = 2, keyword = "hello", mailbox = Mailbox.AUTH)
        )
        val verdict = RuleEngine.evaluate(rules, facts())
        assertEquals(Mailbox.AUTH, verdict.mailbox)
        assertEquals("on", verdict.ruleName)
    }

    @Test
    fun `conditions combine with AND`() {
        val rules = listOf(
            FilterRule(
                name = "strangers with keyword",
                priority = 1,
                senderInContacts = false,
                keyword = "winner",
                mailbox = Mailbox.SPAM
            )
        )
        // a contact sending the keyword does not match
        val fromContact = RuleEngine.evaluate(rules, facts(body = "winner", inContacts = true))
        assertEquals(Mailbox.QUARANTINE, fromContact.mailbox)
        assertNull(fromContact.matchedRule)

        val fromStranger = RuleEngine.evaluate(rules, facts(body = "winner", inContacts = false))
        assertEquals(Mailbox.SPAM, fromStranger.mailbox)
    }

    @Test
    fun `sender exact matches across formatting and country code`() {
        val rules = listOf(
            FilterRule(name = "exact", priority = 1, senderExact = "+1 (480) 382-8285", mailbox = Mailbox.SPAM)
        )
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(sender = "+14803828285")).mailbox)
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(sender = "4803828285")).mailbox)
        assertEquals(Mailbox.QUARANTINE, RuleEngine.evaluate(rules, facts(sender = "+14805550137")).mailbox)
    }

    @Test
    fun `sender pattern uses star wildcards over digits`() {
        val rules = listOf(
            FilterRule(name = "pattern", priority = 1, senderPattern = "480*", mailbox = Mailbox.SPAM)
        )
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(sender = "+14803828285")).mailbox)
        assertEquals(Mailbox.QUARANTINE, RuleEngine.evaluate(rules, facts(sender = "+15205550137")).mailbox)

        val suffixRules = listOf(
            FilterRule(name = "suffix", priority = 1, senderPattern = "*3828285", mailbox = Mailbox.SPAM)
        )
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(suffixRules, facts(sender = "+14803828285")).mailbox)
    }

    @Test
    fun `contains link condition`() {
        val rules = listOf(
            FilterRule(name = "links", priority = 1, containsLink = true, mailbox = Mailbox.QUARANTINE)
        )
        assertEquals(
            Mailbox.QUARANTINE,
            RuleEngine.evaluate(rules, facts(body = "see https://example.com/x")).mailbox
        )
        val noLink = RuleEngine.evaluate(rules, facts(body = "no link here"))
        assertNull(noLink.matchedRule)
        assertTrue(SenderClassifier.containsLink("go to example.com now"))
        assertFalse(SenderClassifier.containsLink("meeting at 3.30 tomorrow"))
    }

    @Test
    fun `sender tag condition requires the tag`() {
        val rules = listOf(
            FilterRule(name = "tagged", priority = 1, senderTag = "work", mailbox = Mailbox.AUTH)
        )
        assertEquals(Mailbox.AUTH, RuleEngine.evaluate(rules, facts(tags = setOf("work"))).mailbox)
        assertNull(RuleEngine.evaluate(rules, facts(tags = setOf("family"))).matchedRule)
    }

    // endregion

    // region sender classification

    @Test
    fun `sender classification`() {
        assertEquals(SenderClassifier.SHORT_CODE, SenderClassifier.classify("40404"))
        assertEquals(SenderClassifier.SHORT_CODE, SenderClassifier.classify("727272"))
        assertEquals(SenderClassifier.LONG_NUMBER, SenderClassifier.classify("+14803828285"))
        assertEquals(SenderClassifier.LONG_NUMBER, SenderClassifier.classify("4803828285"))
        assertEquals(SenderClassifier.ALPHANUMERIC, SenderClassifier.classify("BANK-ALERT"))
        assertEquals(SenderClassifier.ALPHANUMERIC, SenderClassifier.classify("VM-Verizon"))
    }

    // endregion

    // region rule set export

    @Test
    fun `starter pack rule set exports and reimports identically`() {
        val ruleSet = starterRules.toRuleSet(
            listOf(SenderTags.of("+14803828285", listOf(StarterRules.TAG_CAMPAIGNS)))
        )
        val text = FilterRulesExporter.json.encodeToString(FilterRuleSet.serializer(), ruleSet)
        val parsed = FilterRulesImporter.parseRuleSet(text.byteInputStream())
        assertEquals(ruleSet, parsed)
    }

    @Test
    fun `importer rejects garbage and newer versions`() {
        assertNull(FilterRulesImporter.parseRuleSet("not json".byteInputStream()))
        assertNull(FilterRulesImporter.parseRuleSet("""{"version": 99, "rules": []}""".byteInputStream()))
    }

    // endregion
}
