package org.fossify.messages.filtering

import kotlinx.serialization.json.Json
import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * The filter core: a pure function from (rules, message facts) to a verdict.
 * No Android dependencies, so the whole behaviour is unit-testable on the JVM.
 *
 * Rules are considered in ascending priority order (ties broken by id);
 * disabled rules are skipped and the first matching rule wins. If no rule
 * matches, the verdict carries no rule, and [ThreadPrecedence] files a
 * brand-new thread to Not in Contacts while leaving an existing thread
 * where it is.
 */
object RuleEngine {

    fun evaluate(rules: List<FilterRule>, facts: MessageFacts): RuleVerdict {
        val ordered = rules
            .filter { it.enabled }
            .sortedWith(compareBy({ it.priority }, { it.id }))

        for (rule in ordered) {
            if (matches(rule, facts)) {
                return RuleVerdict(
                    mailbox = rule.mailbox,
                    assignTag = rule.assignTag,
                    matchedRule = rule
                )
            }
        }

        return RuleVerdict(
            mailbox = Mailbox.QUARANTINE,
            assignTag = null,
            matchedRule = null
        )
    }

    /**
     * Whether [rule] matches [facts]. Each set condition contributes one
     * boolean; how they combine depends on [FilterRule.conditionMode]:
     * ALL (the historical AND — every set condition must hold) or ANY
     * (OR across conditions — any one set condition matching is enough).
     * A rule with no set condition matches everything (catch-all) in
     * either mode — the long-standing behavior, preserved.
     *
     * The keyword condition (revision 9) matches if ANY of the rule's
     * keywords matches (OR within the keyword set), as a substring or —
     * when [FilterRule.keywordRegex] is set — as a case-insensitive
     * regular expression, against the body OR the OCR text (recognized
     * image text, when OCR ran). An invalid regex never matches and never
     * throws: filing proceeds as if that keyword were not there.
     */
    fun matches(rule: FilterRule, facts: MessageFacts): Boolean {
        val results = ArrayList<Boolean>()
        rule.senderExact?.let {
            results += PhoneNumbers.equal(it, facts.sender)
        }
        rule.senderPattern?.let {
            results += PhoneNumbers.matchesPattern(it, facts.sender)
        }
        rule.senderContains?.let { needle ->
            results += facts.sender.contains(needle, ignoreCase = true) ||
                facts.senderName.contains(needle, ignoreCase = true)
        }
        rule.senderInContacts?.let {
            results += (it == facts.senderInContacts)
        }
        rule.senderTag?.let {
            results += (it in facts.senderTags)
        }
        val keywords = effectiveKeywords(rule)
        if (keywords.isNotEmpty()) {
            results += keywords.any { keyword ->
                keywordMatches(keyword, facts.body, rule.keywordRegex) ||
                    keywordMatches(keyword, facts.ocrText, rule.keywordRegex)
            }
        }
        rule.containsLink?.let {
            results += (it == SenderClassifier.containsLink(facts.body))
        }
        rule.senderClass?.let {
            results += (it == SenderClassifier.classify(facts.sender))
        }
        rule.repliedTo?.let {
            results += (it == facts.repliedTo)
        }
        rule.manuallyFiled?.let {
            results += (it == facts.manuallyFiled)
        }
        if (results.isEmpty()) return true
        return if (rule.conditionMode == CONDITION_MODE_ANY) results.any { it } else results.all { it }
    }

    /** The keywords a rule matches on: the multi-keyword list when set, else the legacy single keyword. */
    fun effectiveKeywords(rule: FilterRule): List<String> {
        val raw = rule.keywordsJson
        if (!raw.isNullOrBlank()) {
            val parsed = try {
                json.decodeFromString<List<String>>(raw)
            } catch (e: Exception) {
                emptyList()
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return listOfNotNull(rule.keyword?.takeIf { it.isNotEmpty() })
    }

    /** Encode a keyword list for storage (the inverse of [effectiveKeywords]). */
    fun keywordsToJson(keywords: List<String>): String {
        return json.encodeToString(keywords)
    }

    /** Compact JSON instance for keyword lists (no pretty printing in the database). */
    private val json = Json { ignoreUnknownKeys = true }

    /** One keyword against a body: substring, or case-insensitive regex when [isRegex]. Never throws. */
    fun keywordMatches(keyword: String, body: String, isRegex: Boolean): Boolean {
        if (keyword.isEmpty()) return false
        if (!isRegex) return body.contains(keyword, ignoreCase = true)
        return try {
            // Bound the input: a pathological pattern against a very long
            // body must not stall arrival-time filing.
            Regex(keyword, RegexOption.IGNORE_CASE).containsMatchIn(body.take(REGEX_INPUT_CAP))
        } catch (e: Exception) {
            false
        }
    }

    /** Condition mode: every set condition must hold (the default). */
    const val CONDITION_MODE_ALL = "ALL"

    /** Condition mode: any one set condition matching is enough. */
    const val CONDITION_MODE_ANY = "ANY"

    /** Longest body a regex keyword is run against (characters). */
    const val REGEX_INPUT_CAP = 10_000
}
