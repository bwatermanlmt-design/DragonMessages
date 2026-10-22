package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.FilterRule

@Dao
interface FilterRulesDao {
    @Query("SELECT * FROM filter_rules ORDER BY priority ASC, id ASC")
    fun getAll(): List<FilterRule>

    @Query("SELECT * FROM filter_rules WHERE enabled = 1 ORDER BY priority ASC, id ASC")
    fun getAllEnabled(): List<FilterRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(rule: FilterRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rules: List<FilterRule>)

    @Query("DELETE FROM filter_rules")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM filter_rules")
    fun getCount(): Int
}
