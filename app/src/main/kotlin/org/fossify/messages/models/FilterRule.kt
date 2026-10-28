package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One filter rule: a set of conditions (a null condition does not constrain
 * the match) plus one action - delivery to a [Mailbox] and an optional tag to
 * assign to the sender. Rules are evaluated in ascending [priority] order;
 * the first matching rule wins.
 */
@Entity(tableName = "filter_rules")
data class FilterRule(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    @ColumnInfo(name = "name") var name: String,
    @ColumnInfo(name = "enabled") var enabled: Boolean = true,
    @ColumnInfo(name = "priority") var priority: Int = 0,

    // Conditions. All set conditions must match (logical AND) unless
    // [conditionMode] is ANY, in which case any one set condition matching
    // is enough. A rule with no set condition matches everything (catch-all),
    // in either mode — that is the long-standing behavior, preserved.
    @ColumnInfo(name = "sender_exact") var senderExact: String? = null,
    @ColumnInfo(name = "sender_pattern") var senderPattern: String? = null,
    @ColumnInfo(name = "sender_contains") var senderContains: String? = null,
    @ColumnInfo(name = "sender_in_contacts") var senderInContacts: Boolean? = null,
    @ColumnInfo(name = "sender_tag") var senderTag: String? = null,
    @ColumnInfo(name = "keyword") var keyword: String? = null,
    // Revision 9: several keywords per rule, as a JSON array string. When
    // set, the keyword condition matches if ANY keyword matches (OR within
    // the set). [keyword] stays as the legacy single keyword and is kept
    // in sync with the first entry, so older readers still see something.
    @ColumnInfo(name = "keywords_json") var keywordsJson: String? = null,
    // Revision 9: how the set conditions combine — "ALL" (default, the
    // historical AND) or "ANY" (OR across conditions).
    @ColumnInfo(name = "condition_mode", defaultValue = "'ALL'") var conditionMode: String = "ALL",
    // Revision 9: when true, keywords are regular expressions (case-
    // insensitive) instead of substrings. An invalid pattern never matches
    // and never crashes filing (see RuleEngine).
    @ColumnInfo(name = "keyword_regex", defaultValue = "0") var keywordRegex: Boolean = false,
    @ColumnInfo(name = "contains_link") var containsLink: Boolean? = null,
    @ColumnInfo(name = "sender_class") var senderClass: String? = null,
    // Revision 10: two more optional conditions. Null means "does not
    // constrain the match", exactly like the other Boolean conditions:
    // repliedTo reads whether Ben has sent a message in the thread,
    // manuallyFiled reads whether the thread was filed by hand (thread
    // flag or sender-level record). Existing rules keep null (any).
    @ColumnInfo(name = "replied_to") var repliedTo: Boolean? = null,
    @ColumnInfo(name = "manually_filed") var manuallyFiled: Boolean? = null,

    // Action.
    @ColumnInfo(name = "mailbox") var mailbox: String,
    @ColumnInfo(name = "assign_tag") var assignTag: String? = null,

    // Revision 7: a rule created through the macro editor is a macro — the
    // same engine row with a friendlier face. Macros that are not marked
    // automatic never fire on arrival or in the retro pass; they only run
    // when Ben runs them by hand. (The rule action already names a mailbox
    // id as a plain string, so a macro can target any folder, system or
    // custom, with no second engine.)
    @ColumnInfo(name = "is_macro", defaultValue = "0") var isMacro: Boolean = false,
    @ColumnInfo(name = "macro_automatic", defaultValue = "0") var macroAutomatic: Boolean = false,
)
