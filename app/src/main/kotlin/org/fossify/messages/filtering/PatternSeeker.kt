package org.fossify.messages.filtering

import org.fossify.messages.models.FilterRule
import org.fossify.messages.models.Mailbox

/**
 * One message as the seeker sees it: sender, body, and the two sender
 * facts that outrank content (see the spec's rung 0). Everything here is
 * plain data — no Android, no database — so the whole miner is pure and
 * unit-testable on the JVM.
 */
data class SeekerMessage(
    val sender: String,
    val body: String,
    val senderInContacts: Boolean,
    val repliedTo: Boolean,
    /**
     * The message carries an image part (revision 12): read off its
     * stored attachments at load time. SMS never has one. The refine
     * pass re-evaluates the target rule against these messages, so a
     * target carrying a "has an image" condition is judged on the real
     * fact, never the false default.
     */
    val hasImage: Boolean = false,
)

/** What kind of pattern a proposal carries. */
enum class SeekerKind {
    /** An exact sender number. */
    SENDER,

    /** One body word. */
    KEYWORD,

    /** A link's registrable domain (matched as a body substring). */
    DOMAIN,

    /** An ordered word sequence. */
    PHRASE,

    /** Two words that must both appear (stored as a lookahead regex). */
    AND_COMBINATION,
}

/**
 * One proposed rule, with the evidence that makes it checkable: the
 * pattern, how many seed messages it covers, how many ham messages it
 * hits (always 0 — a candidate that hits ham never becomes a proposal),
 * and one example message. [rule] is the [FilterRule] the proposal
 * creates when selected (id 0; the caller inserts it). [replacesRule]
 * names an existing Spam rule this proposal would replace instead of
 * joining, and [narrowsRule] names the rule a refine variant would
 * rewrite in place. [isException] marks a refine exception rule: a new
 * rule above the broad one, not a rewrite of it.
 *
 * [seedMatches] and the example are always recomputed from the rule's
 * real coverage (see [PatternSeeker.finish]), so the counts a proposal
 * shows are the counts its rule would actually catch — never the looser
 * token counts mining started from.
 */
data class SeekerProposal(
    val kind: SeekerKind,
    /** Human-readable pattern, e.g. `"prize"`, `"win a prize"`, `givtxt.com`, `"prize" AND "claim"`. */
    val display: String,
    val seedMatches: Int,
    val hamMatches: Int,
    val exampleSender: String,
    val exampleBody: String,
    val rule: FilterRule,
    val replacesRule: FilterRule? = null,
    val narrowsRule: FilterRule? = null,
    val isException: Boolean = false,
)

/**
 * One word's two buckets: how many spam messages and how many ham
 * messages contain it. A word is a keeper only when its ham count is
 * zero — found in spam, never in ham.
 */
data class WordBucket(val spamCount: Int, val hamCount: Int)

/**
 * Pass 1 — the word pile: every unique word with its two buckets.
 * This pile is the vocabulary for everything downstream.
 */
fun wordPile(seed: List<SeekerMessage>, ham: List<SeekerMessage>): Map<String, WordBucket> {
    val spamCounts = seed.flatMap { PatternSeeker.tokens(it.body).toSet() }.groupingBy { it }.eachCount()
    val hamCounts = ham.flatMap { PatternSeeker.tokens(it.body).toSet() }.groupingBy { it }.eachCount()
    return (spamCounts.keys + hamCounts.keys).associateWith { word ->
        WordBucket(spamCount = spamCounts[word] ?: 0, hamCount = hamCounts[word] ?: 0)
    }
}

/**
 * The pattern seeker (spec: dragon-pattern-seeker-spec.md): mine the
 * Spam folder for patterns that never hit a good message, and propose
 * them as rules Ben picks. One mining pass, used twice — seek mines the
 * whole Spam folder, refine re-runs it with one more ham example and a
 * target rule.
 *
 * V2 (2026-11-08): four passes, in the agreed order. Pass 1 builds the
 * word pile — every unique word with its two buckets (spam count, ham
 * count) — and everything downstream uses only that pile. Pass 2 builds
 * sequential combos (phrases), lengthening until each has no ham hits.
 * Pass 3 builds non-sequential combos (AND), adding words until no ham
 * hits. Pass 4 is single words, at most ten, no ham hits. The safety
 * bar is precision only: a candidate qualifies if ham hits = 0, and
 * keepers are spam-only by the two buckets. Suggestions come most
 * specific first (senders and domains, then phrases, then combos, then
 * single words), most catches first inside each tier — and the list
 * stays short: the caps are starting defaults, not fixed numbers.
 * A candidate already covered by an existing rule with no improvement
 * is dropped, not proposed.
 */
