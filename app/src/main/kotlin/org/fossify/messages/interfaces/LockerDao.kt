package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.LockedMessage
import org.fossify.messages.models.LockedThread

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
