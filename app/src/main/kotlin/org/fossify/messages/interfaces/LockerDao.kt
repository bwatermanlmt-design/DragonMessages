package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.LockedMessage
import org.fossify.messages.models.LockedMessageChunk
import org.fossify.messages.models.LockedThread

/**
 * One message's ciphertext, in 256KB pieces (see LockedMessageChunk).
 * A piece is small enough that no single row can blow the 2MB
 * CursorWindow, however big the photo behind it is — and a multi-row
 * result pages through the window, so loading a message's pieces is
 * safe at any size.
 */
@Dao
interface LockedMessageChunksDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(chunks: List<LockedMessageChunk>)

    /** One message's pieces, in order — join them for the ciphertext. */
    @Query("SELECT * FROM locked_message_chunks WHERE id = :id AND is_mms = :isMMS ORDER BY chunk_index ASC")
    fun getByMessage(id: Long, isMMS: Boolean): List<LockedMessageChunk>

    @Query("SELECT COUNT(*) FROM locked_message_chunks WHERE id = :id AND is_mms = :isMMS")
    fun countByMessage(id: Long, isMMS: Boolean): Int

    /**
     * Drop every piece belonging to a thread's messages. The EXISTS
     * matches on the composite key, so an SMS and an MMS sharing a
     * provider id are told apart.
     */
    @Query(
        "DELETE FROM locked_message_chunks WHERE EXISTS " +
            "(SELECT 1 FROM locked_messages lm WHERE lm.id = locked_message_chunks.id " +
            "AND lm.is_mms = locked_message_chunks.is_mms AND lm.thread_id = :threadId)"
    )
    fun deleteByThread(threadId: Long)

    @Query("DELETE FROM locked_message_chunks WHERE id = :id AND is_mms = :isMMS")
    fun deleteByMessage(id: Long, isMMS: Boolean)

    @Query("DELETE FROM locked_message_chunks")
    fun deleteAll()
}

@Dao
interface LockedMessagesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(message: LockedMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(messages: List<LockedMessage>)

    @Query("SELECT * FROM locked_messages WHERE thread_id = :threadId ORDER BY date ASC, id ASC")
    fun getByThread(threadId: Long): List<LockedMessage>

    @Query("SELECT * FROM locked_messages WHERE id = :id AND is_mms = :isMMS")
    fun getById(id: Long, isMMS: Boolean): LockedMessage?

    @Query("SELECT * FROM locked_messages ORDER BY date ASC, id ASC")
    fun getAll(): List<LockedMessage>

    @Query("SELECT COUNT(*) FROM locked_messages WHERE thread_id = :threadId")
    fun countByThread(threadId: Long): Int

    @Query("SELECT COUNT(*) FROM locked_messages")
    fun countAll(): Int

    @Query("DELETE FROM locked_messages WHERE thread_id = :threadId")
    fun deleteByThread(threadId: Long)

    @Query("DELETE FROM locked_messages WHERE id = :id AND is_mms = :isMMS")
    fun deleteById(id: Long, isMMS: Boolean)

    @Query("DELETE FROM locked_messages")
    fun deleteAll()
}

@Dao
interface LockedThreadsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(thread: LockedThread)

    @Query("SELECT * FROM locked_threads WHERE thread_id = :threadId")
    fun get(threadId: Long): LockedThread?

    @Query("SELECT * FROM locked_threads")
    fun getAll(): List<LockedThread>

    @Query("DELETE FROM locked_threads WHERE thread_id = :threadId")
    fun delete(threadId: Long)

    @Query("DELETE FROM locked_threads")
    fun deleteAll()
}
