package org.fossify.messages.helpers

import kotlinx.serialization.json.Json
import java.io.InputStream

object FilterRulesImporter {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** Parses a rule-set file, or returns null when it is not a valid one. */
    fun parseRuleSet(inputStream: InputStream): FilterRuleSet? {
        return try {
            val text = inputStream.bufferedReader().use { it.readText() }
            val ruleSet = json.decodeFromString(FilterRuleSet.serializer(), text)
            if (ruleSet.version > FilterRuleSet.CURRENT_VERSION) {
                null
            } else {
                ruleSet
            }
        } catch (e: Exception) {
            null
        }
    }
}
