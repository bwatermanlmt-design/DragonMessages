package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.fossify.messages.models.FilterAuditEntry

@Dao
interface FilterAuditDao {
    @Insert
    fun insert(entry: FilterAuditEntry): Long

    @Query("SELECT * FROM filter_audit_log ORDER BY timestamp DESC, id DESC")
    fun getAll(): List<FilterAuditEntry>

    @Query("SELECT * FROM filter_audit_log ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun getRecent(limit: Int): List<FilterAuditEntry>
}