object PatternSeeker {

    /** Priority of seeker-born Spam rules: after contacts (10), before the class rules (30/32). */
    const val SEEKER_PRIORITY = 15

    /** An exception rule sits just above the rule it carves a hole in. */
    const val EXCEPTION_PRIORITY_STEP = 2

    /** Lowest priority an exception rule may take: still below contacts (10). */
    const val EXCEPTION_PRIORITY_FLOOR = 11

    /** Most proposals shown in one list, backstop behind the per-tier caps. */
    const val MAX_PROPOSALS = 100

    private const val MAX_SENDER_PROPOSALS = 20
    private const val MAX_DOMAIN_PROPOSALS = 10
    private const val MAX_KEYWORD_PROPOSALS = 10
    private const val MAX_PHRASE_PROPOSALS = 30
    private const val MAX_PHRASE_TOTAL = 10
    private const val MAX_PHRASES_PER_GROUP = 2
    private const val MAX_COMBINATION_PROPOSALS = 5
    private const val MAX_AND_WORDS = 40
    private const val MAX_AND_TRIPLE_PAIRS = 20

    /** Longest phrase built by the lengthening walk (bigrams up to 5-grams). */
    private const val MAX_PHRASE_NGRAM = 5

    /** Words too short to mean anything on their own. */
    private const val MIN_WORD_LENGTH = 3

