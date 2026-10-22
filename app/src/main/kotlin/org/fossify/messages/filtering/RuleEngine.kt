package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * The filter core: a pure function from (rules, message facts) to a verdict.
 * No Android dependencies, so the whole behaviour is unit-testable on the JVM.
 *
 * Rules are considered in ascending priority order (ties broken by id);
 * disabled rules are skipped and the first matching rule wins. If no rule
 * matches, the built-in default applies: Not in Contacts. The raw verdict
 * is then tempered by [ThreadPrecedence], which owns the final semantics.
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

    /** All set conditions on a rule must hold (logical AND). */
    fun matches(rule: FilterRule, facts: MessageFacts): Boolean {
        rule.senderExact?.let {
            if (!PhoneNumbers.equal(it, facts.sender)) return false
        }
        rule.senderPattern?.let {
            if (!PhoneNumbers.matchesPattern(it, facts.sender)) return false
        }
        rule.senderInContacts?.let {
            if (it != facts.senderInContacts) return false
        }
        rule.senderTag?.let {
            if (it !in facts.senderTags) return false
        }
        rule.keyword?.let {
            if (!facts.body.contains(it, ignoreCase = true)) return false
        }
        rule.containsLink?.let {
            if (it != SenderClassifier.containsLink(facts.body)) return false
        }
        rule.senderClass?.let {
            if (it != SenderClassifier.classify(facts.sender)) return false
        }
        return true
    }
}
