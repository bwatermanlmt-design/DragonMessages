package org.fossify.messages.filtering

/** One label → mailbox mapping, in consultation order, as [LabelHomes] sees it. */
data class LabelHomeMapping(
    val label: String,
    val mailbox: String,
)

/**
 * Label-mapped contact homes (Ben's revision 7 ruling): an address-book
 * label Ben has mapped to a folder makes that folder the home of every
 * contact carrying the label. Pure, so the receive path and the retro
 * pass share exactly this behaviour:
 *
 *  - Mappings are consulted in their stored order (creation order); for a
 *    person contact, the first mapping whose label the contact carries
 *    wins.
 *  - The business rule still wins for a business-labeled contact: its
 *    home is decided by the business label alone. Only if the business
 *    label itself is mapped does the business move — mapping some other
 *    label the contact also carries does not move it. (Ben: the business
 *    classification keeps its Auth & Services home unless he deliberately
 *    maps that label.)
 *  - Label titles compare case-insensitively, like the business check.
 *
 * Returns the mapped mailbox id, or null when no mapping applies — the
 * caller then falls back to the ordinary contact homes.
 */
object LabelHomes {

    fun homeFor(
        labels: Collection<String>,
        businessLabel: String?,
        mappings: List<LabelHomeMapping>,
    ): String? {
        if (businessLabel != null) {
            return mappings.firstOrNull { it.label.equals(businessLabel, ignoreCase = true) }?.mailbox
        }

        val carried = labels.map { it.trim() }
        return mappings.firstOrNull { mapping ->
            carried.any { it.equals(mapping.label.trim(), ignoreCase = true) }
        }?.mailbox
    }
}
