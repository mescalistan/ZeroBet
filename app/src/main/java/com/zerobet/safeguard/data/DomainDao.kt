package com.zerobet.safeguard.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainDao {

    @Query("SELECT * FROM blocked_domains ORDER BY userAdded DESC, category, pattern")
    fun observeBlocked(): Flow<List<BlockedDomain>>

    @Query("SELECT * FROM whitelisted_banks ORDER BY name")
    fun observeWhitelist(): Flow<List<WhitelistedBank>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlocked(domain: BlockedDomain): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhitelisted(bank: WhitelistedBank): Long

    @Delete
    suspend fun deleteBlocked(domain: BlockedDomain)

    @Delete
    suspend fun deleteWhitelisted(bank: WhitelistedBank)

    // ---- Domini da fonti esterne ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSynced(domains: List<SyncedDomain>)

    /** Rimuove le righe di una fonte non riconfermate dall'ultima sincronizzazione riuscita. */
    @Query("DELETE FROM synced_domains WHERE source = :source AND batch < :batch")
    suspend fun deleteStaleSynced(source: String, batch: Long)

    @Query("SELECT COUNT(*) FROM synced_domains WHERE source = :source")
    suspend fun countSynced(source: String): Int

    @Query("SELECT domain FROM synced_domains")
    suspend fun allSyncedDomains(): List<String>

    // ---- Domini auto-appresi ----

    @Query("SELECT * FROM auto_learned_blocked_domains ORDER BY learnedAt DESC")
    fun observeLearned(): Flow<List<AutoLearnedBlockedDomain>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLearned(domain: AutoLearnedBlockedDomain): Long

    @Delete
    suspend fun deleteLearned(domain: AutoLearnedBlockedDomain)

    // ---- Stato delle sincronizzazioni ----

    @Query("SELECT * FROM sync_status ORDER BY source")
    fun observeSyncStatus(): Flow<List<SyncStatus>>

    @Query("SELECT * FROM sync_status WHERE source = :source")
    suspend fun syncStatus(source: String): SyncStatus?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncStatus(status: SyncStatus)
}
