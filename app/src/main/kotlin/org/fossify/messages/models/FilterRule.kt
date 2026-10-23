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

    // Conditions. All set conditions must match (logical AND).
    @ColumnInfo(name = "sender_exact") var senderExact: String? = null,
    @ColumnInfo(name = "sender_pattern") var senderPattern: String? = null,
    @ColumnInfo(name = "sender_contains") var senderContains: String? = null,
    @ColumnInfo(name = "sender_in_contacts") var senderInContacts: Boolean? = null,
    @ColumnInfo(name = "sender_tag") var senderTag: String? = null,
    @ColumnInfo(name = "keyword") var keyword: String? = null,
    @ColumnInfo(name = "contains_link") var containsLink: Boolean? = null,
    @ColumnInfo(name = "sender_class") var senderClass: String? = null,

    // Action.
    @ColumnInfo(name = "mailbox") var mailbox: String,
    @ColumnInfo(name = "assign_tag") var assignTag: String? = null,

    // Revision 7: a rule created through the macro editor is a macro — the
    // same engine row with a friendlier face. Macros that are not marked
    // automatic never fire on arrival or in the retro pass; they only run
    // when Ben runs them by hand. (The rule action already names a mailbox
    // id as a plain string, so a macro can target any folder, system or
    // custom, with no second engine.)
    @ColumnInfo(name = "is_macro") var isMacro: Boolean = false,
    @ColumnInfo(name = "macro_automatic") var macroAutomatic: Boolean = false,
)
