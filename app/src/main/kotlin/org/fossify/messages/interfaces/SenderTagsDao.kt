package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.SenderTags

@Dao
interface SenderTagsDao {
    @Query("SELECT * FROM sender_tags WHERE number = :number")
    fun getForNumber(number: String): SenderTags?

    @Query("SELECT * FROM sender_tags ORDER BY number ASC")
    fun getAll(): List<SenderTags>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(senderTags: SenderTags)

    @Query("DELETE FROM sender_tags")
    fun deleteAll()
}
