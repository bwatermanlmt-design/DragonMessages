package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations", indices = [(Index(value = ["thread_id"], unique = true))])
data class Conversation(
    @PrimaryKey @ColumnInfo(name = "thread_id") var threadId: Long,
    @ColumnInfo(name = "snippet") var snippet: String,
    @ColumnInfo(name = "date") var date: Int,
    @ColumnInfo(name = "read") var read: Boolean,
    @ColumnInfo(name = "title") var title: String,
    @ColumnInfo(name = "photo_uri") var photoUri: String,
    @ColumnInfo(name = "is_group_conversation") var isGroupConversation: Boolean,
    @ColumnInfo(name = "phone_number") var phoneNumber: String,
    @ColumnInfo(name = "is_scheduled") var isScheduled: Boolean = false,
    @ColumnInfo(name = "uses_custom_title") var usesCustomTitle: Boolean = false,
    @ColumnInfo(name = "archived") var isArchived: Boolean = false,
    @ColumnInfo(name = "unread_count") var unreadCount: Int = 0,
    @ColumnInfo(name = "mailbox", defaultValue = Mailbox.CONTACTS) var mailbox: String = Mailbox.CONTACTS,
    @ColumnInfo(name = "manually_filed", defaultValue = "0") var manuallyFiled: Boolean = false,
    @ColumnInfo(name = "muted", defaultValue = "0") var muted: Boolean = false,
) {

    companion object {
        /**
         * Merges a provider-built conversation with the stored row for the
         * same thread. The provider's message facts (snippet, date, read
         * state, participants…) win, but state that exists only inside the
         * app — a custom title, the mailbox filing, the hand-filed mark and
         * the per-conversation mute — is carried over from the stored row.
         * A provider refresh can therefore never reset a filing: this is
         * what makes folders sticky across list rebuilds. A null [stored]
         * (a genuinely new thread) yields the provider row untouched.
         */
        fun mergeProviderConversation(stored: Conversation?, provider: Conversation): Conversation {
            if (stored == null) {
                return provider
            }

            var merged = provider
            if (stored.usesCustomTitle) {
                merged = merged.copy(
                    title = stored.title,
                    usesCustomTitle = true
                )
            }
            if (merged.mailbox != stored.mailbox) {
                merged = merged.copy(mailbox = stored.mailbox)
            }
            if (merged.manuallyFiled != stored.manuallyFiled) {
                merged = merged.copy(manuallyFiled = stored.manuallyFiled)
            }
            if (merged.muted != stored.muted) {
                merged = merged.copy(muted = stored.muted)
            }
            return merged
        }

        fun areItemsTheSame(old: Conversation, new: Conversation): Boolean {
            return old.threadId == new.threadId
        }

        fun areContentsTheSame(old: Conversation, new: Conversation): Boolean {
            return old.snippet == new.snippet &&
                old.date == new.date &&
                old.read == new.read &&
                old.title == new.title &&
                old.photoUri == new.photoUri &&
                old.isGroupConversation == new.isGroupConversation &&
                old.phoneNumber == new.phoneNumber &&
                old.unreadCount == new.unreadCount &&
                old.mailbox == new.mailbox &&
                old.manuallyFiled == new.manuallyFiled &&
                old.muted == new.muted
        }
    }
}
