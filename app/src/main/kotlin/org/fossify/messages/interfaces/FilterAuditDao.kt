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

    @Query("SELECT * FROM filter_audit_log WHERE sender = :sender ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun getLatestForSender(sender: String): FilterAuditEntry?

    /**
     * Firings per rule name, for proposal evidence (the rule adjuster).
     * Counts are by name, so two rules sharing a name share one count —
     * the adjuster says so on the row when that applies.
     */
    @Query("SELECT rule_name AS ruleName, COUNT(*) AS firingCount FROM filter_audit_log GROUP BY rule_name")
    fun countByRuleName(): List<RuleNameCount>
}

/** One row of [FilterAuditDao.countByRuleName]: a rule name and how often it fired. */
data class RuleNameCount(
    val ruleName: String,
    val firingCount: Int
)
