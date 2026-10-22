package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.ConversationWithSnippetOverride

@Dao
interface ConversationsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(conversation: Conversation): Long

    @Query("SELECT (SELECT body FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id WHERE recycle_bin_messages.id IS NULL AND messages.thread_id = conversations.thread_id ORDER BY messages.date DESC LIMIT 1) as new_snippet, * FROM conversations WHERE archived = 0 AND mailbox IN ('contacts', 'auth')")
    fun getNonArchivedWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getNonArchived(): List<Conversation> {
        return getNonArchivedWithLatestSnippet().map { it.toConversation() }
    }

    @Query("SELECT (SELECT body FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id WHERE recycle_bin_messages.id IS NULL AND messages.thread_id = conversations.thread_id ORDER BY messages.date DESC LIMIT 1) as new_snippet, * FROM conversations WHERE archived = 0 AND mailbox = :mailbox")
    fun getByMailboxWithLatestSnippet(mailbox: String): List<ConversationWithSnippetOverride>

    fun getByMailbox(mailbox: String): List<Conversation> {
        return getByMailboxWithLatestSnippet(mailbox).map { it.toConversation() }
    }

    @Query("UPDATE conversations SET mailbox = :mailbox WHERE thread_id = :threadId")
    fun updateMailbox(threadId: Long, mailbox: String)

    /** A move made by Ben himself: change the mailbox and mark it hand-filed. */
    @Query("UPDATE conversations SET mailbox = :mailbox, manually_filed = 1 WHERE thread_id = :threadId")
    fun updateMailboxManuallyFiled(threadId: Long, mailbox: String)

    /** Every non-archived conversation, whatever mailbox it currently sits in. */
    @Query("SELECT * FROM conversations WHERE archived = 0")
    fun getAllNonArchivedConversations(): List<Conversation>

    @Query("SELECT COUNT(*) FROM conversations WHERE archived = 0 AND mailbox = :mailbox")
    fun getCountByMailbox(mailbox: String): Int

    @Query("SELECT (SELECT body FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id WHERE recycle_bin_messages.id IS NULL AND messages.thread_id = conversations.thread_id ORDER BY messages.date DESC LIMIT 1) as new_snippet, * FROM conversations WHERE archived = 1")
    fun getAllArchivedWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getAllArchived(): List<Conversation> {
        return getAllArchivedWithLatestSnippet().map { it.toConversation() }
    }

    @Query("SELECT (SELECT body FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id WHERE recycle_bin_messages.id IS NOT NULL AND messages.thread_id = conversations.thread_id ORDER BY messages.date DESC LIMIT 1) as new_snippet, * FROM conversations WHERE (SELECT COUNT(*) FROM messages LEFT OUTER JOIN recycle_bin_messages ON messages.id = recycle_bin_messages.id WHERE recycle_bin_messages.id IS NOT NULL AND messages.thread_id = conversations.thread_id) > 0")
    fun getAllWithMessagesInRecycleBinWithLatestSnippet(): List<ConversationWithSnippetOverride>

    fun getAllWithMessagesInRecycleBin(): List<Conversation> {
        return getAllWithMessagesInRecycleBinWithLatestSnippet().map { it.toConversation() }
    }

    @Query("SELECT * FROM conversations WHERE thread_id = :threadId")
    fun getConversationWithThreadId(threadId: Long): Conversation?

    @Query("SELECT * FROM conversations WHERE read = 0")
    fun getUnreadConversations(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE title LIKE :text")
    fun getConversationsWithText(text: String): List<Conversation>

    @Query("UPDATE conversations SET read = 1 WHERE thread_id = :threadId")
    fun markRead(threadId: Long)

    @Query("UPDATE conversations SET read = 0 WHERE thread_id = :threadId")
    fun markUnread(threadId: Long)

    @Query("UPDATE conversations SET archived = 1 WHERE thread_id = :threadId")
    fun moveToArchive(threadId: Long)

    @Query("UPDATE conversations SET archived = 0 WHERE thread_id = :threadId")
    fun unarchive(threadId: Long)

    @Query("DELETE FROM conversations WHERE thread_id = :threadId")
    fun deleteThreadId(threadId: Long)
}
