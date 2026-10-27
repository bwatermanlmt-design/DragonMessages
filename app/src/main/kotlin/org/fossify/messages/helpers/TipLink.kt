package org.fossify.messages.helpers

/**
 * The "Buy me a beer" tip link (revision 9): a quiet row at the end of
 * Settings, a plain external link, no popups, no nagging, no Play
 * billing. This is a voluntary tip, not a paywall, not ads, not a
 * subscription — and tapping it sends no user data anywhere beyond the
 * link tap itself.
 *
 * [TIP_URL] is the single tip destination, supplied by Ben before
 * publish: his PayPal link. (An earlier revision of this comment
 * forbade hardcoding a real payment URL; that guard no longer applies
 * to this value, which Ben supplied himself in chat.) While blank,
 * the Settings row hides itself; with a destination set, the row
 * shows and opens it in the browser.
 */
object TipLink {
    const val TIP_URL = "https://paypal.me/groveborn"
}
