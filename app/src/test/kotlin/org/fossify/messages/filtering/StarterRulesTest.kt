package org.fossify.messages.filtering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public starter pack is generic-only: a fresh install must never
 * seed person-specific rules, keywords, or tags (publish prep).
 */
class StarterRulesTest {

    @Test
    fun `public default pack has only the four generic rules`() {
        assertEquals(
            listOf(
                "Contacts to My Contacts",
                "Short codes to Auth & Services",
                "Service IDs to Auth & Services",
                "Default: unknown senders to Not in Contacts"
            ),
            StarterRules.defaultRules().map { it.name }
        )
    }

    @Test
    fun `public default pack contains no person-specific content`() {
        val blob = StarterRules.defaultRules().joinToString("|") {
            "${it.name} ${it.keyword} ${it.keywordsJson} ${it.assignTag}"
        }
        assertFalse(blob.contains("Stop2End", ignoreCase = true))
        assertFalse(blob.contains("Gerrit", ignoreCase = true))
        assertFalse(blob.contains("campaigns", ignoreCase = true))
    }

    @Test
    fun `repair only adds missing generics and never deletes`() {
        // Nothing present: all four generics are missing.
        assertEquals(4, StarterRules.missingFrom(emptySet()).size)

        // Everything present: nothing to add.
        val allGenericNames = StarterRules.defaultRules().map { it.name }.toSet()
        assertTrue(StarterRules.missingFrom(allGenericNames).isEmpty())

        // Person-specific rules from earlier packs present alongside the
        // generics: still nothing to add — and missingFrom names nothing
        // to delete, so the repair leaves them exactly where they are.
        val withPersonalRules = allGenericNames + setOf(
            "Campaign fingerprint (Stop2End) to Spam",
            "Addressed to Gerrit to Not in Contacts"
        )
        assertTrue(StarterRules.missingFrom(withPersonalRules).isEmpty())

        // One generic missing: exactly that one is returned.
        val missingOne = allGenericNames - "Short codes to Auth & Services"
        assertEquals(
            listOf("Short codes to Auth & Services"),
            StarterRules.missingFrom(missingOne).map { it.name }
        )
    }
}