    /** Tokens that carry no signal, skipped as single-word candidates. */
    val STOPWORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one",
        "our", "out", "has", "have", "this", "that", "with", "they", "been", "were", "will", "would",
        "there", "their", "what", "when", "where", "which", "who", "whom", "how", "why", "from", "about",
        "into", "than", "then", "them", "these", "those", "him", "his", "hers", "its", "per", "via",
        "yours", "your", "ours", "my", "mine", "me", "we", "us", "he", "she",
        "it", "is", "am", "an", "as", "at", "be", "by", "do", "go", "if", "in", "no", "of",
        "on", "or", "so", "to", "up", "hi", "hey", "ok", "okay", "please", "thanks", "thank", "just",
        "like", "get", "got", "let", "see", "now", "new", "two", "also", "may", "way", "day",
        "yes", "yet", "try", "use", "used", "etc", "www", "com", "org", "net", "http", "https"
    )

    /**
     * Mine [seed] (Spam folder) against [ham] (everything else) and
     * propose safe patterns. [existingRules] is the current enabled rule
     * set, used only to spot replacements and to drop candidates an
     * existing Spam rule already covers.
     */
    fun mine(
        seed: List<SeekerMessage>,
        ham: List<SeekerMessage>,
        existingRules: List<FilterRule>,
    ): List<SeekerProposal> {
        if (seed.isEmpty()) return emptyList()

        val seedTokenSets = seed.map { tokens(it.body).toSet() }
        val seedTokenLists = seed.map { tokensAll(it.body) }
        val hamNormalized = ham.map { normalizedBody(it.body) }
        val hamBodies = ham.map { it.body.lowercase() }

        // Pass 1 — the word pile: every unique word with its two buckets.
        val pile = wordPile(seed, ham)

        val proposals = ArrayList<SeekerProposal>()

        // Rung 0: sender numbers. A pattern never fires on a contact, so
        // senders in contacts are skipped outright.
        proposals += senderProposals(seed, ham)

        // Pass 4 (generated here, shown last): single words, from the pile.
        val wordProposals = wordProposals(seed, seedTokenSets, pile)
        proposals += wordProposals

        // Pass 2: phrases (bigrams up to 5-grams), lengthened until clean.
        proposals += phraseProposals(seed, seedTokenLists, hamNormalized)

        // Link domains, grouped with the senders at the top.
        proposals += domainProposals(seed, hamBodies)

        // Pass 3: AND combinations, only from words that do not qualify
        // alone — simplest pattern that works wins.
        val qualifyingWords = wordProposals.mapNotNull { it.rule.keyword }.toSet()
        proposals += andProposals(seed, seedTokenSets, ham, qualifyingWords)

        return finish(proposals, seed, existingRules)
    }

    /**
     * Refine [targetRule] after it misfiled [misfiled]: the misfiled
     * message becomes one more ham example and the same mining pass
     * re-runs. Returns an exception proposal first (a rule above the
     * broad one, carving a hole for the misfiled sender), then narrower
     * variants of the target rule that still cover its seed spam while
     * dodging the new ham message.
     */
    fun refine(
        targetRule: FilterRule,
        misfiled: SeekerMessage,
        seed: List<SeekerMessage>,
        ham: List<SeekerMessage>,
        existingRules: List<FilterRule>,
    ): List<SeekerProposal> {
        val newHam = ham + misfiled
        val targetCoverage = seedCoverage(targetRule, seed)
        if (targetCoverage.isEmpty()) return emptyList()

        val out = ArrayList<SeekerProposal>()

        // (b) Exception rule above the broad one: the misfiled sender's
        // next messages file to Not in Contacts instead of Spam.
        val exceptionPriority = maxOf(EXCEPTION_PRIORITY_FLOOR, targetRule.priority - EXCEPTION_PRIORITY_STEP)
        out += SeekerProposal(
            kind = SeekerKind.SENDER,
            display = "Except ${misfiled.sender}",
            seedMatches = 0,
            hamMatches = 0,
            exampleSender = misfiled.sender,
            exampleBody = misfiled.body,
            rule = FilterRule(
                name = "Seeker exception: ${misfiled.sender} - 0 spam, 0 ham",
                priority = exceptionPriority,
                senderExact = misfiled.sender,
                mailbox = Mailbox.QUARANTINE,
            ),
            narrowsRule = targetRule,
            isException = true,
        )

        // (a) Narrower variants: re-mined patterns that still cover the
        // target rule's seed spam and hit none of the new ham.
        val mined = mine(seed, newHam, existingRules)
        val variants = mined
            .filter { !it.isException && it.replacesRule == null }
            .filter { proposal ->
                val coverage = proposalCoverage(proposal, seed)
                coverage.any { it in targetCoverage }
            }
            .map { it.copy(narrowsRule = targetRule) }
            .take(MAX_COMBINATION_PROPOSALS)
        out += variants

        return out
    }

    // -- Rung 0: sender numbers -------------------------------------------------

    private fun senderProposals(
        seed: List<SeekerMessage>,
        ham: List<SeekerMessage>,
    ): List<SeekerProposal> {
        val hamSenders = ham.map { it.sender }
        val bySender = seed
            .filter { !it.senderInContacts }
            .groupBy { it.sender }
        return bySender.entries
            .filter { (sender, _) -> hamSenders.none { sameSender(it, sender) } }
            .map { (sender, messages) ->
                val example = messages.first()
                SeekerProposal(
                    kind = SeekerKind.SENDER,
                    display = sender,
                    seedMatches = messages.size,
                    hamMatches = 0,
                    exampleSender = sender,
                    exampleBody = example.body,
                    rule = FilterRule(
                        name = ruleName(sender, messages.size),
                        priority = SEEKER_PRIORITY,
                        senderExact = sender,
                        mailbox = Mailbox.SPAM,
                    ),
                )
            }
            .sortedWith(compareByDescending<SeekerProposal> { it.seedMatches }.thenBy { it.display })
            .take(MAX_SENDER_PROPOSALS)
    }

    // -- Pass 4: single words --------------------------------------------------

    /**
     * Single words from the pile: a word is a keeper only when its ham
     * bucket is empty. No cap here — [finish] picks the top ten after
     * re-examination, preferring words that cover messages nothing
     * above them covers.
     */
    private fun wordProposals(
        seed: List<SeekerMessage>,
        seedTokenSets: List<Set<String>>,
        pile: Map<String, WordBucket>,
    ): List<SeekerProposal> {
        val docCount = seedTokenSets.flatten().groupingBy { it }.eachCount()
        return docCount.keys
            .filter { (pile[it]?.hamCount ?: 0) == 0 }
            .map { word ->
                val matching = seed.withIndex()
                    .filter { indexed -> word in seedTokenSets[indexed.index] }
                    .map { it.value }
                val example = matching.first()
                SeekerProposal(
                    kind = SeekerKind.KEYWORD,
                    display = "\"$word\"",
                    seedMatches = matching.size,
                    hamMatches = 0,
                    exampleSender = example.sender,
                    exampleBody = example.body,
                    rule = FilterRule(
                        name = ruleName("\"$word\"", matching.size),
                        priority = SEEKER_PRIORITY,
                        keyword = word,
                        mailbox = Mailbox.SPAM,
                    ),
                )
            }
            .sortedWith(compareByDescending<SeekerProposal> { it.seedMatches }.thenBy { it.display })
    }

    // -- Pass 2: phrases -------------------------------------------------------

    /**
     * Sequential combos: n-grams from length 2 up to [MAX_PHRASE_NGRAM].
     * The lengthening walk is emergent — a short phrase with ham hits is
     * dropped, while a longer phrase containing it may still come out
     * clean — so only zero-ham phrases survive, longest included.
     */
    private fun phraseProposals(
        seed: List<SeekerMessage>,
        seedTokenLists: List<List<String>>,
        hamNormalized: List<String>,
    ): List<SeekerProposal> {
        val phraseToIndices = LinkedHashMap<String, MutableSet<Int>>()
        for ((index, toks) in seedTokenLists.withIndex()) {
            for (n in 2..MAX_PHRASE_NGRAM) {
                for (i in 0..toks.size - n) {
                    val window = toks.subList(i, i + n)
                    if (!hasSignal(window)) continue
                    val phrase = window.joinToString(" ")
                    phraseToIndices.getOrPut(phrase) { LinkedHashSet() }.add(index)
                }
            }
        }
        return phraseToIndices.entries
            .filter { (phrase, _) -> hamNormalized.none { " $it ".contains(" $phrase ") } }
            .map { (phrase, indices) ->
                val example = seed[indices.first()]
                SeekerProposal(
                    kind = SeekerKind.PHRASE,
                    display = "\"$phrase\"",
                    seedMatches = indices.size,
                    hamMatches = 0,
                    exampleSender = example.sender,
                    exampleBody = example.body,
                    rule = FilterRule(
                        name = ruleName("\"$phrase\"", indices.size),
                        priority = SEEKER_PRIORITY,
                        keyword = phrase,
                        mailbox = Mailbox.SPAM,
                    ),
                )
            }
            .sortedWith(compareByDescending<SeekerProposal> { it.seedMatches }.thenBy { it.display })
            .take(MAX_PHRASE_PROPOSALS)
    }

    // -- Link domains (grouped with keywords) ----------------------------------

    private fun domainProposals(
        seed: List<SeekerMessage>,
        hamBodies: List<String>,
    ): List<SeekerProposal> {
        val domainToIndices = LinkedHashMap<String, MutableSet<Int>>()
        for ((index, message) in seed.withIndex()) {
            for (domain in linkDomains(message.body)) {
                domainToIndices.getOrPut(domain) { LinkedHashSet() }.add(index)
            }
        }
        return domainToIndices.entries
            .filter { (domain, _) -> hamBodies.none { it.contains(domain) } }
            .map { (domain, indices) ->
                val example = seed[indices.first()]
                SeekerProposal(
                    kind = SeekerKind.DOMAIN,
                    display = domain,
                    seedMatches = indices.size,
                    hamMatches = 0,
                    exampleSender = example.sender,
                    exampleBody = example.body,
                    rule = FilterRule(
                        name = ruleName(domain, indices.size),
                        priority = SEEKER_PRIORITY,
                        keyword = domain,
                        mailbox = Mailbox.SPAM,
                    ),
                )
            }
            .sortedWith(compareByDescending<SeekerProposal> { it.seedMatches }.thenBy { it.display })
            .take(MAX_DOMAIN_PROPOSALS)
    }

    // -- Pass 3: AND combinations ----------------------------------------------

    /**
     * Non-sequential combos: pairs from words that do not qualify alone,
     * then triples — a ham-hit pair extended with one more word, until
     * clean. Only the highest-coverage ham-hit pairs are extended, so
     * the walk stays cheap.
     */
    private fun andProposals(
        seed: List<SeekerMessage>,
        seedTokenSets: List<Set<String>>,
        ham: List<SeekerMessage>,
        qualifyingWords: Set<String>,
    ): List<SeekerProposal> {
        // Only words that do not qualify alone: simplest pattern wins.
        val freq = seedTokenSets.flatten().groupingBy { it }.eachCount()
        val candidates = freq.keys
            .filter { it !in qualifyingWords }
            .sortedByDescending { freq[it] ?: 0 }
            .take(MAX_AND_WORDS)
            .toSet()
        if (candidates.size < 2) return emptyList()

        val hamTokenSets = ham.map { tokens(it.body).toSet() }
        val pairToIndices = LinkedHashMap<Pair<String, String>, MutableSet<Int>>()
        for ((index, tokenSet) in seedTokenSets.withIndex()) {
            val present = tokenSet.intersect(candidates).sorted()
            for (i in present.indices) {
                for (j in i + 1 until present.size) {
                    val pair = present[i] to present[j]
                    pairToIndices.getOrPut(pair) { LinkedHashSet() }.add(index)
                }
            }
        }
        val cleanPairs = pairToIndices.entries
            .filter { (pair, _) ->
                hamTokenSets.none { pair.first in it && pair.second in it }
            }
        val dirtyPairs = pairToIndices.entries
            .filter { (pair, _) ->
                hamTokenSets.any { pair.first in it && pair.second in it }
            }
            .sortedByDescending { it.value.size }
            .take(MAX_AND_TRIPLE_PAIRS)
        // Triples: extend each dirty pair with one more word; keep the
        // ones that come out clean. Seed coverage comes from the pair's
        // own indices, narrowed to messages holding the third word.
        val tripleToIndices = LinkedHashMap<List<String>, MutableSet<Int>>()
        for ((pair, pairIndices) in dirtyPairs) {
            for (third in candidates) {
                if (third == pair.first || third == pair.second) continue
                val words = listOf(pair.first, pair.second, third).sorted()
                if (tripleToIndices.containsKey(words)) continue
                val indices = pairIndices.filter { idx -> third in seedTokenSets[idx] }.toMutableSet()
                if (indices.isEmpty()) continue
                if (hamTokenSets.none { tokenSet -> words.all { it in tokenSet } }) {
                    tripleToIndices[words] = indices
                }
            }
        }
        val pairProposals = cleanPairs.map { (pair, indices) ->
            val example = seed[indices.first()]
            val regex = andRegex(pair.first, pair.second)
            SeekerProposal(
                kind = SeekerKind.AND_COMBINATION,
                display = "\"${pair.first}\" AND \"${pair.second}\"",
                seedMatches = indices.size,
                hamMatches = 0,
                exampleSender = example.sender,
                exampleBody = example.body,
                rule = FilterRule(
                    name = ruleName("\"${pair.first}\" AND \"${pair.second}\"", indices.size),
                    priority = SEEKER_PRIORITY,
                    keyword = regex,
                    keywordRegex = true,
                    mailbox = Mailbox.SPAM,
                ),
            )
        }
        val tripleProposals = tripleToIndices.map { (words, indices) ->
            val example = seed[indices.first()]
            val display = words.joinToString(" AND ") { "\"$it\"" }
            SeekerProposal(
                kind = SeekerKind.AND_COMBINATION,
                display = display,
                seedMatches = indices.size,
                hamMatches = 0,
                exampleSender = example.sender,
                exampleBody = example.body,
                rule = FilterRule(
                    name = ruleName(display, indices.size),
                    priority = SEEKER_PRIORITY,
                    keyword = andRegex(words),
                    keywordRegex = true,
                    mailbox = Mailbox.SPAM,
                ),
            )
        }
        return (pairProposals + tripleProposals)
            .sortedWith(compareByDescending<SeekerProposal> { it.seedMatches }.thenBy { it.display })
            .take(MAX_COMBINATION_PROPOSALS)
    }

    /** Two words that must both appear, as one regex the engine already understands. */
    fun andRegex(first: String, second: String): String {
        return "(?=.*${Regex.escape(first)})(?=.*${Regex.escape(second)})"
    }

    /** Three or more words that must all appear, as one lookahead regex. */
    fun andRegex(words: List<String>): String {
        return words.joinToString("") { "(?=.*${Regex.escape(it)})" }
    }

    // -- Replacement and drop-out ----------------------------------------------

    /**
     * Finish the list: recompute every proposal's counts from what its
     * rule would really catch (a phrase mined from tokens does not
     * always match as a raw substring), drop proposals whose rule
     * catches nothing, apply the replacement rule — a proposal that
     * covers everything an existing Spam rule covers offers to replace
     * it; a proposal an existing rule already covers with no improvement
     * is dropped — then assemble the tiers, most specific first:
     * senders and domains, then phrases (top two per group, where a
     * group is one coverage set, and ten phrases at most), then word
     * combos (five at most), then single words (ten at most, most
     * catches first — this tier is the re-examination step, a message
     * nothing above matched gets its last chance here through its own
     * words). Within each tier, most catches first; phrases break catch
     * ties toward the shorter phrase, the broader catch inside the group.
     */
    private fun finish(
        proposals: List<SeekerProposal>,
        seed: List<SeekerMessage>,
        existingRules: List<FilterRule>,
    ): List<SeekerProposal> {
        val spamRules = existingRules.filter { it.enabled && it.mailbox == Mailbox.SPAM }
        val kept = ArrayList<Pair<SeekerProposal, Set<Int>>>()
        for (proposal in proposals) {
            val coverage = proposalCoverage(proposal, seed)
            if (coverage.isEmpty()) continue
            // Counts and example from real coverage, not mining counts.
            val example = seed[coverage.min()]
            val honest = proposal.copy(
                seedMatches = coverage.size,
                exampleSender = example.sender,
                exampleBody = example.body,
            )
            var drop = false
            var replaces: FilterRule? = null
            for (rule in spamRules) {
                val oldCoverage = seedCoverage(rule, seed)
                if (oldCoverage.isEmpty()) continue
                if (oldCoverage.all { it in coverage }) {
                    // The proposal covers everything the old rule covers:
                    // it can replace it (more precise or simpler).
                    if (replaces == null || oldCoverage.size > seedCoverage(replaces, seed).size) {
                        replaces = rule
                    }
                } else if (coverage.all { it in oldCoverage }) {
                    // The old rule already covers everything this proposal
                    // would catch: no improvement, drop the proposal.
                    drop = true
                    break
                }
            }
            if (!drop) {
                kept += (if (replaces != null) honest.copy(replacesRule = replaces) else honest) to coverage
            }
        }

        val byCatches = compareByDescending<Pair<SeekerProposal, Set<Int>>> { it.first.seedMatches }
            .thenBy { it.first.display }
        val senders = kept.filter { it.first.kind == SeekerKind.SENDER }.sortedWith(byCatches)
        val domains = kept.filter { it.first.kind == SeekerKind.DOMAIN }.sortedWith(byCatches)
        val phrases = kept.filter { it.first.kind == SeekerKind.PHRASE }
            .groupBy { it.second }
            .values.flatMap { group ->
                group.sortedWith(
                    compareByDescending<Pair<SeekerProposal, Set<Int>>> { it.first.seedMatches }
                        .thenBy { wordCount(it.first) }
                        .thenBy { it.first.display }
                ).take(MAX_PHRASES_PER_GROUP)
            }
            .sortedWith(
                compareByDescending<Pair<SeekerProposal, Set<Int>>> { it.first.seedMatches }
                    .thenBy { wordCount(it.first) }
                    .thenBy { it.first.display }
            )
            .take(MAX_PHRASE_TOTAL)
        val combos = kept.filter { it.first.kind == SeekerKind.AND_COMBINATION }
            .sortedWith(byCatches)
            .take(MAX_COMBINATION_PROPOSALS)
        // Single words last: most catches first, ten at most. This tier
        // is the re-examination step — a message nothing above matched
        // gets its last chance here, through its own words.
        val keywords = kept.filter { it.first.kind == SeekerKind.KEYWORD }
            .sortedWith(byCatches)
            .take(MAX_KEYWORD_PROPOSALS)
        return (senders + domains + phrases + combos + keywords).map { it.first }.take(MAX_PROPOSALS)
    }

    /** Words in a proposal's pattern, for the phrase tiebreak (shorter first). */
    private fun wordCount(proposal: SeekerProposal): Int {
        return proposal.rule.keyword?.trim()?.split(Regex("\\s+"))?.size ?: 0
    }

    /** Seed indices a proposal's rule would catch (its coverage). */
    fun proposalCoverage(proposal: SeekerProposal, seed: List<SeekerMessage>): Set<Int> {
        return seed.withIndex()
            .filter { (_, message) -> ruleMatchesSeed(proposal.rule, message) }
            .map { it.index }
            .toSet()
    }

    /** Seed indices an existing rule catches. */
    private fun seedCoverage(rule: FilterRule, seed: List<SeekerMessage>): Set<Int> {
        return seed.withIndex()
            .filter { (_, message) -> ruleMatchesSeed(rule, message) }
            .map { it.index }
            .toSet()
    }

    /** Whether [rule] matches one seed message, using the same engine semantics as arrival. */
    private fun ruleMatchesSeed(rule: FilterRule, message: SeekerMessage): Boolean {
        val facts = MessageFacts(
            sender = message.sender,
            body = message.body,
            senderInContacts = message.senderInContacts,
            repliedTo = message.repliedTo,
            hasImage = message.hasImage,
        )
        return RuleEngine.matches(rule, facts)
    }

    // -- Text helpers ------------------------------------------------------------

    /** Signal-bearing tokens of a body: lowercased, stopwords and short/pure-digit tokens skipped. */
    fun tokens(body: String): List<String> {
        return tokensAll(body).filter { it.length >= MIN_WORD_LENGTH && it !in STOPWORDS && it.any { ch -> ch.isLetter() } }
    }

    /** Every token of a body, lowercased, in order (stopwords kept: phrases need contiguity). */
    fun tokensAll(body: String): List<String> {
        return body.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }

    /** A body reduced to its tokens joined with single spaces, for phrase matching. */
    fun normalizedBody(body: String): String {
        return tokensAll(body).joinToString(" ")
    }

    /** A phrase has signal if at least one of its tokens is a kept word. */
    private fun hasSignal(tokens: List<String>): Boolean {
        return tokens.any { it.length >= MIN_WORD_LENGTH && it !in STOPWORDS && it.any { ch -> ch.isLetter() } }
    }

    /** Registrable domains of links in a body (last two labels, www stripped). */
    fun linkDomains(body: String): Set<String> {
        val hosts = HashSet<String>()
        val urlPattern = Regex("""https?://([A-Za-z0-9.\-]+)""")
        for (match in urlPattern.findAll(body)) {
            hosts += match.groupValues[1].lowercase()
        }
        // Bare domains: a token that looks like host.tld with a path or not.
        val barePattern = Regex("""\b([A-Za-z0-9\-]+\.[A-Za-z]{2,}(?:/[^\s]*)?)""")
        for (match in barePattern.findAll(body)) {
            val host = match.groupValues[1].substringBefore("/").lowercase()
            if (!host.startsWith("www.")) hosts += host
        }
        return hosts.mapNotNull { registrableDomain(it) }.toSet()
    }

    /** Last two labels of a host (www stripped); null when there is no dot. */
    fun registrableDomain(host: String): String? {
        val clean = host.lowercase().removePrefix("www.")
        val parts = clean.split(".").filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return parts.takeLast(2).joinToString(".")
    }

    /** Two senders are the same number, tolerating formatting: last 10 digits when both are long. */
    fun sameSender(a: String, b: String): Boolean {
        val digitsA = a.filter { it.isDigit() }
        val digitsB = b.filter { it.isDigit() }
        if (digitsA.isNotEmpty() && digitsA == digitsB) return true
        if (digitsA.length >= 10 && digitsB.length >= 10) {
            return digitsA.takeLast(10) == digitsB.takeLast(10)
        }
        if (digitsA.isEmpty() && digitsB.isEmpty()) {
            return a.equals(b, ignoreCase = true)
        }
        return false
    }

    /** Rule name carrying its origin: the pattern plus its birth evidence counts. */
    private fun ruleName(display: String, seedMatches: Int): String {
        return "Seeker: $display - $seedMatches spam, 0 ham"
    }
}
