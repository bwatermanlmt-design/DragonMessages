package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Delete
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

    /**
     * The rules an automatic evaluation (arrival, retro pass) may fire:
     * every enabled rule except macros Ben has not marked automatic —
     * those exist to be run by hand, never to file on their own.
     */
    @Query("SELECT * FROM filter_rules WHERE enabled = 1 AND (is_macro = 0 OR macro_automatic = 1) ORDER BY priority ASC, id ASC")
    fun getAllEnabledForEvaluation(): List<FilterRule>

    /** Every macro (a rule row created through the macro editor), oldest first. */
    @Query("SELECT * FROM filter_rules WHERE is_macro = 1 ORDER BY id ASC")
    fun getAllMacros(): List<FilterRule>

    @Query("SELECT * FROM filter_rules WHERE id = :id")
    fun getById(id: Long): FilterRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(rule: FilterRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rules: List<FilterRule>)

    @Delete
    fun delete(rule: FilterRule)

    @Query("DELETE FROM filter_rules")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM filter_rules")
    fun getCount(): Int
}
