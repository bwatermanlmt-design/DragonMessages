package org.fossify.messages.helpers

/**
 * The "Buy me a beer" tip link (revision 9): a quiet row at the end of
 * Settings, a plain external link, no popups, no nagging, no Play
 * billing. This is a voluntary tip, not a paywall, not ads, not a
 * subscription — and tapping it sends no user data anywhere beyond the
 * link tap itself.
 *
 * [TIP_URL] is a single configurable placeholder, blank until Ben
 * supplies the real destination (Ko-fi, PayPal, GitHub Sponsors, or
 * similar) before publish. Never invent or hardcode a real payment URL
 * here. While blank, the Settings row hides itself.
 */
object TipLink {
    const val TIP_URL = ""
}
