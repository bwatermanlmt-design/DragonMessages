package org.fossify.messages.filtering

import org.fossify.messages.models.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabelHomesTest {

    private val family = Mailbox.customMailboxId("folder-family")
    private val work = Mailbox.customMailboxId("folder-work")
    private val vendors = Mailbox.customMailboxId("folder-vendors")

    private fun mapping(label: String, mailbox: String) = LabelHomeMapping(label, mailbox)

    @Test
    fun `mapped person label gives its folder`() {
        val home = LabelHomes.homeFor(
            labels = listOf("Family"),
            businessLabel = null,
            mappings = listOf(mapping("Family", family))
        )
        assertEquals(family, home)
    }

    @Test
    fun `unmapped person labels give no label home`() {
        val home = LabelHomes.homeFor(
            labels = listOf("Book Club"),
            businessLabel = null,
            mappings = listOf(mapping("Family", family))
        )
        assertNull(home)
    }

    @Test
    fun `mapping order decides when several labels are mapped`() {
        // The contact carries Work and Family; Work's mapping was made
        // first, so Work wins — the order mappings were created in is
        // the consultation order.
        val home = LabelHomes.homeFor(
            labels = listOf("Family", "Work"),
            businessLabel = null,
            mappings = listOf(mapping("Work", work), mapping("Family", family))
        )
        assertEquals(work, home)
    }

    @Test
    fun `label matching is case insensitive`() {
        val home = LabelHomes.homeFor(
            labels = listOf("family"),
            businessLabel = null,
            mappings = listOf(mapping("Family", family))
        )
        assertEquals(family, home)
    }

    @Test
    fun `business contact without a mapped business label has no label home`() {
        val home = LabelHomes.homeFor(
            labels = listOf("Businesses"),
            businessLabel = "Businesses",
            mappings = listOf(mapping("Family", family))
        )
        assertNull(home)
    }

    @Test
    fun `business contact with another label mapped still has no label home`() {
        // The business rule wins: a mapped second label does not move a
        // business — only mapping the business label itself does.
        val home = LabelHomes.homeFor(
            labels = listOf("Businesses", "Family"),
            businessLabel = "Businesses",
            mappings = listOf(mapping("Family", family))
        )
        assertNull(home)
    }

    @Test
    fun `business label itself mapped gives the folder`() {
        val home = LabelHomes.homeFor(
            labels = listOf("Businesses", "Family"),
            businessLabel = "Businesses",
            mappings = listOf(mapping("Family", family), mapping("Businesses", vendors))
        )
        assertEquals(vendors, home)
    }

    @Test
    fun `services counts as a business label too`() {
        val home = LabelHomes.homeFor(
            labels = listOf("Services"),
            businessLabel = "Services",
            mappings = listOf(mapping("services", vendors))
        )
        assertEquals(vendors, home)
    }
}
