package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.Folder
import org.fossify.messages.models.LabelMapping
import org.fossify.messages.models.SenderFiling

/**
 * Revision 7 storage: Ben's own folders, the address-book label →
 * folder mappings, and the sender-level filing record. One DAO — the
 * three tables are one filing subsystem and are always read together.
 */
@Dao
interface FoldersDao {

    // Folders

    @Query("SELECT * FROM folders ORDER BY sort_order ASC, id ASC")
    fun getAllFolders(): List<Folder>

    @Query("SELECT * FROM folders WHERE id = :folderId")
    fun getFolder(folderId: String): Folder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateFolder(folder: Folder): Long

    @Delete
    fun deleteFolder(folder: Folder)

    @Query("SELECT COUNT(*) FROM folders")
    fun getFolderCount(): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM folders")
    fun getMaxFolderSortOrder(): Int

    // Label mappings (consultation order = sort_order)

    /** Revision 9 import: mappings in a file replace the whole set, so the old set goes first. */
    @Query("DELETE FROM label_mappings")
    fun deleteAllLabelMappings()

    @Query("SELECT * FROM label_mappings ORDER BY sort_order ASC, id ASC")
    fun getAllLabelMappings(): List<LabelMapping>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLabelMapping(mapping: LabelMapping): Long

    @Delete
    fun deleteLabelMapping(mapping: LabelMapping)

    @Query("DELETE FROM label_mappings WHERE mailbox = :mailbox")
    fun deleteLabelMappingsForMailbox(mailbox: String)

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM label_mappings")
    fun getMaxLabelMappingSortOrder(): Int

    // Sender-level filings (manual placements that belong to the sender)

    @Query("SELECT * FROM sender_filings")
    fun getAllSenderFilings(): List<SenderFiling>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdateSenderFiling(filing: SenderFiling): Long

    @Query("DELETE FROM sender_filings WHERE number = :number")
    fun deleteSenderFiling(number: String)

    @Query("DELETE FROM sender_filings WHERE mailbox = :mailbox")
    fun deleteSenderFilingsForMailbox(mailbox: String)
}
