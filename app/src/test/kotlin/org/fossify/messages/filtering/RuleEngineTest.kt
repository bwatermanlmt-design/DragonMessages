package org.fossify.messages.filtering

import org.fossify.messages.helpers.FilterRuleDto
import org.fossify.messages.helpers.FilterRuleSet
import org.fossify.messages.helpers.FilterRulesExporter
import org.fossify.messages.helpers.FilterRulesImporter
import org.fossify.messages.helpers.toEntity
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

    // Keyword rules the old starter pack used to ship; kept here as an
    // explicit engine fixture now that the public pack is generic-only.
    private val starterRules = StarterRules.defaultRules() + listOf(
        FilterRule(name = "Test keyword to Spam", priority = 20, keyword = "Stop2End", mailbox = Mailbox.SPAM, assignTag = StarterRules.TAG_CAMPAIGNS),
        FilterRule(name = "Test keyword to Quarantine", priority = 25, keyword = "Gerrit", mailbox = Mailbox.QUARANTINE)
    )

    private fun facts(
        sender: String = "+14805550137",
        body: String = "hello there",
        inContacts: Boolean = false,
        tags: Set<String> = emptySet(),
        repliedTo: Boolean = false,
        manuallyFiled: Boolean = false
    ) = MessageFacts(
        sender = sender,
        body = body,
        senderInContacts = inContacts,
        senderTags = tags,
        repliedTo = repliedTo,
        manuallyFiled = manuallyFiled
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
    fun `gerrit mail goes to not in contacts, which notifies normally`() {
        val verdict = RuleEngine.evaluate(
            starterRules,
            facts(body = "Hi Gerrit, your brokerage account needs attention")
        )
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
        assertFalse(verdict.silencesNotifications)
        assertTrue(verdict.shouldNotify)
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
    fun `alphanumeric service id with a clean body goes to auth`() {
        val verdict = RuleEngine.evaluate(
            starterRules,
            facts(sender = "USPS", body = "Your package arrives Thursday")
        )
        assertEquals(Mailbox.AUTH, verdict.mailbox)
    }

    @Test
    fun `alphanumeric service id with stop2end goes to spam`() {
        val verdict = RuleEngine.evaluate(
            starterRules,
            facts(sender = "USPS", body = "Track your package! Stop2End")
        )
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `muted tag does not change any verdict`() {
        val spammy = RuleEngine.evaluate(
            starterRules,
            facts(body = "deal inside! Stop2End", tags = setOf(StarterRules.TAG_MUTED))
        )
        assertEquals(Mailbox.SPAM, spammy.mailbox)

        val plain = RuleEngine.evaluate(
            starterRules,
            facts(tags = setOf(StarterRules.TAG_MUTED))
        )
        assertEquals(Mailbox.QUARANTINE, plain.mailbox)
        assertFalse(plain.notificationMuted)
    }

    @Test
    fun `unknown long number defaults to not in contacts`() {
        val verdict = RuleEngine.evaluate(starterRules, facts())
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
        assertEquals("Default: unknown senders to Not in Contacts", verdict.ruleName)
    }

    @Test
    fun `contacts are settled before the spam fingerprint, short codes are not`() {
        val fromContact = RuleEngine.evaluate(
            starterRules,
            facts(body = "deal inside! Stop2End", inContacts = true)
        )
        assertEquals(Mailbox.CONTACTS, fromContact.mailbox)

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
            facts(body = "Stop2End")
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

    // region sender-contains condition (revision 7, the macro criterion)

    @Test
    fun `sender contains matches the number or the display name`() {
        val rule = FilterRule(
            name = "sender contains",
            priority = 1,
            senderContains = "landlord",
            mailbox = Mailbox.AUTH
        )
        val byName = MessageFacts(
            sender = "+14805550137",
            body = "hi",
            senderInContacts = true,
            senderName = "The Landlord"
        )
        assertTrue(RuleEngine.matches(rule, byName))

        val byNumber = MessageFacts(
            sender = "+14805550137",
            body = "hi",
            senderInContacts = false
        )
        val numberRule = rule.copy(senderContains = "555")
        assertTrue(RuleEngine.matches(numberRule, byNumber))
        assertFalse(RuleEngine.matches(rule, byNumber))
    }

    @Test
    fun `macro flags survive an export and reimport round trip`() {
        val macro = FilterRule(
            name = "Rent things",
            priority = 15,
            senderContains = "rent",
            mailbox = Mailbox.customMailboxId("folder-family"),
            isMacro = true,
            macroAutomatic = true
        )
        val ruleSet = listOf(macro).toRuleSet(emptyList())
        val text = FilterRulesExporter.json.encodeToString(FilterRuleSet.serializer(), ruleSet)
        val parsed = FilterRulesImporter.parseRuleSet(text.byteInputStream())
        assertEquals(ruleSet, parsed)
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

    // region revision 9 rules: multi-keyword, ANY mode, regex

    private fun keywordsRule(
        keywords: List<String>,
        mailbox: String = Mailbox.SPAM,
        conditionMode: String = RuleEngine.CONDITION_MODE_ALL,
        keywordRegex: Boolean = false,
        priority: Int = 1,
    ) = FilterRuleDto(
        name = "keywords",
        priority = priority,
        keywords = keywords,
        conditionMode = conditionMode,
        keywordRegex = keywordRegex,
        mailbox = mailbox
    ).toEntity()

    @Test
    fun `multi keyword matches if any keyword matches`() {
        val rules = listOf(keywordsRule(listOf("alpha", "beta")))
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(body = "has beta in it")).mailbox)
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(body = "ALPHA here")).mailbox)
        assertNull(RuleEngine.evaluate(rules, facts(body = "neither here")).matchedRule)
    }

    @Test
    fun `legacy single keyword still works when no keyword list is set`() {
        val rules = listOf(
            FilterRule(name = "legacy", priority = 1, keyword = "winner", mailbox = Mailbox.SPAM)
        )
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(body = "winner")).mailbox)
        assertNull(RuleEngine.evaluate(rules, facts(body = "loser")).matchedRule)
    }

    @Test
    fun `malformed keyword json falls back to the legacy keyword`() {
        val rule = FilterRule(
            name = "broken json",
            priority = 1,
            keyword = "fallback",
            keywordsJson = "[not json",
            mailbox = Mailbox.SPAM
        )
        assertTrue(RuleEngine.matches(rule, facts(body = "fallback here")))
        assertFalse(RuleEngine.matches(rule, facts(body = "nothing here")))
        assertEquals(listOf("fallback"), RuleEngine.effectiveKeywords(rule))
    }

    @Test
    fun `ANY mode matches when any one condition matches`() {
        val rules = listOf(
            FilterRule(
                name = "any",
                priority = 1,
                senderInContacts = true,
                keyword = "winner",
                conditionMode = RuleEngine.CONDITION_MODE_ANY,
                mailbox = Mailbox.SPAM
            )
        )
        // contact without the keyword matches via the contacts condition
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(body = "hi", inContacts = true)).mailbox)
        // stranger with the keyword matches via the keyword condition
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(body = "winner", inContacts = false)).mailbox)
        // stranger without the keyword matches neither
        assertNull(RuleEngine.evaluate(rules, facts(body = "hi", inContacts = false)).matchedRule)
    }

    @Test
    fun `ALL mode still requires every condition`() {
        val rules = listOf(
            FilterRule(
                name = "all",
                priority = 1,
                senderInContacts = true,
                keyword = "winner",
                conditionMode = RuleEngine.CONDITION_MODE_ALL,
                mailbox = Mailbox.SPAM
            )
        )
        assertNull(RuleEngine.evaluate(rules, facts(body = "hi", inContacts = true)).matchedRule)
        assertNull(RuleEngine.evaluate(rules, facts(body = "winner", inContacts = false)).matchedRule)
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(body = "winner", inContacts = true)).mailbox)
    }

    @Test
    fun `a rule with no conditions still matches everything in either mode`() {
        val allMode = FilterRule(name = "catch all", priority = 1, mailbox = Mailbox.SPAM)
        val anyMode = allMode.copy(conditionMode = RuleEngine.CONDITION_MODE_ANY)
        assertTrue(RuleEngine.matches(allMode, facts()))
        assertTrue(RuleEngine.matches(anyMode, facts()))
    }

    @Test
    fun `regex keywords match as patterns and are case insensitive`() {
        val rules = listOf(keywordsRule(listOf("win\\d+"), keywordRegex = true))
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(body = "win42 today")).mailbox)
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(rules, facts(body = "WIN99")).mailbox)
        assertNull(RuleEngine.evaluate(rules, facts(body = "winner")).matchedRule)
    }

    @Test
    fun `an invalid regex never matches and never throws`() {
        val rules = listOf(keywordsRule(listOf("[a-"), keywordRegex = true))
        assertNull(RuleEngine.evaluate(rules, facts(body = "anything at all")).matchedRule)
        // and the same keyword as a plain substring still works when the flag is off
        val plain = listOf(keywordsRule(listOf("[a-")))
        assertEquals(Mailbox.SPAM, RuleEngine.evaluate(plain, facts(body = "has [a- in it")).mailbox)
    }

    @Test
    fun `first match wins with the new fields too`() {
        val rules = listOf(
            keywordsRule(listOf("beta"), mailbox = Mailbox.AUTH, priority = 1),
            keywordsRule(listOf("beta"), mailbox = Mailbox.SPAM, priority = 2)
        )
        assertEquals(Mailbox.AUTH, RuleEngine.evaluate(rules, facts(body = "beta")).mailbox)
    }

    @Test
    fun `new rule fields survive an export and reimport round trip`() {
        val rule = keywordsRule(
            listOf("alpha", "beta"),
            mailbox = Mailbox.customMailboxId("folder-family"),
            conditionMode = RuleEngine.CONDITION_MODE_ANY,
            keywordRegex = true
        ).copy(name = "fancy")
        val ruleSet = listOf(rule).toRuleSet(emptyList())
        val text = FilterRulesExporter.json.encodeToString(FilterRuleSet.serializer(), ruleSet)
        val parsed = FilterRulesImporter.parseRuleSet(text.byteInputStream())
        assertEquals(ruleSet, parsed)
    }

    // region revision 9 OCR text (recognized image text feeds keyword matching only)

    private fun ocrFacts(body: String = "", ocrText: String = "") = MessageFacts(
        sender = "+14805550137",
        body = body,
        senderInContacts = false,
        ocrText = ocrText
    )

    @Test
    fun `keyword in ocr text matches like body text`() {
        val rule = keywordsRule(listOf("vote"), mailbox = Mailbox.SPAM)
        val verdict = RuleEngine.evaluate(listOf(rule), ocrFacts(ocrText = "Vote early, vote often"))
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `keyword in neither body nor ocr text does not match`() {
        val rule = keywordsRule(listOf("vote"), mailbox = Mailbox.SPAM)
        val verdict = RuleEngine.evaluate(listOf(rule), ocrFacts(body = "hello", ocrText = "pretty picture"))
        assertEquals(Mailbox.QUARANTINE, verdict.mailbox)
    }

    @Test
    fun `empty ocr text preserves body-only matching`() {
        val rule = keywordsRule(listOf("vote"), mailbox = Mailbox.SPAM)
        val verdict = RuleEngine.evaluate(listOf(rule), ocrFacts(body = "please vote"))
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    @Test
    fun `link rules read body only, never ocr text`() {
        val linkRule = FilterRule(name = "link", mailbox = Mailbox.SPAM, containsLink = true)
        assertFalse(RuleEngine.matches(linkRule, ocrFacts(body = "hello", ocrText = "see http://example.com")))
        assertTrue(RuleEngine.matches(linkRule, ocrFacts(body = "see http://example.com", ocrText = "hello")))
    }

    @Test
    fun `regex keywords match against ocr text too`() {
        val rule = keywordsRule(listOf("vot\\w+"), mailbox = Mailbox.SPAM, keywordRegex = true)
        val verdict = RuleEngine.evaluate(listOf(rule), ocrFacts(ocrText = "VOTING day"))
        assertEquals(Mailbox.SPAM, verdict.mailbox)
    }

    // endregion

    @Test
    fun `an old file without the new fields parses with defaults`() {
        val text = """{"version": 1, "rules": [{"name": "old", "mailbox": "spam", "keyword": "hello"}]}"""
        val parsed = FilterRulesImporter.parseRuleSet(text.byteInputStream())
        val rule = parsed!!.rules.single()
        assertEquals("hello", rule.keyword)
        assertEquals(emptyList<String>(), rule.keywords)
        assertEquals(RuleEngine.CONDITION_MODE_ALL, rule.conditionMode)
        assertFalse(rule.keywordRegex)
        assertEquals("hello", rule.toEntity().keyword)
        assertEquals(listOf("hello"), RuleEngine.effectiveKeywords(rule.toEntity()))
    }

    // region revision 10 conditions: replied-to and hand-filed

    @Test
    fun `replied-to condition matches only answered threads`() {
        val rule = FilterRule(name = "replied", priority = 1, repliedTo = true, mailbox = Mailbox.SPAM)
        assertTrue(RuleEngine.matches(rule, facts(repliedTo = true)))
        assertFalse(RuleEngine.matches(rule, facts(repliedTo = false)))
    }

    @Test
    fun `replied-to condition false matches only unanswered threads`() {
        val rule = FilterRule(name = "unanswered", priority = 1, repliedTo = false, mailbox = Mailbox.SPAM)
        assertTrue(RuleEngine.matches(rule, facts(repliedTo = false)))
        assertFalse(RuleEngine.matches(rule, facts(repliedTo = true)))
    }

    @Test
    fun `hand-filed condition matches only hand-filed threads`() {
        val rule = FilterRule(name = "hand", priority = 1, manuallyFiled = true, mailbox = Mailbox.SPAM)
        assertTrue(RuleEngine.matches(rule, facts(manuallyFiled = true)))
        assertFalse(RuleEngine.matches(rule, facts(manuallyFiled = false)))
    }

    @Test
    fun `hand-filed condition false matches only threads not filed by hand`() {
        val rule = FilterRule(name = "not hand", priority = 1, manuallyFiled = false, mailbox = Mailbox.SPAM)
        assertTrue(RuleEngine.matches(rule, facts(manuallyFiled = false)))
        assertFalse(RuleEngine.matches(rule, facts(manuallyFiled = true)))
    }

    @Test
    fun `null replied-to and hand-filed conditions do not constrain the match`() {
        val rule = FilterRule(
            name = "keyword either way", priority = 1, keyword = "vote",
            repliedTo = null, manuallyFiled = null, mailbox = Mailbox.SPAM
        )
        assertTrue(RuleEngine.matches(rule, facts(body = "please vote", repliedTo = true, manuallyFiled = true)))
        assertTrue(RuleEngine.matches(rule, facts(body = "please vote", repliedTo = false, manuallyFiled = false)))
        assertFalse(RuleEngine.matches(rule, facts(body = "hello", repliedTo = true, manuallyFiled = true)))
    }

    @Test
    fun `replied-to combines with other conditions under ALL`() {
        val rule = FilterRule(
            name = "replied vote", priority = 1, keyword = "vote", repliedTo = true,
            conditionMode = RuleEngine.CONDITION_MODE_ALL, mailbox = Mailbox.SPAM
        )
        assertTrue(RuleEngine.matches(rule, facts(body = "please vote", repliedTo = true)))
        assertFalse(RuleEngine.matches(rule, facts(body = "please vote", repliedTo = false)))
        assertFalse(RuleEngine.matches(rule, facts(body = "hello", repliedTo = true)))
    }

    @Test
    fun `replied-to combines with other conditions under ANY`() {
        val rule = FilterRule(
            name = "replied or vote", priority = 1, keyword = "vote", repliedTo = true,
            conditionMode = RuleEngine.CONDITION_MODE_ANY, mailbox = Mailbox.SPAM
        )
        assertTrue(RuleEngine.matches(rule, facts(body = "please vote", repliedTo = false)))
        assertTrue(RuleEngine.matches(rule, facts(body = "hello", repliedTo = true)))
        assertFalse(RuleEngine.matches(rule, facts(body = "hello", repliedTo = false)))
    }

    @Test
    fun `new conditions survive an export and reimport round trip`() {
        val rule = FilterRule(
            name = "conditions", priority = 1, keyword = "vote",
            repliedTo = true, manuallyFiled = false, mailbox = Mailbox.SPAM
        )
        val ruleSet = listOf(rule).toRuleSet(emptyList())
        val text = FilterRulesExporter.json.encodeToString(FilterRuleSet.serializer(), ruleSet)
        val parsed = FilterRulesImporter.parseRuleSet(text.byteInputStream())
        assertEquals(ruleSet, parsed)
        val roundTripped = parsed!!.rules.single().toEntity()
        assertEquals(true, roundTripped.repliedTo)
        assertEquals(false, roundTripped.manuallyFiled)
    }

    @Test
    fun `an old file without the new conditions parses with nulls`() {
        val text = """{"version": 1, "rules": [{"name": "old", "mailbox": "spam", "keyword": "hello"}]}"""
        val parsed = FilterRulesImporter.parseRuleSet(text.byteInputStream())
        val rule = parsed!!.rules.single()
        assertNull(rule.repliedTo)
        assertNull(rule.manuallyFiled)
        assertNull(rule.toEntity().repliedTo)
        assertNull(rule.toEntity().manuallyFiled)
    }

    // endregion
}
